package com.example.padsampler.studio

import com.example.padsampler.audio.NativeAudio
import com.example.padsampler.data.MidiFile
import com.example.padsampler.model.DrumLayout
import com.example.padsampler.model.KeyNote
import com.example.padsampler.model.KeysLayout
import com.example.padsampler.model.KeysState
import com.example.padsampler.model.Scale
import com.example.padsampler.model.SynthParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Keys (Studio): Minimoog-sintisajzer (C++ synth.*) i piano roll. Stanje je u [state] (dio sesije, pads.json
 * polje "keys"); svaka promjena zvuka ide odmah u motor (synthSetParam), promjena nota u studioSetPattern.
 * Sviranje uživo (klavijatura u aplikaciji, Launchpad) ide kroz [noteOn]/[noteOff]; note koje se drže su u [held].
 * Zove se iz UI-a i iz MIDI niti.
 */
class KeysMachine(private val onChanged: () -> Unit) {
    private val _state = MutableStateFlow(KeysState())
    val state: StateFlow<KeysState> = _state

    private val _held = MutableStateFlow<Set<Int>>(emptySet())
    val held: StateFlow<Set<Int>> = _held

    /** Launchpad pad (nota pada) -> nota koja je pokrenuta, da otpuštanje ugasi pravu notu i poslije promjene oktave. */
    private val lpHeld = HashMap<Int, Int>()

    /** Undo/redo izmjena nota (note, jačine, swing, dužina); zvuk (potenciometri) ne ulazi. */
    private val history = UndoHistory<KeysState>()
    val undoAvailable: StateFlow<Pair<Boolean, Boolean>> = history.available

    /** Postavlja zvuk i pattern (učitavanje sesije, "Edit in Keys", nova sesija). */
    fun setState(s: KeysState) {
        stopRec()
        NativeAudio.studioStop(NativeAudio.PART_KEYS)
        allNotesOff()
        val cur = _state.value
        _state.value = s.copy(
            viewBar = 0, lpPage = 0, lpStep = cur.lpStep, selectedNote = cur.selectedNote, panelOpen = cur.panelOpen,
            recQuantize = cur.recQuantize
        )
        history.clear()
        for (d in SynthParams.all) NativeAudio.synthSetParam(d.id, s.params.getOrElse(d.id) { d.def })
        sendPattern()
    }

    // ---------------------------------------------------------------- zvuk

    fun setParam(id: Int, value: Float) {
        val d = SynthParams.all.getOrNull(id) ?: return
        val v = value.coerceIn(d.min, d.max)
        NativeAudio.synthSetParam(id, v)
        change { s -> if (s.params[id] == v) s else s.copy(params = s.params.toMutableList().also { it[id] = v }) }
    }

    fun loadPreset(p: SynthPreset) {
        // preset je zvuk: arpeggiator i efekti ostaju kakvi jesu
        val cur = _state.value.params
        val params = SynthParams.all.map { d ->
            if (d.id in SynthParams.KEEP_ON_PRESET) cur.getOrElse(d.id) { d.def } else p.params.getOrElse(d.id) { d.def }
        }
        for (d in SynthParams.all) NativeAudio.synthSetParam(d.id, params[d.id])
        change { s -> s.copy(params = params, presetName = p.name) }
    }

    fun setPresetName(name: String) = change { s -> s.copy(presetName = name) }

    // ---------------------------------------------------------------- sviranje uživo

    fun noteOn(pitch: Int, velocity: Float = 1f) {
        if (pitch !in 0..127) return
        val v = velocity.coerceIn(0.01f, 1f)
        NativeAudio.studioNoteOn(NativeAudio.PART_KEYS, pitch, v)
        _held.update { it + pitch }
        if (_state.value.rec == KeysState.REC_ON) {
            val t = audibleTick()
            if (t >= 0.0) synchronized(recOpen) { recOpen[pitch] = OpenNote(t, v) }
        }
    }

    fun noteOff(pitch: Int) {
        if (pitch !in 0..127) return
        NativeAudio.studioNoteOff(NativeAudio.PART_KEYS, pitch)
        _held.update { it - pitch }
        val open = synchronized(recOpen) { recOpen.remove(pitch) } ?: return
        val end = audibleTick()
        commitRecorded(pitch, open, if (end >= 0.0) end else open.tick + NativeAudio.TICKS_PER_STEP)
    }

    // ---------------------------------------------------------------- snimanje uživo (REC)

    private class OpenNote(val tick: Double, val velocity: Float)

    /** Note koje se drže dok se snima: visina -> početak (tick u patternu) i jačina. */
    private val recOpen = HashMap<Int, OpenNote>()
    private var takeRecorded = false        // prva snimljena nota prolaza pravi jedan korak za undo
    private var countInLastPhase = -1f
    private var countInWrapped = false

    /** Pokreće Keys transport za snimanje (ViewModel: uz Ableton Link). */
    var startTransport: () -> Unit = { NativeAudio.studioPlay(NativeAudio.PART_KEYS) }

    /**
     * REC: ako Keys ne svira, 1 takt odbrojavanja (metronom), pa pattern krene i snima; ako svira, snima odmah.
     * Ponovni pritisak završava snimanje (pattern svira dalje). Snima se preko postojećih nota (dosnimavanje).
     */
    fun toggleRec() {
        if (_state.value.rec != KeysState.REC_OFF) {
            stopRec()
            return
        }
        takeRecorded = false
        countInLastPhase = -1f
        countInWrapped = false
        NativeAudio.studioSetClick(true)
        val playing = NativeAudio.studioTransport(NativeAudio.PART_KEYS) == NativeAudio.STUDIO_PLAYING
        change(save = false) { it.copy(rec = if (playing) KeysState.REC_ON else KeysState.REC_COUNT_IN) }
    }

    /** Završava snimanje: note koje se još drže se upišu do ovog trenutka. */
    fun stopRec() {
        if (_state.value.rec == KeysState.REC_OFF) return
        val end = audibleTick()
        val open = synchronized(recOpen) { HashMap(recOpen).also { recOpen.clear() } }
        for ((pitch, o) in open) commitRecorded(pitch, o, if (end >= 0.0) end else o.tick + NativeAudio.TICKS_PER_STEP)
        NativeAudio.studioSetClick(false)
        change(save = false) { it.copy(rec = KeysState.REC_OFF) }
    }

    fun setRecQuantize(steps: Int) {
        if (steps == 1 || steps == 2) change(save = false) { if (it.recQuantize == steps) it else it.copy(recQuantize = steps) }
    }

    /**
     * Zove se iz petlje stanja (~30 puta u sekundi). Odbrojavanje: na prvom početku takta poslije REC pokrene
     * transport (kreće takt kasnije, tj. poslije punog takta odbrojavanja); kad svira, snimanje počinje.
     * Ako se Keys zaustavi dok se snima, snimanje se završi.
     */
    fun tick(barPhase: Float, transport: Int) {
        when (_state.value.rec) {
            KeysState.REC_COUNT_IN -> {
                if (transport == NativeAudio.STUDIO_PLAYING) {
                    change(save = false) { it.copy(rec = KeysState.REC_ON) }
                } else if (!countInWrapped) {
                    if (countInLastPhase >= 0f && barPhase < countInLastPhase) {
                        countInWrapped = true
                        if (transport == NativeAudio.STUDIO_STOPPED) startTransport()
                    }
                    countInLastPhase = barPhase
                }
            }
            KeysState.REC_ON -> if (transport == NativeAudio.STUDIO_STOPPED) stopRec()
        }
    }

    /**
     * Pozicija u patternu koju korisnik upravo ČUJE (tickovi): motor javlja kraj zadnjeg renderovanog bloka, a
     * zvuk izlazi tek poslije bafera izlaza; -1 ako ne svira.
     */
    private fun audibleTick(): Double {
        val pos = NativeAudio.studioPosition(NativeAudio.PART_KEYS)
        if (pos < 0f) return -1.0
        val len = _state.value.totalSteps * NativeAudio.TICKS_PER_STEP.toDouble()
        val rate = NativeAudio.sampleRate().coerceAtLeast(1)
        val bpm = NativeAudio.engineBpm().coerceIn(20.0, 400.0)
        val framesPerBar = rate * 240.0 / bpm
        val latencyTicks = NativeAudio.bufferSize().coerceAtLeast(0) * (DrumLayout.STEPS_PER_BAR * NativeAudio.TICKS_PER_STEP) / framesPerBar
        var t = pos - latencyTicks
        while (t < 0) t += len
        return t % len
    }

    /** Upisuje odsviranu notu u pattern: početak poravnat na 1/16 ili 1/8, dužina u šesnaestinama (najmanje 1). */
    private fun commitRecorded(pitch: Int, o: OpenNote, endTick: Double) {
        val s0 = _state.value
        val tps = NativeAudio.TICKS_PER_STEP
        val total = s0.totalSteps
        val len = total * tps.toDouble()
        val q = s0.recQuantize
        var dur = endTick - o.tick
        if (dur < 0) dur += len
        var step = (Math.round(o.tick / (tps * q)) * q).toInt()
        if (step >= total) step -= total
        val lenSteps = Math.round(dur / tps).toInt().coerceIn(1, total)
        if (!takeRecorded) {
            takeRecorded = true
            history.record(s0, UndoHistory.EDIT)    // cijeli prolaz snimanja = jedan undo
        }
        change(pattern = true) { s ->
            // ista visina: stara nota na istom koraku se zamijeni, nota koja prekriva početak se skrati
            val others = s.notes.filterNot { it.pitch == pitch && it.step == step }.map { n ->
                if (n.pitch == pitch && n.step < step && n.step + n.length > step) n.copy(length = step - n.step) else n
            }
            val next = others.filter { it.pitch == pitch && it.step > step }.minOfOrNull { it.step } ?: total
            val l = minOf(lenSteps, next - step, total - step).coerceAtLeast(1)
            s.copy(notes = others + KeyNote(step, l, pitch, o.velocity.coerceIn(DrumLayout.MIN_VELOCITY, 1f)))
        }
    }

    /**
     * Launchpad, redovi 1..7. PLAY mod: sve je klavijatura. STEP mod: redovi 7..4 su 32 koraka izabrane note
     * (pritisak dodaje/briše notu dužine NOTE iz aplikacije), redovi 3..1 klavijatura (svira i bira notu).
     */
    fun lpPad(row: Int, col: Int, down: Boolean) {
        val s = _state.value
        if (s.lpStep && row > KeysLayout.STEP_KEY_ROWS) {
            if (down) toggleNote(s.lpPage * DrumLayout.LP_STEPS_PER_PAGE + (7 - row) * 8 + col, s.stepNote)
            return
        }
        val key = row * 10 + col
        if (down) {
            val n = KeysLayout.lpNote(s, row, col) ?: return
            synchronized(lpHeld) { lpHeld[key] = n }
            noteOn(n)
            if (s.lpStep) selectNote(n)
        } else {
            val n = synchronized(lpHeld) { lpHeld.remove(key) } ?: return
            noteOff(n)
        }
    }

    /** Nota čiji se koraci programiraju (Launchpad STEP mod; bira se i klavijaturom u aplikaciji). */
    fun selectNote(n: Int) {
        if (n in 0..127) change(save = false) { if (it.selectedNote == n) it else it.copy(selectedNote = n) }
    }

    /** Panel potenciometara: prikaži / sakrij (aplikacija i strelica dolje na Launchpadu). */
    fun setPanelOpen(open: Boolean) = change(save = false) { if (it.panelOpen == open) it else it.copy(panelOpen = open) }
    fun togglePanel() = change(save = false) { it.copy(panelOpen = !it.panelOpen) }

    fun toggleLpMode() {
        allNotesOff()
        change(save = false) { it.copy(lpStep = !it.lpStep) }
    }

    fun setLpPage(page: Int) = change(save = false) { s -> if (page !in 0 until s.lpPages) s else s.copy(lpPage = page) }

    fun allNotesOff() {
        for (n in _held.value) NativeAudio.studioNoteOff(NativeAudio.PART_KEYS, n)
        synchronized(lpHeld) { lpHeld.clear() }
        _held.value = emptySet()
    }

    /** Play/Stop Keys patterna (samo Keys; Drums ima svoj). */
    fun togglePlay() {
        val p = NativeAudio.PART_KEYS
        if (NativeAudio.studioTransport(p) == NativeAudio.STUDIO_STOPPED) NativeAudio.studioPlay(p) else NativeAudio.studioStop(p)
    }

    fun stop() = NativeAudio.studioStop(NativeAudio.PART_KEYS)

    fun hasContent(): Boolean {
        val s = _state.value
        return s.notes.any { it.step < s.totalSteps }
    }

    // ---------------------------------------------------------------- piano roll

    /** Klik na ćeliju: briše notu koja pokriva (step, pitch) ili dodaje novu izabrane dužine. */
    fun toggleNote(step: Int, pitch: Int) {
        if (step !in 0 until DrumLayout.MAX_STEPS || pitch !in 0..127) return
        change(pattern = true, undo = UndoHistory.EDIT) { s ->
            if (step >= s.totalSteps) return@change s
            val hit = s.notes.firstOrNull { it.pitch == pitch && step >= it.step && step < it.step + it.length }
            if (hit != null) return@change s.copy(notes = s.notes - hit)
            // ne preklapa sljedeću notu iste visine
            val next = s.notes.filter { it.pitch == pitch && it.step > step }.minOfOrNull { it.step } ?: s.totalSteps
            val len = minOf(s.noteLength, next - step, s.totalSteps - step).coerceAtLeast(1)
            s.copy(notes = s.notes + KeyNote(step, len, pitch))
        }
        if (_held.value.isEmpty()) previewNote(pitch)
    }

    /** Kratko odsvira notu (klik u piano rollu) dok pattern ne svira. */
    private fun previewNote(pitch: Int) {
        if (NativeAudio.studioTransport(NativeAudio.PART_KEYS) != NativeAudio.STUDIO_STOPPED) return
        NativeAudio.studioNoteOn(NativeAudio.PART_KEYS, pitch, 1f)
        Thread {
            try { Thread.sleep(180) } catch (_: InterruptedException) {}
            if (pitch !in _held.value) NativeAudio.studioNoteOff(NativeAudio.PART_KEYS, pitch)
        }.apply { isDaemon = true }.start()
    }

    /**
     * IMPORT MIDI: note dijela zamijene pattern (jedan korak za undo). Poravnaju se na šesnaestine, prazni taktovi na
     * početku se preskoče, dužina = 1/2/4/8 taktova (duže se odsiječe). Ako nota nije u skali, skala postaje
     * Chromatic; oktava se izabere da se vidi što više nota. Vraća (uvezeno nota, odsječeno nota).
     */
    fun importMidi(part: MidiFile.Part, ppq: Int): Pair<Int, Int> {
        stopRec()
        val grid = MidiFile.toGrid(part, ppq, DrumLayout.BAR_OPTIONS, DrumLayout.STEPS_PER_BAR)
        if (grid.notes.isEmpty()) return 0 to grid.cut
        val bars = grid.bars
        val seen = HashSet<Long>()
        val notes = grid.notes.mapNotNull { n ->
            if (!seen.add(n.pitch.toLong() * 1000 + n.step)) null
            else KeyNote(n.step, n.length, n.pitch, n.velocity.coerceIn(DrumLayout.MIN_VELOCITY, 1f))
        }
        change(pattern = true, undo = UndoHistory.EDIT) { s ->
            val scale = if (notes.all { s.scale.contains(it.pitch, s.root) }) s.scale else Scale.CHROMATIC
            // oktava pod kojom se vidi najviše nota (piano roll = dvije oktave od tonike)
            val probe = s.copy(scale = scale)
            val octave = (KeysLayout.MIN_OCTAVE..KeysLayout.MAX_OCTAVE).maxByOrNull { o ->
                val base = KeysLayout.baseNote(probe.copy(octave = o))
                notes.count { it.pitch in base..base + 24 } * 100 - Math.abs(o - s.octave)
            } ?: s.octave
            s.copy(
                notes = notes, bars = bars, scale = scale, octave = octave, viewBar = 0,
                lpPage = 0
            )
        }
        return notes.size to grid.cut
    }

    /** Pomjera izabrane note (SELECT u piano rollu); vraća nove note kao novu selekciju. */
    fun moveNotes(sel: Set<KeyNote>, dStep: Int, dRow: Int, rows: List<Int>, undoKey: String = UndoHistory.EDIT): Set<KeyNote> {
        var out = sel
        change(pattern = true, undo = undoKey) { s ->
            val (notes, moved) = KeysLayout.moveNotes(s.notes, sel, dStep, dRow, rows, s.totalSteps)
            out = moved
            if (notes == s.notes) s else s.copy(notes = notes)
        }
        return out
    }

    fun deleteNotes(sel: Set<KeyNote>) = change(pattern = true, undo = UndoHistory.EDIT) { s ->
        if (s.notes.none { it in sel }) s else s.copy(notes = s.notes.filter { it !in sel })
    }

    fun clearAll() = change(pattern = true, undo = UndoHistory.EDIT) { s ->
        if (s.notes.isEmpty()) s else s.copy(notes = emptyList())
    }

    /** Jačina svih nota koje počinju na koraku (traka jačine ispod piano rolla). */
    fun setVelocity(step: Int, v: Float) {
        val value = v.coerceIn(DrumLayout.MIN_VELOCITY, 1f)
        change(pattern = true, undo = "vel") { s ->
            if (s.notes.none { it.step == step && it.velocity != value }) return@change s
            s.copy(notes = s.notes.map { if (it.step == step) it.copy(velocity = value) else it })
        }
    }

    fun setSwing(swing: Int) = change(pattern = true, undo = "swing") { s ->
        val v = swing.coerceIn(0, DrumLayout.MAX_SWING)
        if (v == s.swing) s else s.copy(swing = v)
    }

    fun undo() = restore { history.undo(it) }
    fun redo() = restore { history.redo(it) }

    /** Vraća note iz istorije; zvuk, skala i prikaz ostaju kakvi jesu. */
    private fun restore(pick: (KeysState) -> KeysState?) {
        val h = pick(_state.value) ?: return
        change(pattern = true) { s ->
            s.copy(
                notes = h.notes, swing = h.swing, bars = h.bars,
                viewBar = s.viewBar.coerceAtMost(h.bars - 1), lpPage = s.lpPage.coerceAtMost((h.bars + 1) / 2 - 1)
            )
        }
    }

    fun setNoteLength(len: Int) {
        if (len in KeysLayout.NOTE_LENGTHS) change(save = false) { it.copy(noteLength = len) }
    }

    fun setRoot(root: Int) = change { it.copy(root = root.coerceIn(0, 11)) }
    fun setScale(scale: Scale) = change { it.copy(scale = scale) }
    fun shiftOctave(delta: Int) = change { s ->
        s.copy(octave = (s.octave + delta).coerceIn(KeysLayout.MIN_OCTAVE, KeysLayout.MAX_OCTAVE))
    }
    fun setCategory(c: String) = change { it.copy(category = c) }

    /** Dužina patterna; produžavanje kao u Drums: ako je novi dio prazan, postojeći taktovi se ponove. */
    fun setBars(bars: Int) {
        if (bars !in DrumLayout.BAR_OPTIONS) return
        change(pattern = true, undo = UndoHistory.EDIT) { s ->
            if (bars == s.bars) return@change s
            val oldLen = s.totalSteps
            val newLen = bars * DrumLayout.STEPS_PER_BAR
            var notes = s.notes
            if (bars > s.bars && notes.none { it.step >= oldLen }) {
                val base = notes.filter { it.step < oldLen }
                val out = ArrayList(notes)
                var off = oldLen
                while (off < newLen) {
                    for (n in base) if (n.step + off < newLen) out += n.copy(step = n.step + off)
                    off += oldLen
                }
                notes = out
            }
            s.copy(
                bars = bars, notes = notes, viewBar = s.viewBar.coerceAtMost(bars - 1),
                lpPage = s.lpPage.coerceAtMost((bars + 1) / 2 - 1)
            )
        }
    }

    fun setViewBar(bar: Int) = change(save = false) { s -> s.copy(viewBar = bar.coerceIn(0, s.bars - 1)) }

    // ---------------------------------------------------------------- interno

    private inline fun change(
        save: Boolean = true,
        pattern: Boolean = false,
        undo: String? = null,
        crossinline f: (KeysState) -> KeysState
    ) {
        val before = _state.value
        val after = _state.updateAndGet { f(it) }
        if (after === before) return
        if (undo != null) history.record(before, undo)
        if (pattern) sendPattern()
        if (save) onChanged()
    }

    /** Note u motor: swing pomjera početak (i kraj) neparnih šesnaestina; jačina po noti. */
    private fun sendPattern() {
        val s = _state.value
        val ticks = ArrayList<Int>()
        val vel = ArrayList<Float>()
        val tps = NativeAudio.TICKS_PER_STEP
        for (n in s.notes.sortedWith(compareBy({ it.step }, { it.pitch }))) {
            if (n.step >= s.totalSteps) continue
            val end = minOf(n.step + n.length, s.totalSteps)
            val start = DrumLayout.swingTick(n.step, s.swing, tps)
            ticks += start
            ticks += (DrumLayout.swingTick(end, s.swing, tps) - start).coerceAtLeast(1)
            ticks += n.pitch
            vel += n.velocity
        }
        NativeAudio.studioSetPattern(NativeAudio.PART_KEYS, s.bars, ticks.toIntArray(), vel.toFloatArray())
    }
}
