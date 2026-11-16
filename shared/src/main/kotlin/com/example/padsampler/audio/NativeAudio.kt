package com.example.padsampler.audio

/**
 * Most prema C++ audio engineu (Oboe). Imena funkcija moraju odgovarati jni_bridge.cpp.
 * slot = scena * 8 + track (scena 0..5, track 0..7).
 */
object NativeAudio {
    const val TRACKS = 8
    const val SCENES = 6
    const val SLOTS = TRACKS * SCENES

    // Način loopa pri učitavanju sempla
    const val LOOP_OFF = 0
    const val LOOP_ON = 1
    const val LOOP_AUTO = 2   // loop ako je dužina blizu cijelog broja taktova

    // SlotState vrijednosti (moraju odgovarati mixer.h)
    const val EMPTY = 0
    const val IDLE = 1
    const val QUEUED_START = 2
    const val PLAYING = 3
    const val QUEUED_STOP = 4

    init {
        // Računar: Main.kt je biblioteku već učitao s pune putanje (paket aplikacije), pa se ne traži ponovo.
        if (System.getProperty("padsampler.preloaded") == null) System.loadLibrary("padsampler")
    }

    external fun start(): Boolean
    external fun stop()

    /**
     * data = interleaved stereo float (L,R,L,R...), frames = broj stereo frameova.
     * srcBpm = originalni BPM sempla; sempl se razvlači (bez promjene visine tona) na trenutni tempo.
     */
    external fun loadSample(slot: Int, data: FloatArray, frames: Int, sampleRate: Int, srcBpm: Double, loopMode: Int): Boolean

    /** Dekodira WAV/AIFF iz bajtova i učita u slot. Vraća broj frameova ili 0 ako format nije podržan. */
    external fun loadFromBytes(slot: Int, data: ByteArray, srcBpm: Double, loopMode: Int): Int

    external fun clearSample(slot: Int)
    /** Trajanje ORIGINALNOG sempla u sekundama. */
    external fun sampleDurationSec(slot: Int): Float

    /** Promijeni originalni BPM sempla (razvlačenje se ponovo izračuna u pozadini). */
    external fun setSlotBpm(slot: Int, bpm: Double)

    /** Je li slot loop (važno nakon LOOP_AUTO). */
    external fun slotSrcBpm(slot: Int): Double   // originalni BPM sempla (poznat ili procijenjen)
    external fun slotIsLoop(slot: Int): Boolean

    /** Koliko semplova još čeka razvlačenje na trenutni tempo (0 = sve gotovo). */
    external fun stretchPending(): Int

    /** Klip kreće na sljedeći takt. Ako svira, staje na sljedećem taktu. */
    external fun launch(slot: Int)
    external fun launchScene(scene: Int)
    external fun stopTrack(track: Int)
    external fun stopAll()
    external fun killSlot(slot: Int)

    external fun setBpm(bpm: Double)

    // ---- Ableton Link (sinhronizacija tempa i takta preko Wi-Fi mreže)
    external fun linkEnable(on: Boolean, bpm: Double)
    external fun linkShutdown()               // zatvaranje aplikacije: uništi Link sesiju i niti
    external fun linkPeers(): Int            // broj drugih učesnika, -1 ako je isključen
    external fun linkSetTempo(bpm: Double)
    external fun linkStart()                 // transport kreće na sljedećem taktu (ako ne svira)
    external fun linkStop()                  // transport staje kod svih učesnika
    external fun linkPlaying(): Boolean
    external fun linkAudioEnable(on: Boolean) // Link Audio: šalji master miks
    external fun linkSetOffsetMs(ms: Double)
    external fun linkLocked(): Boolean
    external fun engineBpm(): Double         // tempo koji mikser trenutno koristi (prati Link)
    external fun setBankBpm(bpm: Double)     // samo razvlačenje semplova (mikser je već na tom tempu)
    external fun setSlotLoop(slot: Int, loop: Boolean)
    external fun setTrackGain(track: Int, gain: Float)
    /** -1 lijevo .. 0 sredina .. +1 desno (balance). */
    external fun setTrackPan(track: Int, pan: Float)
    external fun setTrackMute(track: Int, mute: Boolean)
    external fun setTrackSolo(track: Int, solo: Boolean)
    external fun setMasterGain(gain: Float)
    external fun setMasterMute(mute: Boolean)

    /**
     * states[48] = stanje slota (EMPTY..QUEUED_STOP).
     * floats[0..7] = nivo trackova, [8..15] = napredak klipa po tracku (0..1), [16] = faza takta (0..1), [17] = master nivo.
     */
    external fun pollState(states: ByteArray, floats: FloatArray)

    // ---- preslušavanje (browser). srcBpm <= 0: BPM nepoznat. targetBpm <= 0: sviraj original brzinom.
    // loopMode kao kod učitavanja (LOOP_AUTO traži poznat srcBpm).
    external fun previewFromBytes(data: ByteArray, srcBpm: Double, targetBpm: Double, loopMode: Int): Int
    external fun previewSamples(data: FloatArray, frames: Int, sampleRate: Int, srcBpm: Double, targetBpm: Double, loopMode: Int): Boolean
    external fun previewStop()

    /** Napredak preslušavanja 0..1, ili -1 ako ne svira. */
    external fun previewProgress(): Float

    external fun sampleRate(): Int
    external fun framesPerBurst(): Int
    external fun bufferSize(): Int

    // ---- Studio (Drums; Keys kasnije). part: 0 = drums, 1 = keys. Svira kroz master, sinhrono sa taktom.
    const val PART_DRUMS = 0
    const val PART_KEYS = 1
    const val TICKS_PER_STEP = 24     // 96 tickova po četvrtini, korak = šesnaestina
    const val STUDIO_STOPPED = 0
    const val STUDIO_QUEUED = 1
    const val STUDIO_PLAYING = 2

    /** Zvuk trake (one-shot) iz WAV/AIFF bajtova. Vraća broj frameova ili 0 ako format nije podržan. */
    external fun studioLoadDrumFromBytes(lane: Int, data: ByteArray): Int
    /** Zvuk trake iz dekodiranih podataka (interleaved stereo). */
    external fun studioLoadDrum(lane: Int, data: FloatArray, frames: Int, sampleRate: Int): Boolean
    external fun studioClearDrum(lane: Int)
    external fun studioSetDrumGain(lane: Int, gain: Float)
    /** notes = [tick, dužina, nota] po noti, velocities = jačina po noti (0..1). */
    external fun studioSetPattern(part: Int, bars: Int, notes: IntArray, velocities: FloatArray)
    // Svaki dio ima svoj transport (PART_DRUMS, PART_KEYS); PART_ALL = svi.
    const val PART_ALL = -1
    external fun studioPlay(part: Int)         // kreće na sljedećem taktu
    external fun studioStop(part: Int)         // odmah
    external fun studioNoteOn(part: Int, note: Int, velocity: Float)
    external fun studioNoteOff(part: Int, note: Int)
    external fun studioTransport(part: Int): Int   // STUDIO_STOPPED / QUEUED / PLAYING (PART_ALL: bilo koji)
    external fun studioPosition(part: Int): Float  // tick u patternu, -1 ako ne svira
    external fun studioLevel(): Float
    /** Metronom (tik na svaku četvrtinu) za odbrojavanje i snimanje u Keys. */
    external fun studioSetClick(on: Boolean)
    /** Pattern dijela renderovan u loop bez šava (tačno N taktova na bpm), interleaved stereo. Prazno ako nema šta. */
    external fun studioRender(part: Int, bpm: Double, sampleRate: Int): FloatArray

    // ---- Sintisajzer (Keys). id = SynthParam iz synth.h (redoslijed mora biti isti), vrijednost u jedinicama
    // parametra (Hz, sekunde, 0..1...); motor je ograniči na opseg.
    external fun synthSetParam(id: Int, value: Float)
    external fun synthParam(id: Int): Float
    // MIDI klavijatura uživo: pitch bend u polutonovima, mod točak 0..1 (otvara filter)
    external fun synthPitchBend(semitones: Float)
    external fun synthModWheel(amount: Float)
}
