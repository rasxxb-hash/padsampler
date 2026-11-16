package com.example.padsampler.midi

/** Događaji sa MIDI klavijature (svi kanali). Zove se iz MIDI niti. */
interface KeyboardListener {
    /** velocity 1..127; 0 = nota otpuštena. */
    fun onKeyboardNote(note: Int, velocity: Int)

    /** -8192..8191 (0 = sredina). */
    fun onKeyboardPitchBend(value: Int)

    /** Control Change (mod točak = CC 1, ostalo za MIDI mapiranje); device = ime uređaja (mapiranje po uređaju). */
    fun onKeyboardControl(device: String, cc: Int, value: Int)

    /** Spojeni uređaji su se promijenili (imena; prazno = ništa nije spojeno). */
    fun onKeyboardConnection(devices: List<String>)
}

/**
 * MIDI klavijatura (ulaz): svi MIDI ulazi osim Launchpada, ili samo izabrani uređaj.
 * Android: android.media.midi (USB class compliant); računar: Java MIDI.
 */
interface MidiKeyboard {
    fun start()
    fun stop()

    /** null = Auto (svi uređaji osim Launchpada), OFF = isključeno, inače ime uređaja iz [availableDevices]. */
    fun select(device: String?)

    /** Imena MIDI ulaza koji se mogu izabrati (bez Launchpada). */
    fun availableDevices(): List<String>

    companion object {
        const val OFF = "\u0000off"

        /** Launchpad ide preko LaunchpadMidi/DesktopMidi; Launchkey/FLkey (Novation klavijature) su klavijature. */
        fun isLaunchpadName(name: String): Boolean {
            val n = name.lowercase()
            if ("launchkey" in n || "flkey" in n) return false
            return "launchpad" in n || "lpmini" in n || "lpx" in n || "lppro" in n
        }
    }
}

/**
 * Raščlanjuje MIDI bajtove (i "running status") u poruke za [KeyboardListener]. Real-time (clock...) i SysEx se
 * preskaču. Jedan parser po ulaznom portu; zove se iz jedne niti.
 */
class KeyboardParser(private val listener: KeyboardListener, private val device: String) {
    private var status = 0
    private var d1 = -1
    private var inSysex = false

    fun feed(bytes: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) byte(bytes[i].toInt() and 0xFF)
    }

    fun byte(b: Int) {
        if (b >= 0xF8) return                       // real-time, može doći bilo gdje
        if (b >= 0x80) {
            when {
                b == 0xF0 -> { inSysex = true; status = 0 }
                b == 0xF7 -> inSysex = false
                b >= 0xF0 -> status = 0                // system common: ignoriše se
                else -> { status = b; inSysex = false }
            }
            d1 = -1
            return
        }
        if (inSysex || status == 0) return
        val type = status and 0xF0
        if (type == 0xC0 || type == 0xD0) return    // program change, channel pressure (jedan bajt)
        if (d1 < 0) {
            d1 = b
            return
        }
        message(type, d1, b)
        d1 = -1                                      // running status: sljedeći bajt je opet d1
    }

    /** Već raščlanjena poruka (Java MIDI ShortMessage). */
    fun message(type: Int, data1: Int, data2: Int) {
        when (type) {
            0x90 -> listener.onKeyboardNote(data1, data2)
            0x80 -> listener.onKeyboardNote(data1, 0)
            0xB0 -> listener.onKeyboardControl(device, data1, data2)
            0xE0 -> listener.onKeyboardPitchBend(((data2 shl 7) or data1) - 8192)
        }
    }
}
