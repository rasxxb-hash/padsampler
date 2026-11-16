package com.example.padsampler.studio

import com.example.padsampler.audio.NativeAudio
import com.example.padsampler.data.DrumsJson
import com.example.padsampler.data.MidiFile
import com.example.padsampler.data.WavWriter
import com.example.padsampler.model.DrumSynthSpec
import com.example.padsampler.model.DrumLayout
import com.example.padsampler.model.DrumSound
import com.example.padsampler.model.DrumsState
import com.example.padsampler.model.Fader
import com.example.padsampler.platform.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Drums (Studio): kit od 8 zvukova i step sequencer. Stanje je u [state]; svaka promjena patterna ide odmah u
 * C++ Studio (NativeAudio.studioSetPattern). Stanje je dio sesije (pads.json, polje "drums"); fajlovi zvukova
 * su u [samplesDir] zajedno sa semplovima padova. Fajlovi se ovdje ne brišu (može ih koristiti i printani pad);
 * nekorištene briše ViewModel (gcSamples).
 * Zove se iz UI-a i iz MIDI niti (stanje je u StateFlow-u, promjene su atomske).
 */
class DrumMachine(
    private val platform: Platform,
    private val samplesDir: File,
    private val onMessage: (String) -> Unit,
    private val onChanged: () -> Unit
) {
    private val _state = MutableStateFlow(DrumsState())
    val state: StateFlow<DrumsState> = _state

    /** Undo/redo izmjena patterna (koraci, jačine, swing, dužina, mute); zvukovi i jačina trake ne ulaze. */
    private val history = UndoHistory<DrumsState>()
    val undoAvailable: StateFlow<Pair<Boolean, Boolean>> = history.available

    /** Postavlja kit i pattern (učitavanje sesije, "Edit in Drums", novi start). Pozadinska nit. */
    fun setState(s: DrumsState) {
        NativeAudio.studioStop(NativeAudio.PART_DRUMS)
        val sounds = s.sounds.toMutableList()
        for (lane in 0 until DrumLayout.LANES) {
            val snd = sounds[lane]
            if (snd == null || !loadIntoEngine(lane, File(samplesDir, snd.fileName))) {
                NativeAudio.studioClearDrum(lane)
                sounds[lane] = null
            } else {
                NativeAudio.studioSetDrumGain(lane, Fader.gain(snd.gain))
            }
        }
        _state.value = s.copy(sounds = sounds, viewBar = 0, lpPage = 0)
        history.clear()
        sendPattern()
    }

    /**
     * Stari format (faza S2): drums.json + drums/ u folderu aplikacije. Fajlovi se prebace u samplesDir, stari se
     * obrišu. Vraća stanje ili null ako starog formata nema.
     */
    fun migrateLegacy(baseDir: File): DrumsState? {
        val json = File(baseDir, "drums.json")
        val dir = File(baseDir, "drums")
        if (!json.isFile) return null
        val s = try { DrumsJson.fromJson(JSONObject(json.readText())) } catch (e: Exception) { null }
        if (s != null) {
            for (snd in s.sounds) {
                if (snd == null) continue
                val src = File(dir, snd.fileName)
                if (src.isFile) src.copyTo(File(samplesDir, snd.fileName), overwrite = true)
            }
        }
        json.delete()
        dir.deleteRecursively()
        return s
    }

    // ---------------------------------------------------------------- sviranje

    fun hit(lane: Int) {
        if (lane !in 0 until DrumLayout.LANES) return
        NativeAudio.studioNoteOn(NativeAudio.PART_DRUMS, lane, 1f)
    }

    /** Pad zvuka (aplikacija ili Launchpad): svira i bira zvuk. */
    fun hitAndSelect(lane: Int) {
        hit(lane)
        select(lane)
    }

    fun select(lane: Int) {
        if (lane !in 0 until DrumLayout.LANES) return
        change(save = false) { it.copy(selected = lane) }
    }

    fun togglePlay() {
        val p = NativeAudio.PART_DRUMS
        if (NativeAudio.studioTransport(p) == NativeAudio.STUDIO_STOPPED) NativeAudio.studioPlay(p) else NativeAudio.studioStop(p)
    }

    fun stop() = NativeAudio.studioStop(NativeAudio.PART_DRUMS)

    /** Ima li pattern bar jedan korak sa zvukom (inače nema šta da se printa). */
    fun hasContent(): Boolean {
        val s = _state.value
        return (0 until DrumLayout.LANES).any { lane ->
            s.sounds[lane] != null && !s.mutes[lane] && s.steps[lane].any { it < s.totalSteps }
        }
    }

    // ---------------------------------------------------------------- pattern

    fun toggleStep(lane: Int, step: Int) {
        if (lane !in 0 until DrumLayout.LANES || step !in 0 until DrumLayout.MAX_STEPS) return
        change(pattern = true, undo = UndoHistory.EDIT) { s ->
            if (step >= s.totalSteps) return@change s
            val cur = s.steps[lane]
            s.copy(
                steps = s.steps.replace(lane, if (step in cur) cur - step else cur + step),
                velocities = s.velocities.replace(lane, s.velocities[lane] - step)   // novi korak: puna jačina
            )
        }
    }

    /** Jačina upaljenog koraka (traka jačine ispod mreže). */
    fun setVelocity(lane: Int, step: Int, v: Float) {
        if (lane !in 0 until DrumLayout.LANES) return
        val value = v.coerceIn(DrumLayout.MIN_VELOCITY, 1f)
        change(pattern = true, undo = "vel") { s ->
            if (step !in s.steps[lane] || s.velocity(lane, step) == value) return@change s
            val m = if (value >= 1f) s.velocities[lane] - step else s.velocities[lane] + (step to value)
            s.copy(velocities = s.velocities.replace(lane, m))
        }
    }

    fun setSwing(swing: Int) = change(pattern = true, undo = "swing") { s ->
        val v = swing.coerceIn(0, DrumLayout.MAX_SWING)
        if (v == s.swing) s else s.copy(swing = v)
    }

    fun undo() = restore { history.undo(it) }
    fun redo() = restore { history.redo(it) }

    /** Vraća pattern iz istorije; zvukovi, izabrana traka i prikaz ostaju kakvi jesu. */
    private fun restore(pick: (DrumsState) -> DrumsState?) {
        val cur = _state.value
        val h = pick(cur) ?: return
        change(pattern = true) { s ->
            s.copy(
                steps = h.steps, velocities = h.velocities, mutes = h.mutes, swing = h.swing, bars = h.bars,
                viewBar = s.viewBar.coerceAtMost(h.bars - 1), lpPage = s.lpPage.coerceAtMost((h.bars + 1) / 2 - 1)
            )
        }
    }

    /** Korak izabranog zvuka na Launchpadu: indeks 0..31 na trenutnoj strani. */
    fun toggleLpStep(index: Int) {
        val s = _state.value
        toggleStep(s.selected, s.lpPage * DrumLayout.LP_STEPS_PER_PAGE + index)
    }

    /**
     * IMPORT MIDI: udarci dijela zamijene pattern (jedan korak za undo; zvukovi, mute i jačine traka ostaju).
     * Note se rasporede po General MIDI vrsti (DrumLayout.gmKind) na trake (DrumLayout.lanesForKinds). Poravnanje i
     * dužina kao u Keys (MidiFile.toGrid). Vraća (udaraca, odsječeno, vrste koje su dobile traku bez zvuka).
     */
    fun importMidi(part: MidiFile.Part, ppq: Int): Triple<Int, Int, List<String>> {
        val grid = MidiFile.toGrid(part, ppq, DrumLayout.BAR_OPTIONS, DrumLayout.STEPS_PER_BAR)
        val s0 = _state.value
        val lanes = DrumLayout.lanesForKinds(s0.sounds.map { it?.let { d -> d.label + " " + d.name } })
        val steps = List(DrumLayout.LANES) { HashSet<Int>() }
        val vels = List(DrumLayout.LANES) { HashMap<Int, Float>() }
        val kindsUsed = HashSet<Int>()
        for (n in grid.notes) {
            val kind = DrumLayout.gmKind(n.pitch)
            val lane = lanes[kind]
            kindsUsed += kind
            steps[lane] += n.step
            val v = n.velocity.coerceIn(DrumLayout.MIN_VELOCITY, 1f)
            vels[lane][n.step] = maxOf(v, vels[lane][n.step] ?: 0f)   // dvije note na istom mjestu: jača
        }
        if (steps.all { it.isEmpty() }) return Triple(0, grid.cut, emptyList())
        change(pattern = true, undo = UndoHistory.EDIT) { s ->
            s.copy(
                steps = steps, velocities = vels.map { m -> m.filterValues { it < 1f } },
                bars = grid.bars, viewBar = 0, lpPage = 0
            )
        }
        val silent = kindsUsed.sorted().filter { s0.sounds[lanes[it]] == null }
            .map { "${DrumLayout.GM_KINDS[it]} → ${lanes[it] + 1}" }
        return Triple(steps.sumOf { it.size }, grid.cut, silent)
    }

    /** Dužina patterna. Produžavanje kao u Abletonu: ako je novi dio prazan, postojeći taktovi se ponove. */
    fun setBars(bars: Int) {
        if (bars !in DrumLayout.BAR_OPTIONS) return
        change(pattern = true, undo = UndoHistory.EDIT) { s ->
            if (bars == s.bars) return@change s
            var steps = s.steps
            var velocities = s.velocities
            if (bars > s.bars) {
                val oldLen = s.totalSteps
                val newLen = bars * DrumLayout.STEPS_PER_BAR
                val emptyTail = steps.all { lane -> lane.none { it in oldLen until newLen } }
                if (emptyTail) {
                    steps = steps.map { lane ->
                        val base = lane.filter { it < oldLen }
                        val out = HashSet<Int>(lane)
                        var off = oldLen
                        while (off < newLen) {
                            for (st in base) if (st + off < newLen) out += st + off
                            off += oldLen
                        }
                        out
                    }
                    velocities = velocities.map { lane ->
                        val base = lane.filterKeys { it < oldLen }
                        val out = HashMap(lane)
                        var off = oldLen
                        while (off < newLen) {
                            for ((st, v) in base) if (st + off < newLen) out[st + off] = v
                            off += oldLen
                        }
                        out
                    }
                }
            }
            s.copy(
                bars = bars, steps = steps, velocities = velocities,
                viewBar = s.viewBar.coerceAtMost(bars - 1),
                lpPage = s.lpPage.coerceAtMost((bars + 1) / 2 - 1)
            )
        }
    }

    fun setViewBar(bar: Int) = change(save = false) { s -> s.copy(viewBar = bar.coerceIn(0, s.bars - 1)) }

    fun setLpPage(page: Int) = change(save = false) { s ->
        if (page !in 0 until s.lpPages) s else s.copy(lpPage = page)
    }

    fun toggleMute(lane: Int) {
        if (lane !in 0 until DrumLayout.LANES) return
        change(pattern = true, undo = UndoHistory.EDIT) { s -> s.copy(mutes = s.mutes.replace(lane, !s.mutes[lane])) }
    }

    /** Briše korake zvuka (u cijelom patternu). */
    fun clearLane(lane: Int) {
        if (lane !in 0 until DrumLayout.LANES) return
        change(pattern = true, undo = UndoHistory.EDIT) { s ->
            if (s.steps[lane].isEmpty()) s
            else s.copy(steps = s.steps.replace(lane, emptySet()), velocities = s.velocities.replace(lane, emptyMap()))
        }
    }

    fun clearAll() = change(pattern = true, undo = UndoHistory.EDIT) { s ->
        if (s.steps.all { it.isEmpty() }) s
        else s.copy(steps = List(DrumLayout.LANES) { emptySet() }, velocities = List(DrumLayout.LANES) { emptyMap() })
    }

    fun setGain(lane: Int, v: Float) {
        if (lane !in 0 until DrumLayout.LANES) return
        val value = v.coerceIn(0f, 1f)
        change { s ->
            val snd = s.sounds[lane] ?: return@change s
            s.copy(sounds = s.sounds.replace(lane, snd.copy(gain = value)))
        }
        NativeAudio.studioSetDrumGain(lane, Fader.gain(value))
    }

    // ---------------------------------------------------------------- zvukovi

    /** Učitava fajl (ref dokumenta) kao zvuk trake. Pozadinska nit. */
    fun assign(lane: Int, ref: String, name: String, label: String) {
        if (lane !in 0 until DrumLayout.LANES) return
        val ext = name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        val target = File(samplesDir, "drum${lane}_${System.currentTimeMillis()}.$ext")
        try {
            platform.docs.openInput(ref)?.use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                ?: run { onMessage("Cannot read file: $name"); return }
        } catch (e: Exception) {
            target.delete()
            onMessage("Cannot read file: $name")
            return
        }
        if (!loadIntoEngine(lane, target)) {
            target.delete()
            onMessage("Unsupported format or corrupt file: $name")
            return
        }
        val gain = _state.value.sounds[lane]?.gain ?: Fader.DEFAULT
        NativeAudio.studioSetDrumGain(lane, Fader.gain(gain))
        change { s -> s.copy(sounds = s.sounds.replace(lane, DrumSound(name, target.name, label, gain)), selected = lane) }
        onMessage("Drums ${lane + 1}: $label")
    }

    /** Ugrađeni kit: svih 8 traka dobije sintetizovane zvukove (jačine traka ostaju). Pozadinska nit. */
    fun loadKit(kit: DrumKits.Kit, quiet: Boolean = false) {
        var ok = 0
        kit.sounds.forEachIndexed { lane, spec -> if (setSynth(lane, spec, "${kit.name} ${spec.kind.label}", quiet = true)) ok++ }
        if (!quiet || ok < kit.sounds.size) onMessage(if (ok == kit.sounds.size) "Drum kit: ${kit.name}" else "Drum kit ${kit.name}: $ok of ${kit.sounds.size} sounds loaded")
    }

    /**
     * Ugrađeni zvuk na traku: izračuna se u wav (folder semplova, kao i fajlovi korisnika, pa ga nose sesija,
     * izvoz i Print to pad) i učita u motor. Pozadinska nit. Vraća true ako je uspjelo.
     */
    fun setSynth(lane: Int, spec: DrumSynthSpec, name: String, quiet: Boolean = false): Boolean {
        if (lane !in 0 until DrumLayout.LANES) return false
        val target = File(samplesDir, "drum${lane}_${System.currentTimeMillis()}_${spec.kind.name.lowercase()}.wav")
        try {
            WavWriter.write24(target, DrumSynth.renderStereo(spec), DrumSynth.SAMPLE_RATE)
        } catch (e: Exception) {
            target.delete()
            if (!quiet) onMessage("Cannot create sound: ${e.message}")
            return false
        }
        if (!loadIntoEngine(lane, target)) {
            target.delete()
            if (!quiet) onMessage("Cannot load sound: $name")
            return false
        }
        val gain = _state.value.sounds[lane]?.gain ?: Fader.DEFAULT
        NativeAudio.studioSetDrumGain(lane, Fader.gain(gain))
        change { s -> s.copy(sounds = s.sounds.replace(lane, DrumSound(name, target.name, spec.kind.label, gain, spec))) }
        if (!quiet) onMessage("Drums ${lane + 1}: $name")
        return true
    }

    /** Potenciometri ugrađenog zvuka dok se vuče: zvuk samo u motor (bez fajla i bez promjene stanja). */
    fun previewSynth(lane: Int, spec: DrumSynthSpec) {
        if (lane !in 0 until DrumLayout.LANES) return
        val st = DrumSynth.renderStereo(spec)
        NativeAudio.studioLoadDrum(lane, st, st.size / 2, DrumSynth.SAMPLE_RATE)
    }

    /** Nijedna traka nema zvuk (nova sesija): tada se učita podrazumijevani kit (808). */
    fun isKitEmpty() = _state.value.sounds.all { it == null }

    fun removeSound(lane: Int) {
        if (lane !in 0 until DrumLayout.LANES) return
        if (_state.value.sounds[lane] == null) return
        NativeAudio.studioClearDrum(lane)
        change { s -> s.copy(sounds = s.sounds.replace(lane, null)) }
    }

    private fun loadIntoEngine(lane: Int, f: File): Boolean {
        if (!f.isFile) return false
        try {
            if (NativeAudio.studioLoadDrumFromBytes(lane, f.readBytes()) > 0) return true
        } catch (e: Exception) {
            return false
        } catch (e: OutOfMemoryError) {
            return false
        }
        val d = platform.decodeAudio(f.absolutePath) ?: return false   // mp3 i ostalo
        return NativeAudio.studioLoadDrum(lane, d.data, d.frames, d.sampleRate)
    }

    // ---------------------------------------------------------------- interno

    private inline fun change(
        save: Boolean = true,
        pattern: Boolean = false,
        undo: String? = null,
        crossinline f: (DrumsState) -> DrumsState
    ) {
        val before = _state.value
        val after = _state.updateAndGet { f(it) }
        if (after === before) return
        if (undo != null) history.record(before, undo)
        if (pattern) sendPattern()
        if (save) onChanged()
    }

    /** Pattern (bez utišanih zvukova) u motor, sa swingom i jačinama. */
    private fun sendPattern() {
        val s = _state.value
        val ticks = ArrayList<Int>()
        val vel = ArrayList<Float>()
        val tps = NativeAudio.TICKS_PER_STEP
        for (lane in 0 until DrumLayout.LANES) {
            if (s.mutes[lane]) continue
            for (st in s.steps[lane].sorted()) {
                if (st >= s.totalSteps) continue
                ticks += DrumLayout.swingTick(st, s.swing, tps)
                ticks += tps / 2
                ticks += lane
                vel += s.velocity(lane, st)
            }
        }
        NativeAudio.studioSetPattern(NativeAudio.PART_DRUMS, s.bars, ticks.toIntArray(), vel.toFloatArray())
    }
}

private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
