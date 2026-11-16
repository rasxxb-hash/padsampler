package com.example.padsampler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.padsampler.audio.NativeAudio
import com.example.padsampler.audio.SampleLoader
import com.example.padsampler.data.BpmParser
import com.example.padsampler.data.KeyParser
import com.example.padsampler.data.LibEntry
import com.example.padsampler.data.Library
import com.example.padsampler.data.SessionInfo
import com.example.padsampler.data.SessionStore
import com.example.padsampler.data.SetList
import com.example.padsampler.data.SetListStore
import com.example.padsampler.data.PadStore
import com.example.padsampler.midi.LaunchpadLeds
import com.example.padsampler.midi.LedPicture
import com.example.padsampler.midi.MidiListener
import com.example.padsampler.midi.LaunchpadSurface
import com.example.padsampler.model.BottomMode
import com.example.padsampler.model.ControlState
import com.example.padsampler.model.Fader
import com.example.padsampler.model.Layout
import com.example.padsampler.model.LiveState
import com.example.padsampler.model.PadConfig
import com.example.padsampler.model.Screen
import com.example.padsampler.model.SessionData
import com.example.padsampler.platform.DocChild
import com.example.padsampler.platform.Platform
import com.example.padsampler.studio.DrumMachine
import com.example.padsampler.studio.KeysMachine
import com.example.padsampler.midi.KeyboardListener
import com.example.padsampler.midi.MidiKeyboard
import com.example.padsampler.data.MidiMapStore
import com.example.padsampler.data.MidiFile
import com.example.padsampler.studio.DrumKits
import com.example.padsampler.model.DrumSynthSpec
import kotlinx.coroutines.channels.Channel
import com.example.padsampler.model.SynthParams
import com.example.padsampler.studio.SynthPreset
import com.example.padsampler.studio.SynthPresets
import com.example.padsampler.model.KeysState
import com.example.padsampler.model.KeysLayout
import com.example.padsampler.model.safeFolder
import com.example.padsampler.model.DrumLayout
import com.example.padsampler.model.DrumsState
import com.example.padsampler.data.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Sva logika sesije. Slot = scena * 8 + track. Tablet i Launchpad zovu iste funkcije.
 * Launchpad pozivi dolaze iz MIDI niti; stanje je u StateFlow-ovima (sigurno za više niti).
 */
class SamplerViewModel(private val platform: Platform) : ViewModel(), MidiListener {

    private val loader = SampleLoader(platform)
    private val docs = platform.docs

    private val _info = MutableStateFlow("Starting audio...")
    val info: StateFlow<String> = _info

    private val _midiStatus = MutableStateFlow("Launchpad: not connected")
    val midiStatus: StateFlow<String> = _midiStatus

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    /** Kratka obavijest dolje na ekranu (MessageToast), npr. napomena o bateriji pri pokretanju. */
    fun showMessage(text: String) { _message.value = text }

    /** Koliko semplova se još razvlači na novi tempo (0 = gotovo). */
    private val _stretching = MutableStateFlow(0)
    val stretching: StateFlow<Int> = _stretching

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live

    private val _control = MutableStateFlow(ControlState())
    val control: StateFlow<ControlState> = _control

    private val _bpm = MutableStateFlow(120)
    val bpm: StateFlow<Int> = _bpm

    // ---- Ableton Link (samo tablet, nije vezano za Launchpad). Uvijek počinje isključen.
    private val _linkOn = MutableStateFlow(false)
    val linkOn: StateFlow<Boolean> = _linkOn
    private val _linkPeers = MutableStateFlow(-1)
    val linkPeers: StateFlow<Int> = _linkPeers
    private val _linkOffsetMs = MutableStateFlow(platform.getInt("link_offset_ms", 0))
    val linkOffsetMs: StateFlow<Int> = _linkOffsetMs
    private val _linkAudio = MutableStateFlow(platform.getInt("link_audio", 0) == 1)
    val linkAudio: StateFlow<Boolean> = _linkAudio
    private var multicastLock: AutoCloseable? = null
    private var lastEngineBpm = 0.0

    private val _pads = MutableStateFlow<Map<Int, PadConfig>>(emptyMap())
    val pads: StateFlow<Map<Int, PadConfig>> = _pads

    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode

    private val _editSlot = MutableStateFlow<Int?>(null)
    val editSlot: StateFlow<Int?> = _editSlot

    private val store = PadStore(platform.filesDir)
    private val sessionStore = SessionStore(platform.filesDir)
    val library = Library(docs, platform.filesDir)

    private val _sessionName = MutableStateFlow("")
    val sessionName: StateFlow<String> = _sessionName

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions

    private val setListStore = SetListStore(platform.filesDir)
    private val _setList = MutableStateFlow(setListStore.load())
    val setList: StateFlow<SetList> = _setList

    // ---- browser biblioteke / preslušavanje
    private val _browserSlot = MutableStateFlow<Int?>(null)
    val browserSlot: StateFlow<Int?> = _browserSlot
    private val _previewKey = MutableStateFlow<String?>(null)   // docId sempla koji se preslušava (ili učitava)
    val previewKey: StateFlow<String?> = _previewKey
    private val _previewProgress = MutableStateFlow(-1f)
    val previewProgress: StateFlow<Float> = _previewProgress
    private val _previewMatch = MutableStateFlow(false)          // false = original, true = na tempu sesije
    val previewMatch: StateFlow<Boolean> = _previewMatch
    private var previewJob: Job? = null
    @Volatile private var previewGen = 0
    @Volatile private var previewLoading = false
    @Volatile private var previewGraceUntil = 0L
    private val midi = platform.createMidi(this)
    private val surface = LaunchpadSurface(midi)

    /** Drums (Studio): kit + step sequencer, svira kroz master. */
    val drums = DrumMachine(platform, store.samplesDir, onMessage = { _message.value = it }, onChanged = { scheduleSave() })
    /** Pad na koji je zadnji put printan (ili koji je otvoren u Drums): predložen u dijalogu "Print to pad". */
    private val _printTarget = MutableStateFlow<Int?>(null)
    val printTarget: StateFlow<Int?> = _printTarget
    /** Keys (Studio): sintisajzer + piano roll, svira kroz master (isti transport kao Drums). */
    val keys = KeysMachine(onChanged = { scheduleSave() })
    private val presets = SynthPresets(platform.filesDir)
    val builtInPresets: List<SynthPreset> get() = presets.builtIn
    private val _userPresets = MutableStateFlow<List<SynthPreset>>(emptyList())
    val userPresets: StateFlow<List<SynthPreset>> = _userPresets
    private val _drumBrowserLane = MutableStateFlow<Int?>(null)
    // ---- MIDI klavijatura (svira Keys sintisajzer na svim ekranima)
    private val keyboard: MidiKeyboard = platform.createKeyboard(KeyboardHandler())
    private val _keyboardDevices = MutableStateFlow<List<String>>(emptyList())
    /** Spojene MIDI klavijature (imena). */
    val keyboardDevices: StateFlow<List<String>> = _keyboardDevices
    private val _keyboardSelection = MutableStateFlow(platform.getString(KEYBOARD_KEY, ""))
    /** "" = Auto, MidiKeyboard.OFF = isključeno, inače ime uređaja. */
    val keyboardSelection: StateFlow<String> = _keyboardSelection
    // ---- MIDI LEARN (potenciometri Keys): po uređaju CC -> id parametra
    private val midiMapStore = MidiMapStore(platform.filesDir)
    private val _midiMap = MutableStateFlow<Map<String, Map<Int, Int>>>(emptyMap())
    val midiMap: StateFlow<Map<String, Map<Int, Int>>> = _midiMap
    private val _learnMode = MutableStateFlow(false)
    val learnMode: StateFlow<Boolean> = _learnMode
    private val _learnTarget = MutableStateFlow<Int?>(null)
    /** Potenciometar koji čeka CC sa klavijature (MIDI LEARN). */
    val learnTarget: StateFlow<Int?> = _learnTarget
    private val _learnDevice = MutableStateFlow<String?>(null)
    /** Uređaj čije se mapiranje prikazuje (zadnji koji je poslao CC, inače prvi spojeni). */
    val learnDevice: StateFlow<String?> = _learnDevice


    val drumBrowserLane: StateFlow<Int?> = _drumBrowserLane

    @Volatile private var audioReady = false
    private var saveJob: Job? = null
    private var ledRepaintJob: Job? = null

    /**
     * LED slika za Launchpad, ali samo sa CC brojevima koji na Mini MK3 imaju LED (programmer mode: gornji red
     * 91-98 + logo 99, desna kolona 19, 29 ... 89). LaunchpadLeds puni svih 128 CC-ova, pa bi se pri punom
     * slanju (spajanje, invalidate) poslali i CC 120-127 = MIDI "channel mode" poruke (123 All Notes Off,
     * 121 Reset All Controllers, 122 Local Control). Launchpad tada ugasi sve padove upaljene notama, odmah
     * pošto ih je dobio. Ostali CC-ovi ostaju UNKNOWN i LaunchpadSurface ih ne šalje.
     */
    private fun ledPicture(cs: ControlState, live: LiveState): LedPicture {
        val pic = LaunchpadLeds.compose(cs, live, drums.state.value, keys.state.value, keys.held.value)
        for (cc in pic.cc.indices) if (!isLedCc(cc)) pic.cc[cc] = LedPicture.UNKNOWN
        return pic
    }

    private fun isLedCc(cc: Int): Boolean =
        cc in 91..99 || (cc % 10 == 9 && cc in 19..89)

    private val stateBytes = ByteArray(Layout.SLOTS)
    private val stateFloats = FloatArray(18)

    init {
        // Snimanje u Keys poslije odbrojavanja pokreće transport (uz Ableton Link kao Play)
        keys.startTransport = {
            if (_linkOn.value) NativeAudio.linkStart()
            NativeAudio.studioPlay(NativeAudio.PART_KEYS)
        }
        viewModelScope.launch(Dispatchers.Default) {
            if (!NativeAudio.start()) {
                _info.value = "ERROR: audio failed to start"
                return@launch
            }
            val saved = store.load()
            _sessionName.value = saved.name
            library.load()

            _bpm.value = saved.bpm
            _control.value = ControlState(
                faders = saved.faders, pans = saved.pans, mutes = saved.mutes,
                solos = List(Layout.TRACKS) { false }, master = saved.master
            )
            NativeAudio.setBpm(saved.bpm.toDouble())
            midi.setClockBpm(saved.bpm.toDouble())
            applyMixerToEngine()

            var failed = 0
            val loaded = mutableMapOf<Int, PadConfig>()
            if (saved.pads.isNotEmpty()) _message.value = "Loading samples..."
            for (cfg in saved.pads) {
                val f = store.fileFor(cfg)
                val mode = if (cfg.loop) NativeAudio.LOOP_ON else NativeAudio.LOOP_OFF
                if (f.exists() && loader.load(cfg.slot, f, cfg.srcBpm, mode) > 0) {
                    loaded[cfg.slot] = cfg
                } else failed++
            }
            if (saved.pads.isNotEmpty()) _message.value = ""

            _pads.value = loaded
            if (failed > 0) _message.value = "Could not load $failed sample(s)"

            // Drums sesije (stari format iz faze S2 se prenese jednom)
            val legacy = if (saved.drums == null) drums.migrateLegacy(platform.filesDir) else null
            drums.setState(saved.drums ?: legacy ?: DrumsState())
            ensureDrumKit()
            if (legacy != null) scheduleSave()
            keys.setState(saved.keys ?: KeysState())
            _userPresets.value = presets.user()
            _midiMap.value = midiMapStore.load()
            refreshAudioInfo()
            audioReady = true

            midi.start()
            keyboard.select(_keyboardSelection.value)
            keyboard.start()

            var pollCount = 0
            while (isActive) {
                pollCount++
                if (_linkOn.value) followLink(pollCount)
                // Izlaz se može promijeniti sam (računar: drajver resetuje stream, motor ga otvori na drugoj frekvenciji)
                if (pollCount % 80 == 0) refreshAudioInfo()
                NativeAudio.pollState(stateBytes, stateFloats)
                val studioPos = NativeAudio.studioPosition(NativeAudio.PART_DRUMS)
                val keysPos = NativeAudio.studioPosition(NativeAudio.PART_KEYS)
                val snapshot = LiveState(
                    slotStates = stateBytes.copyOf(),
                    levels = stateFloats.copyOfRange(0, 8),
                    progress = stateFloats.copyOfRange(8, 16),
                    barPhase = stateFloats[16],
                    masterLevel = stateFloats[17],
                    drumsTransport = NativeAudio.studioTransport(NativeAudio.PART_DRUMS),
                    keysTransport = NativeAudio.studioTransport(NativeAudio.PART_KEYS),
                    drumStep = if (studioPos >= 0f) (studioPos / NativeAudio.TICKS_PER_STEP).toInt() else -1,
                    keysStep = if (keysPos >= 0f) (keysPos / NativeAudio.TICKS_PER_STEP).toInt() else -1
                )
                _live.value = snapshot
                keys.tick(snapshot.barPhase, snapshot.keysTransport)   // odbrojavanje / kraj snimanja
                _stretching.value = NativeAudio.stretchPending()
                val pp = NativeAudio.previewProgress()
                _previewProgress.value = pp
                if (pp < 0f && !previewLoading && _previewKey.value != null &&
                    System.currentTimeMillis() > previewGraceUntil
                ) {
                    _previewKey.value = null   // preslušavanje je završilo samo od sebe
                }
                if (midi.isConnected) surface.render(ledPicture(_control.value, snapshot))
                delay(25)
            }
        }
    }

    /** Tekst o izlazu (frekvencija, bafer, kašnjenje). Zove se i nakon promjene audio uređaja (računar). */
    fun refreshAudioInfo() {
        val rate = NativeAudio.sampleRate()
        val buffer = NativeAudio.bufferSize()
        val latencyMs = if (rate > 0) buffer * 1000.0 / rate else 0.0
        _info.value = "Audio: $rate Hz, burst ${NativeAudio.framesPerBurst()} / buffer $buffer, " +
            "latency ~ ${"%.1f".format(latencyMs)} ms"
    }

    private fun applyMixerToEngine() {
        val c = _control.value
        for (t in 0 until Layout.TRACKS) {
            NativeAudio.setTrackGain(t, Fader.gain(c.faders[t]))
            NativeAudio.setTrackPan(t, c.pans[t])
            NativeAudio.setTrackMute(t, c.mutes[t])
            NativeAudio.setTrackSolo(t, c.solos[t])
        }
        NativeAudio.setMasterGain(Fader.gain(c.master))
        NativeAudio.setMasterMute(c.masterMute)
    }

    // ---------------------------------------------------------------- sviranje

    private fun hasClip(slot: Int) = _live.value.slotStates[slot].toInt() != NativeAudio.EMPTY

    /** Tap na klip (tablet ili Launchpad). U edit modu (samo tablet) otvara dijalog. */
    fun clipTap(slot: Int) {
        if (slot !in 0 until Layout.SLOTS) return
        if (_editMode.value) {
            _editSlot.value = slot
            return
        }
        if (audioReady && hasClip(slot)) launchSlot(slot)
    }

    /** Klip kreće (ili staje). Sa Linkom: klip koji kreće pokreće i transport kod drugih (na sljedećem taktu). */
    private fun launchSlot(slot: Int) {
        if (_linkOn.value && _live.value.slotStates[slot].toInt() == NativeAudio.IDLE) NativeAudio.linkStart()
        NativeAudio.launch(slot)
    }

    fun launchScene(scene: Int) {
        if (!audioReady || scene !in 0 until Layout.SCENES) return
        if (_linkOn.value && (0 until Layout.TRACKS).any { hasClip(Layout.slot(scene, it)) }) NativeAudio.linkStart()
        NativeAudio.launchScene(scene)
    }

    fun stopTrack(track: Int) {
        if (audioReady) NativeAudio.stopTrack(track)
    }

    /** STOP ALL (dugme): sve odmah staje; sa Linkom staje i transport kod drugih (kao Ableton). */
    fun stopAll() {
        if (!audioReady) return
        keys.stopRec()
        NativeAudio.stopAll()
        NativeAudio.studioStop(NativeAudio.PART_ALL)
        keys.allNotesOff()
        if (_linkOn.value) NativeAudio.linkStop()
    }

    /** Donji red padova u session prikazu, ovisno o modu (Stop / Solo / Mute). */
    fun bottomPad(track: Int) {
        when (_control.value.bottomMode) {
            BottomMode.STOP -> stopTrack(track)
            BottomMode.SOLO -> toggleSolo(track)
            BottomMode.MUTE -> toggleMute(track)
        }
    }

    fun cycleBottomMode() = _control.update { it.copy(bottomMode = it.bottomMode.next()) }

    fun setBottomMode(mode: BottomMode) = _control.update { it.copy(bottomMode = mode) }

    /** Undo/redo na DRUMS ili KEYS ekranu (tastatura računara: Ctrl+Z / Ctrl+Y). Vraća false na drugim ekranima. */
    fun studioUndo(redo: Boolean): Boolean = when (_control.value.screen) {
        Screen.DRUMS -> { if (redo) drums.redo() else drums.undo(); true }
        Screen.KEYS -> { if (redo) keys.redo() else keys.undo(); true }
        else -> false
    }

    fun setScreen(screen: Screen) {
        if (screen != Screen.KEYS) keys.allNotesOff()   // note sa Launchpad klavijature ne ostaju da sviraju
        // Patterni Drums i Keys sviraju dok ih korisnik sam ne zaustavi (i na drugim ekranima)
        _control.update { it.copy(screen = screen) }
    }

    /** Dugme Session: Session <-> Mikser; sa Drums ekrana vraća u Session. */
    fun toggleScreen() = setScreen(if (_control.value.screen == Screen.SESSION) Screen.MIXER else Screen.SESSION)

    // ---------------------------------------------------------------- Drums (Studio)

    /** Play/Stop Drums patterna (samo Drums). Sa Linkom pokreće i transport kod drugih (kao klip). */
    fun drumsTogglePlay() {
        if (!audioReady) return
        if (_linkOn.value && NativeAudio.studioTransport(NativeAudio.PART_DRUMS) == NativeAudio.STUDIO_STOPPED) NativeAudio.linkStart()
        drums.togglePlay()
    }

    // ---------------------------------------------------------------- Keys (Studio)

    /** Play/Stop Keys patterna (isti transport kao Drums: oba sviraju zajedno). */
    /** Play/Stop Keys patterna (samo Keys). */
    fun keysTogglePlay() {
        if (!audioReady) return
        if (_linkOn.value && NativeAudio.studioTransport(NativeAudio.PART_KEYS) == NativeAudio.STUDIO_STOPPED) NativeAudio.linkStart()
        keys.togglePlay()
    }

    /** REC u Keys (aplikacija i Launchpad CC 29): odbrojavanje 1 takt ako ne svira, pa snimanje; ponovo = kraj. */
    fun keysToggleRec() {
        if (!audioReady) return
        keys.toggleRec()
    }

    // ---- IMPORT MIDI (Keys i Drums): fajl sa više instrumenata -> izbor u dijalogu (midiChoice)
    data class MidiChoice(val song: MidiFile.Song, val drums: Boolean)
    private val _midiChoice = MutableStateFlow<MidiChoice?>(null)
    val midiChoice: StateFlow<MidiChoice?> = _midiChoice

    /** [drums] = uvoz u Drums (GM raspored na trake); inače u Keys. Drums: jedini dio na kanalu 10 se uzme odmah. */
    fun importMidiFile(ref: String, drums: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = docs.openInput(ref)?.use { it.readBytes() } ?: throw IllegalStateException("cannot read file")
                val parsed = MidiFile.parse(bytes)
                // za Drums su dijelovi na kanalu 10 prvi u listi
                val song = if (drums) parsed.copy(parts = parsed.parts.sortedByDescending { it.isDrums }) else parsed
                when {
                    song.parts.isEmpty() -> _message.value = "No notes in this MIDI file"
                    song.parts.size == 1 -> applyMidiPart(MidiChoice(song, drums), 0)
                    drums && song.parts.count { it.isDrums } == 1 -> applyMidiPart(MidiChoice(song, true), 0)
                    else -> _midiChoice.value = MidiChoice(song, drums)
                }
            } catch (e: Exception) {
                _message.value = "MIDI import failed: ${e.message}"
            }
        }
    }

    fun applyMidiPart(choice: MidiChoice, index: Int) {
        _midiChoice.value = null
        val part = choice.song.parts.getOrNull(index) ?: return
        val cutNote = { cut: Int -> if (cut > 0) " (rest cut off)" else "" }
        if (choice.drums) {
            val (n, cut, silent) = drums.importMidi(part, choice.song.ppq)
            val b = drums.state.value.bars
            _message.value = "Imported $n hits, $b bar${if (b > 1) "s" else ""}${cutNote(cut)}" +
                (if (silent.isNotEmpty()) ". No sound yet: ${silent.joinToString(", ")}" else "")
        } else {
            val (n, cut) = keys.importMidi(part, choice.song.ppq)
            val b = keys.state.value.bars
            _message.value = "Imported $n notes, $b bar${if (b > 1) "s" else ""}${cutNote(cut)}"
        }
    }

    fun cancelMidiChoice() { _midiChoice.value = null }

    // ---- ugrađeni kitovi (Drums)
    /** Prazan Drums (nova sesija, stara sesija bez zvukova) dobije 808 kit. Pozadinska nit. */
    private fun ensureDrumKit() {
        if (drums.isKitEmpty()) drums.loadKit(DrumKits.all[0], quiet = true)
    }

    fun loadDrumKit(kit: DrumKits.Kit) {
        viewModelScope.launch(Dispatchers.IO) { drums.loadKit(kit) }
    }

    // ---- ugrađeni zvuk trake (potenciometri u dijalogu zvuka): dok se vuče samo motor, kad se pusti wav + stanje.
    // Jedna pozadinska korutina; preskočeni su samo zastarjeli pregledi, svaki upis se izvrši.
    private class SynthEdit(val lane: Int, val spec: DrumSynthSpec, val commit: Boolean)
    private val synthEdits = Channel<SynthEdit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (first in synthEdits) {
                var e = first
                while (true) {
                    val next = synthEdits.tryReceive().getOrNull() ?: break
                    if (e.commit) applySynthEdit(e)
                    e = next
                }
                applySynthEdit(e)
            }
        }
    }

    private fun applySynthEdit(e: SynthEdit) {
        if (!e.commit) { drums.previewSynth(e.lane, e.spec); return }
        if (drums.setSynth(e.lane, e.spec, e.spec.kind.label, quiet = true)) drums.hit(e.lane)
    }

    /** [commit] = false dok se potenciometar vuče (samo pregled), true kad se pusti ili se promijeni vrsta (svira jednom). */
    fun editDrumSynth(lane: Int, spec: DrumSynthSpec, commit: Boolean) {
        synthEdits.trySend(SynthEdit(lane, spec, commit))
    }

    fun loadPreset(p: SynthPreset) = keys.loadPreset(p)

    fun saveUserPreset(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        val params = keys.state.value.params
        keys.setPresetName(clean)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                presets.save(clean, params)
                _userPresets.value = presets.user()
                _message.value = "Preset saved: $clean"
            } catch (e: Exception) {
                _message.value = "Could not save the preset: ${e.message}"
            }
        }
    }

    fun deleteUserPreset(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                presets.delete(name)
            } catch (_: Exception) {
            }
            _userPresets.value = presets.user()
        }
    }

    fun openDrumBrowser(lane: Int) {

        _editSlot.value = null
        _drumBrowserLane.value = lane
    }

    /** Fajl iz memorije (izbor fajla platforme) kao zvuk trake. */
    fun assignDrumFile(lane: Int, ref: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = displayName(ref) ?: "sample"
            drums.assign(lane, ref, name, name.substringBeforeLast('.'))
        }
    }

    // ---------------------------------------------------------------- tempo

    fun setBpm(value: Int) {
        val v = value.coerceIn(60, 240)
        if (_linkOn.value) {
            // Sa Linkom tempo se mijenja preko Linka; mikser ga preuzme, a poll petlja ažurira prikaz i razvlačenje
            NativeAudio.linkSetTempo(v.toDouble())
            return
        }
        _bpm.value = v
        NativeAudio.setBpm(v.toDouble())
        midi.setClockBpm(v.toDouble())
        scheduleSave()
    }

    // ---------------------------------------------------------------- Ableton Link

    fun toggleLink() = setLink(!_linkOn.value)

    fun setLink(on: Boolean) {
        if (on == _linkOn.value) return
        if (on) {
            try {
                // Android: bez multicast lock-a se Link-ovi paketi filtriraju na Wi-Fi mreži
                multicastLock = platform.acquireLinkLock()
            } catch (_: Exception) {}
            val start = NativeAudio.engineBpm()
            lastEngineBpm = start
            NativeAudio.linkSetOffsetMs(_linkOffsetMs.value.toDouble())
            NativeAudio.linkAudioEnable(_linkAudio.value)
            NativeAudio.linkEnable(true, start)
            _linkOn.value = true
            _message.value = "Link on"
        } else {
            NativeAudio.linkEnable(false, NativeAudio.engineBpm())
            try { multicastLock?.close() } catch (_: Exception) {}
            multicastLock = null
            _linkOn.value = false
            _linkPeers.value = -1
            _message.value = "Link off"
        }
    }

    /** Link Audio: master miks ide u Link sesiju kao kanal "PadSampler Master" (npr. ulaz u Abletonu). Pamti se. */
    fun toggleLinkAudio() {
        val on = !_linkAudio.value
        _linkAudio.value = on
        platform.putInt("link_audio", if (on) 1 else 0)
        NativeAudio.linkAudioEnable(on)
        _message.value = if (on) "Link audio on (channel \"PadSampler Master\")" else "Link audio off"
    }

    /** Ručno fino podešavanje kašnjenja za Link (ms), npr. ako se bubanj čuje malo ispred/iza Abletona. */
    fun changeLinkOffset(deltaMs: Int) {
        val v = (_linkOffsetMs.value + deltaMs).coerceIn(-200, 200)
        _linkOffsetMs.value = v
        NativeAudio.linkSetOffsetMs(v.toDouble())
        platform.putInt("link_offset_ms", v)
    }

    /** Zove se iz poll petlje dok je Link uključen: prati tempo koji mikser preuzima od Linka. */
    private fun followLink(pollCount: Int) {
        val eb = NativeAudio.engineBpm()
        if (kotlin.math.abs(eb - lastEngineBpm) > 0.005) {
            lastEngineBpm = eb
            NativeAudio.setBankBpm(eb)       // semplovi se razvuku na tačan (decimalni) tempo
            midi.setClockBpm(eb)
            _bpm.value = eb.roundToInt()
            scheduleSave()
        }
        if (pollCount % 10 == 0) _linkPeers.value = NativeAudio.linkPeers()
    }

    fun changeBpm(delta: Int) = setBpm(_bpm.value + delta)

    // ---------------------------------------------------------------- mikser

    fun setFader(track: Int, v: Float) {
        val value = v.coerceIn(0f, 1f)
        _control.update { c -> c.copy(faders = c.faders.toMutableList().also { it[track] = value }) }
        NativeAudio.setTrackGain(track, Fader.gain(value))
        scheduleSave()
    }

    /** Pan tracka: -1 lijevo .. 0 sredina .. +1 desno (blizu sredine = tačno sredina). */
    fun setPan(track: Int, v: Float) {
        if (track !in 0 until Layout.TRACKS) return
        val value = v.coerceIn(-1f, 1f).let { if (kotlin.math.abs(it) < 0.02f) 0f else it }
        _control.update { c -> c.copy(pans = c.pans.toMutableList().also { it[track] = value }) }
        NativeAudio.setTrackPan(track, value)
        scheduleSave()
    }

    fun toggleMute(track: Int) {
        _control.update { c -> c.copy(mutes = c.mutes.toMutableList().also { it[track] = !it[track] }) }
        NativeAudio.setTrackMute(track, _control.value.mutes[track])
        scheduleSave()
    }

    fun toggleSolo(track: Int) {
        _control.update { c -> c.copy(solos = c.solos.toMutableList().also { it[track] = !it[track] }) }
        NativeAudio.setTrackSolo(track, _control.value.solos[track])
    }

    fun setMaster(v: Float) {
        val value = v.coerceIn(0f, 1f)
        _control.update { it.copy(master = value) }
        NativeAudio.setMasterGain(Fader.gain(value))
        scheduleSave()
    }

    fun toggleMasterMute() {
        _control.update { it.copy(masterMute = !it.masterMute) }
        NativeAudio.setMasterMute(_control.value.masterMute)
    }

    // ---------------------------------------------------------------- uređivanje semplova

    fun setEditMode(on: Boolean) {
        _editMode.value = on
        if (!on) _editSlot.value = null
    }

    fun closeEditDialog() {
        _editSlot.value = null
    }

    /** pathHint: putanja iz indeksa biblioteke (Android SAF ponekad ne daje putanju dokumenta). */
    fun assignSample(slot: Int, ref: String, pathHint: String? = null) {
        _editSlot.value = null
        viewModelScope.launch(Dispatchers.IO) { doAssign(slot, ref, pathHint = pathHint) }
    }

    /** Učitava jedan sempl u pad (poziva se iz pozadinske korutine, jedan po jedan). */
    private suspend fun doAssign(slot: Int, ref: String, inherit: Boolean = true, pathHint: String? = null) {
        run {
            _message.value = "Loading..."
            val name = displayName(ref) ?: "sample"
            val target = copyIntoStore(ref, slot, name)
            if (target == null) {
                _message.value = "Cannot read file: $name"
                return
            }
            val old = _pads.value[slot]

            // Originalni BPM: iz putanje/imena foldera ("... 124 BPM ..."), inače stari BPM pada, inače trenutni tempo
            // Putanja za BPM, labelu i tonalitet: iz biblioteke (folder + ime), inače od dokumenta
            val path = pathHint ?: documentPath(ref)
            val detected = BpmParser.fromPath(path, name)
            // Ako BPM nije u putanji, C++ ga procijeni iz dužine loopa (0 = nepoznat); stari BPM pada se ne nasljeđuje
            var srcBpm = detected ?: 0.0
            // Prvi sempl sesije sa prepoznatim BPM-om postavlja i tempo sesije
            if (_pads.value.isEmpty() && detected != null && !_linkOn.value) setBpm(detected.roundToInt())

            val mode = when {
                inherit && old != null -> if (old.loop) NativeAudio.LOOP_ON else NativeAudio.LOOP_OFF
                name.contains("loop", ignoreCase = true) -> NativeAudio.LOOP_ON
                else -> NativeAudio.LOOP_AUTO
            }
            NativeAudio.killSlot(slot)
            val frames = loader.load(slot, target, srcBpm, mode)
            if (frames <= 0) {
                target.delete()
                _message.value = "Unsupported format or corrupt file: $name"
                return
            }
            old?.let { store.fileFor(it).delete() }

            if (detected == null) {
                srcBpm = NativeAudio.slotSrcBpm(slot).takeIf { it > 0.0 } ?: _bpm.value.toDouble()
            }
            val loop = NativeAudio.slotIsLoop(slot)
            val label = folderLabel(path) ?: name.substringBeforeLast('.')
            val key = KeyParser.fromPath(path, name)   // samo prikaz
            _pads.update { it + (slot to PadConfig(slot, name, target.name, loop, srcBpm, label, key)) }
            scheduleSave()
            val bpmText = if (detected != null) "%.0f BPM".format(srcBpm)
                else "BPM not in name, estimated %.1f from length".format(srcBpm)
            _message.value = "Loaded: $name (" + (if (loop) "loop" else "one-shot") + ", $bpmText)"
        }
    }

    fun removeSample(slot: Int) {
        _editSlot.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val old = _pads.value[slot] ?: return@launch
            NativeAudio.killSlot(slot)
            NativeAudio.clearSample(slot)
            _pads.update { it - slot }
            store.fileFor(old).delete()
            scheduleSave()
            _message.value = "Removed: ${old.name}"
        }
    }

    fun setLoop(slot: Int, loop: Boolean) {
        val cfg = _pads.value[slot] ?: return
        NativeAudio.setSlotLoop(slot, loop)
        _pads.update { it + (slot to cfg.copy(loop = loop)) }
        scheduleSave()
    }

    /** Ručno postavljanje originalnog BPM-a sempla (ako ga nije bilo moguće pročitati iz imena). */
    fun setSampleBpm(slot: Int, bpm: Double) {
        val cfg = _pads.value[slot] ?: return
        val v = bpm.coerceIn(20.0, 400.0)
        NativeAudio.setSlotBpm(slot, v)
        _pads.update { it + (slot to cfg.copy(srcBpm = v)) }
        scheduleSave()
    }

    /** Isti originalni BPM za sve semplove (svi semplovi jedne pjesme imaju isti BPM). */
    fun setSampleBpmAll(bpm: Double) {
        val v = bpm.coerceIn(20.0, 400.0)
        for (slot in _pads.value.keys) NativeAudio.setSlotBpm(slot, v)
        _pads.update { m -> m.mapValues { it.value.copy(srcBpm = v) } }
        scheduleSave()
    }

    private fun documentPath(ref: String): String? = docs.documentPath(ref)

    /** Ime foldera u kojem je sempl (npr. "Drums"), iz putanje dokumenta; null ako putanje nema. */
    private fun folderLabel(path: String?): String? {
        if (path == null) return null
        val parts = path.split('/', '\\').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts[parts.size - 2].substringAfterLast(':').trim().ifEmpty { null }
    }

    private fun displayName(ref: String): String? = try { docs.displayName(ref) } catch (e: Exception) { null }

    /** Kopira izabrani fajl u privatni folder aplikacije, pa ne ovisi o dozvolama ni o originalu. */
    private fun copyIntoStore(ref: String, slot: Int, name: String): File? {
        val ext = name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        val target = File(store.samplesDir, "pad${slot}_${System.currentTimeMillis()}.$ext")
        return try {
            docs.openInput(ref)?.use { input ->
                FileOutputStream(target).use { input.copyTo(it) }
            } ?: return null
            target
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    // ---------------------------------------------------------------- sesije

    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) { _sessions.value = sessionStore.list() }
    }

    fun saveSession(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                saveJob?.cancel()
                _sessionName.value = name.trim()
                val info = sessionStore.save(name, snapshot(), store)
                _sessionName.value = info.name
                store.save(snapshot())
                _sessions.value = sessionStore.list()
                _message.value = "Saved session: ${info.name}"
            } catch (e: Exception) {
                _message.value = "Could not save session: ${e.message}"
            }
        }
    }

    fun deleteSession(info: SessionInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.delete(info.dirName)
            _sessions.value = sessionStore.list()
            // obrisana sesija ispada i iz set liste
            while (true) {
                val i = _setList.value.items.indexOf(info.dirName)
                if (i < 0) break
                removeFromSetList(i)
            }
            _message.value = "Deleted session: ${info.name}"
        }
    }

    // ---- izvoz / uvoz sesije kao jedan fajl (.padsession = ZIP: pads.json + samples/)

    /** Predloženo ime fajla za izvoz. */
    fun sessionFileName(info: SessionInfo): String =
        info.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "session" } + ".padsession"

    /** Izvoz sesije u fajl koji je korisnik izabrao ("Save as"). */
    fun exportSessionFile(info: SessionInfo, ref: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _message.value = "Exporting ${info.name}..."
                val out = docs.openOutputFile(ref) ?: throw IllegalStateException("cannot write file")
                val count = out.use { sessionStore.exportZip(info.dirName, it) }
                _message.value = "Exported \"${info.name}\" ($count samples)"
            } catch (e: Exception) {
                _message.value = "Export failed: ${e.message}"
            }
        }
    }

    /**
     * Izvoz sesije u izabrani folder: <folder>/<ime sesije>/ sa <ime>.padsession (projekt, za uvoz na tabletu ili
     * računaru) i semplovima po folderima labela (Drums, Drums/Kit, Bass...), da se mogu koristiti i van aplikacije.
     * Postojeći fajlovi istog imena se prepisuju.
     */
    fun exportSessionToFolder(info: SessionInfo, tree: String) {
        viewModelScope.launch(Dispatchers.IO) { exportToFolder(info, tree) }
    }

    /** Dugme "export session": snimi trenutno stanje pod imenom, pa izvezi u izabrani folder. */
    fun saveAndExport(name: String, tree: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val info = try {
                saveJob?.cancel()
                val i = sessionStore.save(name, snapshot(), store)
                _sessionName.value = i.name
                store.save(snapshot())
                _sessions.value = sessionStore.list()
                i
            } catch (e: Exception) {
                _message.value = "Could not save session: ${e.message}"
                return@launch
            }
            exportToFolder(info, tree)
        }
    }

    private fun exportToFolder(info: SessionInfo, tree: String) {
        run {
            try {
                _message.value = "Exporting ${info.name}..."
                val rootId = docs.treeRootId(tree) ?: throw IllegalStateException("invalid folder")
                val dirs = HashMap<String, String>()   // relativna putanja foldera -> id
                fun child(parentId: String, name: String, dir: Boolean): String =
                    docs.children(tree, parentId).firstOrNull { it.name == name && it.isDir == dir }?.id
                        ?: docs.createChild(tree, parentId, name, dir)
                        ?: throw IllegalStateException("cannot create $name")
                fun folder(path: String): String = dirs.getOrPut(path) {
                    val i = path.lastIndexOf('/')
                    if (i < 0) child(dirs.getValue(""), path, true)
                    else child(folder(path.substring(0, i)), path.substring(i + 1), true)
                }
                dirs[""] = child(rootId, safeFolder(info.name), true)

                // projekt (.padsession, sa semplovima, kao do sada)
                val zipId = child(dirs.getValue(""), sessionFileName(info), false)
                (docs.openOutput(tree, zipId) ?: throw IllegalStateException("cannot write project file"))
                    .use { sessionStore.exportZip(info.dirName, it) }

                // semplovi po folderima
                val dir = sessionStore.dir(info.dirName)
                val folders = PadStore(dir).load().exportFolders()
                var count = 0
                File(dir, "samples").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
                    val parent = folder(folders[f.name] ?: "Samples")
                    val id = child(parent, f.name, false)
                    (docs.openOutput(tree, id) ?: throw IllegalStateException("cannot write ${f.name}"))
                        .use { out -> f.inputStream().use { it.copyTo(out) } }
                    count++
                }
                _message.value = "Exported \"${info.name}\" (project file + $count samples in folders)"
            } catch (e: Exception) {
                _message.value = "Export failed: ${e.message}"
            }
        }
    }

    /** Uvoz iz .padsession fajla. Postojeće sesije se nikad ne prepisuju (ime dobije " (2)"...). */
    fun importSessionFile(ref: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _message.value = "Importing..."
                val input = docs.openInput(ref) ?: throw IllegalStateException("cannot read file")
                val info = input.use { sessionStore.importZip(it, File(platform.cacheDir, "import_tmp")) }
                _sessions.value = sessionStore.list()
                _message.value = if (info == null) "Not a PadSampler session file" else "Imported session: ${info.name}"
            } catch (e: Exception) {
                _message.value = "Import failed: ${e.message}"
            }
        }
    }

    // ---- set lista (redoslijed sesija za nastup)

    fun addToSetList(info: SessionInfo) = updateSetList { it.copy(items = it.items + info.dirName) }

    fun removeFromSetList(index: Int) = updateSetList { s ->
        if (index !in s.items.indices) s
        else s.copy(
            items = s.items.filterIndexed { i, _ -> i != index },
            current = when {
                s.current == index -> -1
                s.current > index -> s.current - 1
                else -> s.current
            }
        )
    }

    /** Pomjera pjesmu za delta mjesta (-1 gore, +1 dolje). */
    fun moveInSetList(index: Int, delta: Int) = updateSetList { s ->
        val to = index + delta
        if (index !in s.items.indices || to !in s.items.indices) s
        else {
            val items = s.items.toMutableList()
            items.add(to, items.removeAt(index))
            val cur = when (s.current) {
                index -> to
                to -> index
                else -> s.current
            }
            s.copy(items = items, current = cur)
        }
    }

    /** Učitava pjesmu sa set liste (kao "load session": zaustavlja sve). */
    fun playSetListItem(index: Int) {
        val dir = _setList.value.items.getOrNull(index) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val info = sessionStore.list().firstOrNull { it.dirName == dir }
            if (info == null) {
                _message.value = "Session is missing (deleted?)"
                return@launch
            }
            updateSetList { it.copy(current = index) }
            loadSession(info)
        }
    }

    fun setListNext() {
        val s = _setList.value
        if (s.current + 1 < s.items.size) playSetListItem(s.current + 1)
    }

    fun setListPrev() {
        val s = _setList.value
        if (s.current > 0) playSetListItem(s.current - 1)
    }

    private fun updateSetList(change: (SetList) -> SetList) {
        _setList.updateAndGet(change)
        viewModelScope.launch(Dispatchers.IO) { setListStore.save(_setList.value) }
    }

    private fun copyDocToFile(tree: String, docId: String, target: File) {
        docs.openInput(docs.childRef(tree, docId))?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }

    /** Čita folder sesije (pads.json + samples/) iz SAF-a u privremeni folder. Null ako nema pads.json. */
    private fun readSessionDir(tree: String, dirId: String, tmp: File): File? {
        val kids = docs.children(tree, dirId)
        val pads = kids.firstOrNull { !it.isDir && it.name == "pads.json" } ?: return null
        tmp.deleteRecursively()
        File(tmp, "samples").mkdirs()
        copyDocToFile(tree, pads.id, File(tmp, "pads.json"))
        kids.firstOrNull { it.isDir && it.name == "samples" }?.let { sd ->
            for (f in docs.children(tree, sd.id)) {
                if (!f.isDir) copyDocToFile(tree, f.id, File(tmp, "samples/" + f.name))
            }
        }
        return tmp
    }

    /** Uvoz: izabrani folder je ili jedna sesija (ima pads.json) ili folder sa više sesija. Ništa se ne prepisuje. */
    fun importSessions(tree: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _message.value = "Importing..."
                val rootId = docs.treeRootId(tree) ?: throw IllegalStateException("invalid folder")
                val top = docs.children(tree, rootId)
                val dirIds = if (top.any { !it.isDir && it.name == "pads.json" }) listOf(rootId)
                else top.filter { it.isDir }.map { it.id }
                var imported = 0
                var last = ""
                val tmp = File(platform.cacheDir, "import_tmp")
                for (id in dirIds) {
                    val dir = readSessionDir(tree, id, tmp) ?: continue
                    sessionStore.importDir(dir)?.let { imported++; last = it.name }
                }
                tmp.deleteRecursively()
                _sessions.value = sessionStore.list()
                _message.value = when {
                    imported == 0 -> "No sessions found in that folder (looking for pads.json)"
                    imported == 1 -> "Imported session: $last"
                    else -> "Imported $imported sessions"
                }
            } catch (e: Exception) {
                _message.value = "Import failed: ${e.message}"
            }
        }
    }

    /** Učitava sesiju: zaustavlja sve, mijenja semplove, tempo i miks. */
    fun loadSession(info: SessionInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val opened = sessionStore.open(info.dirName)
            if (opened == null) {
                _message.value = "Session not found: ${info.name}"
                return@launch
            }
            val (data, source) = opened
            _message.value = "Loading session: ${info.name}..."
            saveJob?.cancel()
            _editMode.value = false
            _editSlot.value = null
            NativeAudio.stopAll()

            // 1) ukloni trenutne semplove
            for (slot in 0 until Layout.SLOTS) {
                NativeAudio.killSlot(slot)
                NativeAudio.clearSample(slot)
            }
            _pads.value = emptyMap()
            store.samplesDir.listFiles()?.forEach { it.delete() }

            // 2) kopiraj semplove sesije (i zvukove Drums) u radni folder
            for (name in data.referencedFiles()) {
                val src = source.file(name)
                if (src.exists()) src.copyTo(store.file(name), overwrite = true)
            }

            // 3) tempo i miks (tempo prije učitavanja, da se semplovi odmah razvuku na njega)
            if (!_linkOn.value) {   // sa Linkom tempo ostaje onaj iz Linka (da se ne mijenja tempo drugima)
                _bpm.value = data.bpm
                NativeAudio.setBpm(data.bpm.toDouble())
                midi.setClockBpm(data.bpm.toDouble())
            }
            _control.update {
                it.copy(
                    faders = data.faders, pans = data.pans, mutes = data.mutes,
                    solos = List(Layout.TRACKS) { false }, master = data.master, masterMute = false
                )
            }
            applyMixerToEngine()

            // 4) učitaj semplove
            var failed = 0
            val loaded = mutableMapOf<Int, PadConfig>()
            for (cfg in data.pads) {
                val f = store.fileFor(cfg)
                val mode = if (cfg.loop) NativeAudio.LOOP_ON else NativeAudio.LOOP_OFF
                if (f.exists() && loader.load(cfg.slot, f, cfg.srcBpm, mode) > 0) loaded[cfg.slot] = cfg else failed++
            }
            _pads.value = loaded
            drums.setState(data.drums ?: DrumsState())
            ensureDrumKit()
            keys.setState(data.keys ?: KeysState())
            _printTarget.value = null
            _sessionName.value = data.name.ifBlank { info.name }
            store.save(snapshot())
            _message.value = if (failed > 0) "Loaded ${_sessionName.value} ($failed sample(s) failed)" else "Loaded session: ${_sessionName.value}"
        }
    }

    /** New session: zaustavi sve, obriši sve padove, miks na početno stanje (tempo ostaje). */
    fun newSession() {
        viewModelScope.launch(Dispatchers.IO) {
            saveJob?.cancel()
            _editMode.value = false
            _editSlot.value = null
            NativeAudio.stopAll()
            for (slot in 0 until Layout.SLOTS) {
                NativeAudio.killSlot(slot)
                NativeAudio.clearSample(slot)
            }
            _pads.value = emptyMap()
            store.samplesDir.listFiles()?.forEach { it.delete() }
            drums.setState(DrumsState())
            ensureDrumKit()
            keys.setState(KeysState())
            _printTarget.value = null
            _control.update {
                it.copy(
                    faders = List(Layout.TRACKS) { Fader.DEFAULT },
                    pans = List(Layout.TRACKS) { 0f },
                    mutes = List(Layout.TRACKS) { false },
                    solos = List(Layout.TRACKS) { false },
                    master = 1f, masterMute = false
                )
            }
            applyMixerToEngine()
            _sessionName.value = ""
            store.save(snapshot())
            _message.value = "New session"
        }
    }

    // ---------------------------------------------------------------- browser biblioteke

    /** "Fill column": prvih 6 fajlova iz foldera (abecedno) ide u kolonu pada koji je otvorio browser, od scene 1 nanize. */
    fun fillColumnFromFolder(dir: String) {
        val slot = _browserSlot.value ?: return
        val track = slot % Layout.TRACKS
        val files = library.listDir(dir).second
            .sortedBy { it.name.lowercase() }
            .take(Layout.SCENES)
        if (files.isEmpty()) return
        val refs = files.mapNotNull { e -> library.documentRef(e)?.let { it to "${e.dir}/${e.name}" } }
        closeBrowser()
        viewModelScope.launch(Dispatchers.IO) {
            _message.value = "Filling column ${track + 1}..."
            refs.forEachIndexed { i, (ref, path) -> doAssign(Layout.slot(i, track), ref, inherit = false, pathHint = path) }
            // pad-ovi ispod popunjenih ostaju prazni samo ako je u folderu manje od 6 fajlova; stari se brišu
            for (i in refs.size until Layout.SCENES) {
                val s = Layout.slot(i, track)
                val old = _pads.value[s] ?: continue
                NativeAudio.killSlot(s)
                NativeAudio.clearSample(s)
                _pads.update { it - s }
                store.fileFor(old).delete()
            }
            scheduleSave()
            _message.value = "Column ${track + 1}: ${refs.size} sample(s) from ${dir.substringAfterLast('/')}"
        }
    }

    fun openBrowser(slot: Int) {
        _editSlot.value = null
        _browserSlot.value = slot
    }

    fun closeBrowser() {
        stopPreview()
        _browserSlot.value = null
        _drumBrowserLane.value = null
    }

    fun addLibraryFolder(tree: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = library.addRoot(tree)
            if (id >= 0) library.scan(id) else _message.value = "Cannot use that folder"
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            for (r in library.roots.value) library.scan(r.id)
        }
    }

    fun removeLibraryFolder(id: Int) {
        viewModelScope.launch(Dispatchers.IO) { library.removeRoot(id) }
    }

    fun setPreviewMatch(on: Boolean) {
        _previewMatch.value = on
    }

    fun stopPreview() {
        previewGen++
        previewJob?.cancel()
        NativeAudio.previewStop()
        _previewKey.value = null
    }

    /** Dodir na sempl: pusti ga (original ili na tempu sesije); ponovni dodir ga zaustavlja. */
    fun togglePreview(entry: LibEntry) {
        if (_previewKey.value == entry.docId) {
            stopPreview()
            return
        }
        val ref = library.documentRef(entry) ?: run {
            _message.value = "Sample folder is no longer in the library: rescan"
            return
        }
        val gen = ++previewGen
        previewJob?.cancel()
        _previewKey.value = entry.docId
        previewLoading = true
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val ok = startPreviewFor(ref, entry, gen)
                if (!ok && gen == previewGen) {
                    _previewKey.value = null
                    _message.value = "Cannot preview: ${entry.name}"
                }
            } finally {
                if (gen == previewGen) previewLoading = false
            }
        }
    }

    private fun startPreviewFor(ref: String, entry: LibEntry, gen: Int): Boolean {
        val bytes = try {
            docs.openInput(ref)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return false

        val src = entry.bpm ?: 0.0
        val target = if (_previewMatch.value && src > 0.0) _bpm.value.toDouble() else 0.0
        val mode = when {
            src > 0.0 -> NativeAudio.LOOP_AUTO
            entry.name.contains("loop", ignoreCase = true) -> NativeAudio.LOOP_ON
            else -> NativeAudio.LOOP_OFF
        }

        if (gen != previewGen) return true
        previewGraceUntil = System.currentTimeMillis() + 400
        if (NativeAudio.previewFromBytes(bytes, src, target, mode) > 0) return true

        // mp3 i ostali formati: preko dekodera platforme (Android: MediaCodec) (traži fajl, pa ide preko privremene kopije)
        val ext = entry.name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        val tmp = File(platform.cacheDir, "preview_${System.nanoTime()}.$ext")
        return try {
            tmp.writeBytes(bytes)
            val d = platform.decodeAudio(tmp.absolutePath) ?: return false
            if (gen != previewGen) return true
            previewGraceUntil = System.currentTimeMillis() + 400
            NativeAudio.previewSamples(d.data, d.frames, d.sampleRate, src, target, mode)
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    /** "Use" u browseru: dodijeli izabrani sempl padu koji je otvorio browser. */
    fun useFromLibrary(entry: LibEntry) {
        val ref = library.documentRef(entry) ?: return
        _drumBrowserLane.value?.let { lane ->
            closeBrowser()
            val label = entry.name.substringBeforeLast('.')
            viewModelScope.launch(Dispatchers.IO) { drums.assign(lane, ref, entry.name, label) }
            return
        }
        val slot = _browserSlot.value ?: return
        closeBrowser()
        assignSample(slot, ref, "${entry.dir}/${entry.name}")
    }

    // ---------------------------------------------------------------- spremanje

    private fun snapshot(): SessionData {
        val c = _control.value
        return SessionData(
            name = _sessionName.value, bpm = _bpm.value, pads = _pads.value.values.toList(),
            faders = c.faders, pans = c.pans, mutes = c.mutes, master = c.master, drums = drums.state.value,
            keys = keys.state.value
        )
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            val snap = snapshot()
            store.save(snap)
            gcSamples(snap)
        }
    }

    /**
     * Briše fajlove u samples/ koje ništa više ne koristi (zamijenjeni zvukovi Drums, stari printovi). Samo
     * fajlove starije od 2 minute, da se ne obriše fajl koji se upravo učitava.
     */
    private fun gcSamples(snap: SessionData) {
        val keep = snap.referencedFiles()
        val limit = System.currentTimeMillis() - 120_000
        store.samplesDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in keep && f.lastModified() < limit) f.delete()
        }
    }

    // ---------------------------------------------------------------- Drums: Print to pad

    /**
     * Renderuje Drums pattern u wav (tačno N taktova na trenutnom tempu, loop bez šava) i stavlja ga na pad kao
     * loop. Pad pamti pattern i kit (Edit in Drums).
     */
    fun printDrumsToPad(slot: Int) {
        val state = drums.state.value
        if (!drums.hasContent()) {
            _message.value = "Nothing to print: add sounds and steps first"
            return
        }
        printStudio(slot, NativeAudio.PART_DRUMS, "Drums", state.bars) { name, file, bpm ->
            PadConfig(slot, name, file, true, bpm, "Drums", null, state)
        }
    }

    /** Keys pattern u wav na pad; labela (i folder u izvozu) = kategorija (Bass, Lead...). Pad pamti zvuk i note. */
    fun printKeysToPad(slot: Int, category: String) {
        val label = category.trim().ifEmpty { "Keys" }
        keys.setCategory(label)
        val state = keys.state.value
        if (!keys.hasContent()) {
            _message.value = "Nothing to print: add notes first"
            return
        }
        printStudio(slot, NativeAudio.PART_KEYS, label, state.bars) { name, file, bpm ->
            PadConfig(slot, name, file, true, bpm, label, null, null, state)
        }
    }

    /** Renderuje dio Studija u wav (tačno N taktova na trenutnom tempu, loop bez šava) i stavlja ga na pad kao loop. */
    private fun printStudio(slot: Int, part: Int, label: String, bars: Int, config: (String, String, Double) -> PadConfig) {
        if (slot !in 0 until Layout.SLOTS || !audioReady) return
        _printTarget.value = slot
        viewModelScope.launch(Dispatchers.IO) {
            _message.value = "Printing ${label.lowercase()}..."
            val bpm = if (_linkOn.value) NativeAudio.engineBpm() else _bpm.value.toDouble()
            val rate = NativeAudio.sampleRate().takeIf { it > 0 } ?: 48000
            val audio = NativeAudio.studioRender(part, bpm, rate)
            if (audio.isEmpty()) {
                _message.value = "Nothing to print"
                return@launch
            }
            // Bez klipovanja: ako je zbir glasniji od 0 dB, cijeli loop se utiša taman ispod
            var peak = 0f
            for (x in audio) peak = maxOf(peak, kotlin.math.abs(x))
            if (peak > 0.99f) {
                val g = 0.99f / peak
                for (i in audio.indices) audio[i] *= g
            }
            // čitljivo ime (vidi se u izvozu sesije); broj na kraju čini ime jedinstvenim
            val title = "${safeFolder(label)} S${slot / Layout.TRACKS + 1}-T${slot % Layout.TRACKS + 1} $bars bar${if (bars > 1) "s" else ""} ${"%.0f".format(bpm)} BPM"
            val target = File(store.samplesDir, "$title (${System.currentTimeMillis() % 1_000_000}).wav")
            try {
                WavWriter.write24(target, audio, rate)
            } catch (e: Exception) {
                target.delete()
                _message.value = "Could not write the loop: ${e.message}"
                return@launch
            }
            NativeAudio.killSlot(slot)
            if (loader.load(slot, target, bpm, NativeAudio.LOOP_ON) <= 0) {
                target.delete()
                _message.value = "Could not load the loop"
                return@launch
            }
            _pads.update { it + (slot to config("$title.wav", target.name, bpm)) }
            scheduleSave()
            _message.value = "Printed to scene ${slot / Layout.TRACKS + 1}, track ${slot % Layout.TRACKS + 1}" +
                (if (peak > 0.99f) " (level lowered to avoid clipping)" else "")
        }
    }

    /** Pad nastao iz Keys: njegov zvuk i note idu u Keys (zamjenjuju trenutne), za izmjenu i ponovni print. */
    fun editPadInKeys(slot: Int) {
        val k = _pads.value[slot]?.keys ?: return
        _editSlot.value = null
        _editMode.value = false
        _printTarget.value = slot
        viewModelScope.launch(Dispatchers.IO) {
            keys.setState(k)
            scheduleSave()
            setScreen(Screen.KEYS)
            _message.value = "Editing keys of scene ${slot / Layout.TRACKS + 1}, track ${slot % Layout.TRACKS + 1}"
        }
    }

    /** Pad nastao iz Drums: njegov pattern i kit idu u Drums (zamjenjuju trenutni), za izmjenu i ponovni print. */
    fun editPadInDrums(slot: Int) {
        val studio = _pads.value[slot]?.studio ?: return
        _editSlot.value = null
        _editMode.value = false
        _printTarget.value = slot
        viewModelScope.launch(Dispatchers.IO) {
            drums.setState(studio)
            scheduleSave()
            setScreen(Screen.DRUMS)
            _message.value = "Editing drums of scene ${slot / Layout.TRACKS + 1}, track ${slot % Layout.TRACKS + 1}"
        }
    }

    // ---------------------------------------------------------------- MIDI klavijatura

    /** Uređaji za izbor (Android: odmah; računar: čita Java MIDI, zvati sa pozadinske niti). */
    fun keyboardChoices(): List<String> = try { keyboard.availableDevices() } catch (_: Exception) { emptyList() }

    fun selectKeyboard(selection: String) {
        _keyboardSelection.value = selection
        platform.putString(KEYBOARD_KEY, selection)
        keys.allNotesOff()
        keyboard.select(selection)
    }

    fun toggleLearn() {
        _learnMode.value = !_learnMode.value
        _learnTarget.value = null
    }

    /** MIDI LEARN: potenciometar koji čeka CC (ponovni klik ga odznači). */
    fun setLearnTarget(id: Int) {
        _learnTarget.value = if (_learnTarget.value == id) null else id
    }

    private fun assignCc(device: String, cc: Int, param: Int) {
        updateMidiMap { all ->
            // jedan CC = jedan potenciometar i obrnuto
            val m = (all[device] ?: emptyMap()).filter { (c, p) -> c != cc && p != param } + (cc to param)
            all + (device to m)
        }
        _learnTarget.value = null
        _message.value = "MIDI: CC $cc -> ${SynthParams.all[param].label}"
    }

    /** Briše mapiranje izabranog potenciometra (MIDI LEARN) na trenutnom uređaju. */
    fun clearLearnTarget() {
        val id = _learnTarget.value ?: return
        val dev = _learnDevice.value ?: return
        updateMidiMap { all -> all + (dev to (all[dev] ?: emptyMap()).filterValues { it != id }) }
        _learnTarget.value = null
    }

    /** Briše sva mapiranja trenutnog uređaja. */
    fun clearAllMappings() {
        val dev = _learnDevice.value ?: return
        updateMidiMap { it - dev }
        _learnTarget.value = null
    }

    private fun updateMidiMap(f: (Map<String, Map<Int, Int>>) -> Map<String, Map<Int, Int>>) {
        val m = _midiMap.updateAndGet { f(it).filterValues { v -> v.isNotEmpty() } }
        viewModelScope.launch(Dispatchers.IO) {
            try { midiMapStore.save(m) } catch (_: Exception) {}
        }
    }

    private inner class KeyboardHandler : KeyboardListener {

        override fun onKeyboardNote(note: Int, velocity: Int) {
            if (!audioReady) return
            if (velocity > 0) {
                keys.noteOn(note, velocity / 127f)
                if (keys.state.value.lpStep) keys.selectNote(note)   // nota za Launchpad STEP mod
            } else {
                keys.noteOff(note)
            }
        }

        override fun onKeyboardPitchBend(value: Int) {
            if (audioReady) NativeAudio.synthPitchBend(value / 8192f * PITCH_BEND_RANGE)
        }

        override fun onKeyboardControl(device: String, cc: Int, value: Int) {
            if (!audioReady) return
            _learnDevice.value = device
            // MIDI LEARN: prvi CC poslije izbora potenciometra se veže za njega
            val target = _learnTarget.value
            if (_learnMode.value && target != null) {
                if (cc == 120 || cc == 123) return
                assignCc(device, cc, target)
                return
            }
            val param = _midiMap.value[device]?.get(cc)
            if (param != null) {
                val def = SynthParams.all[param]
                keys.setParam(param, def.fromKnob(value / 127f))
                return
            }
            when (cc) {

                1 -> NativeAudio.synthModWheel(value / 127f)        // modulacioni točak: otvara filter
                120, 123 -> keys.allNotesOff()                      // All Sound Off / All Notes Off
            }
        }

        override fun onKeyboardConnection(devices: List<String>) {
            if (_learnDevice.value !in devices) _learnDevice.value = devices.firstOrNull()
            val before = _keyboardDevices.value
            _keyboardDevices.value = devices
            val added = devices - before.toSet()
            if (added.isNotEmpty()) _message.value = "MIDI keyboard: ${added.joinToString()}"
        }
    }

    // ---------------------------------------------------------------- MidiListener


    override fun onPad(row: Int, col: Int, down: Boolean) {
        if (!audioReady) return
        if (_control.value.screen == Screen.KEYS) {
            keysPad(row, col, down)
            return
        }
        if (!down) return
        when (_control.value.screen) {
            Screen.SESSION -> when (row) {
                Layout.METRONOME_ROW -> Unit
                Layout.FUNCTION_ROW -> bottomPad(col)
                else -> clipLaunchpad(Layout.slot(Layout.sceneOfRow(row), col))
            }
            Screen.MIXER -> if (row == Layout.FUNCTION_ROW) {
                toggleMute(col)
            } else {
                // red k (1..7) postavlja nivo na k; ponovni pritisak na red 1 gasi kolonu
                val current = Fader.stepFor(_control.value.faders[col])
                val step = if (row == 1 && current == 1) 0 else row
                setFader(col, Fader.valueForStep(step))
            }
            Screen.DRUMS -> drumsPad(row, col)
            Screen.KEYS -> Unit
        }
    }

    /** Keys na Launchpadu: redovi 1..7 klavijatura u skali (pritisak i otpuštanje), red 0 Play, oktava -/+. */
    private fun keysPad(row: Int, col: Int, down: Boolean) {
        if (row in 1..7) {
            keys.lpPad(row, col, down)
            return
        }
        if (!down || row != Layout.FUNCTION_ROW) return
        when (col) {
            KeysLayout.COL_PLAY -> keysTogglePlay()
            KeysLayout.COL_OCT_DOWN -> keys.shiftOctave(-1)
            KeysLayout.COL_OCT_UP -> keys.shiftOctave(+1)
            KeysLayout.COL_MODE -> keys.toggleLpMode()
        }
    }

    /** Drums na Launchpadu (raspored u LaunchpadLeds.composeDrums i SPECIFIKACIJA "Studio"). */
    private fun drumsPad(row: Int, col: Int) {
        when (row) {
            in 4..7 -> drums.toggleLpStep((7 - row) * 8 + col)   // red 3 je prazan
            DrumLayout.ROW_SOUNDS -> drums.hitAndSelect(col)
            DrumLayout.ROW_MUTES -> drums.toggleMute(col)
            Layout.FUNCTION_ROW -> when (col) {
                DrumLayout.COL_PLAY -> drumsTogglePlay()
                in DrumLayout.COL_LENGTH until DrumLayout.COL_LENGTH + DrumLayout.BAR_OPTIONS.size ->
                    drums.setBars(DrumLayout.BAR_OPTIONS[col - DrumLayout.COL_LENGTH])
                DrumLayout.COL_CLEAR -> drums.clearLane(drums.state.value.selected)
            }
        }
    }

    /** Sa Launchpada klip uvijek svira (edit mod važi samo za tablet). */
    private fun clipLaunchpad(slot: Int) {
        if (hasClip(slot)) launchSlot(slot)
    }

    override fun onControl(cc: Int, down: Boolean) {
        if (!down || !audioReady) return
        val screen = _control.value.screen
        when (cc) {
            LaunchpadLeds.CC_SESSION -> toggleScreen()
            LaunchpadLeds.CC_DRUMS -> setScreen(Screen.DRUMS)
            LaunchpadLeds.CC_KEYS -> setScreen(Screen.KEYS)
            // CC 19 (desna kolona dolje): SESSION/MIXER stop/solo/mute; DRUMS/KEYS Play/Stop tog dijela
            LaunchpadLeds.CC_MODE -> when (screen) {
                Screen.SESSION, Screen.MIXER -> cycleBottomMode()
                Screen.DRUMS -> drumsTogglePlay()
                Screen.KEYS -> keysTogglePlay()
            }
            // CC 29 na KEYS: REC
            LaunchpadLeds.CC_REC -> when (screen) {
                Screen.KEYS -> keysToggleRec()
                Screen.SESSION -> launchScene(5)
                else -> {}
            }
            91 -> changeBpm(+10)   // gore
            // dolje: na KEYS potenciometri prikaži/sakrij, na DRUMS ništa, inače tempo -10
            92 -> when (screen) {
                Screen.KEYS -> {
                    if (keys.state.value.panelOpen && _learnMode.value) toggleLearn()   // kao "hide" u aplikaciji
                    keys.togglePanel()
                }
                Screen.DRUMS -> {}
                else -> changeBpm(-10)
            }
            93 -> changeBpm(-1)    // lijevo
            94 -> changeBpm(+1)    // desno
            else -> {
                // Desna kolona, CC 79 (scena 0) ... CC 29 (scena 5)
                if (cc % 10 == 9 && cc / 10 in 2..7 && screen == Screen.SESSION) {
                    launchScene(7 - cc / 10)
                } else if (screen == Screen.DRUMS) {
                    DrumLayout.pageOfCc(cc)?.let { drums.setLpPage(it) }
                } else if (screen == Screen.KEYS && keys.state.value.lpStep) {
                    DrumLayout.pageOfCc(cc)?.let { keys.setLpPage(it) }
                }
            }
        }
    }

    override fun onConnectionChanged(connected: Boolean, description: String) {
        if (connected) {
            _midiStatus.value = "Launchpad: connected ($description)"
            midi.setClockBpm(_bpm.value.toDouble())
            surface.invalidate()
            // Launchpad koji je tek dobio struju (USB) se još pali/prebacuje u programmer mode i "pojede" dio LED
            // poruka; statični padovi (semplovi koji miruju) se ne šalju ponovo dok se ne promijene. Zato se cijela
            // slika pošalje još dva puta poslije spajanja. Jednokratno, ne periodično (nema bljeskanja).
            ledRepaintJob?.cancel()
            ledRepaintJob = viewModelScope.launch {
                for (wait in longArrayOf(1500L, 2500L)) {
                    delay(wait)
                    if (midi.isConnected) surface.invalidate()
                }
            }
        } else {
            ledRepaintJob?.cancel()
            _midiStatus.value = "Launchpad: not connected"
        }
    }

    /** Za platforme bez ViewModelStore-a (računar): isto što i uništenje ViewModela. */
    fun shutdown() = onCleared()

    override fun onCleared() {
        saveJob?.cancel()
        try { store.save(snapshot()) } catch (_: Exception) {}
        midi.stop()
        keyboard.stop()
        if (_linkOn.value) {
            NativeAudio.linkEnable(false, NativeAudio.engineBpm())
            try { multicastLock?.close() } catch (_: Exception) {}
        }
        NativeAudio.stop()
        NativeAudio.linkShutdown()
        super.onCleared()
    }
}

private const val KEYBOARD_KEY = "midiKeyboard"
/** Pitch bend: ±2 polutona (standard). */
private const val PITCH_BEND_RANGE = 2f
