package com.example.padsampler.midi

/** Događaji koje Launchpad veza javlja aplikaciji. */
interface MidiListener {
    /** Zove se iz MIDI niti. red 0 = dolje. */
    fun onPad(row: Int, col: Int, down: Boolean)

    /** Okrugla dugmad (CC). Zove se iz MIDI niti. */
    fun onControl(cc: Int, down: Boolean)

    /** Kad je Launchpad spreman za LED poruke (ili se isključio: connected = false). Main nit. */
    fun onConnectionChanged(connected: Boolean, description: String)
}

enum class LedMode(val noteStatus: Int, val ccStatus: Int) {
    STATIC(0x90, 0xB0),
    FLASHING(0x91, 0xB1),
    PULSING(0x92, 0xB2)
}

/**
 * Veza prema Launchpadu, onoliko koliko zajednički kod treba. Android implementacija je LaunchpadMidi
 * (android.media.midi), a desktop će imati svoju (Java MIDI).
 */
interface MidiOut {
    val isConnected: Boolean
    fun start()
    fun stop()
    /** Šalje sirove bajtove (više kompletnih MIDI poruka u jednom paketu je u redu). */
    fun sendRaw(data: ByteArray, length: Int = data.size)
    fun setClockBpm(bpm: Double)
}

/**
 * Indeksi boja iz standardne Launchpad palete. Od 12. 11. 2026: glavna boja MINT (kao aplikacija), bijela za
 * isticanje; STOP / SOLO / MUTE = tamno crvena / plava / zlatno žuta (pune boje; pastelne izgledaju isprano).
 */
object LaunchpadColors {
    const val OFF = 0
    const val GREY = 1
    const val WHITE = 3
    const val MINT = 33
    const val MINT_DIM = 34
    const val RED = 120          // tamno crvena (ne jarka 5)
    const val RED_DIM = 121
    const val BLUE = 41
    const val BLUE_DIM = 42
    const val YELLOW = 109       // zlatno žuta
    const val YELLOW_DIM = 14
    const val GREEN = MINT       // "uključeno" (Play, ima klipova) = mint
}
