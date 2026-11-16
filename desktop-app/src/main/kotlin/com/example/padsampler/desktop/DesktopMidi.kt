package com.example.padsampler.desktop

import com.example.padsampler.midi.MidiListener
import com.example.padsampler.midi.MidiOut
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer
import javax.sound.midi.Sequencer
import javax.sound.midi.SysexMessage

/**
 * Launchpad preko Java MIDI-ja (isto ponašanje kao Android LaunchpadMidi):
 * koriste se svi portovi čije ime sadrži Launchpad/LPMini/Novation, a ne sadrži "DAW".
 * Na Windowsu su to "LPMiniMK3 MIDI" i "MIDIIN2/MIDIOUT2 (LPMiniMK3 MIDI)".
 * Šalje se programmer mode (SysEx) i MIDI clock; ulazne note/CC idu u listener.
 */
class DesktopMidi(private val listener: MidiListener) : MidiOut {
    private val sendPorts = CopyOnWriteArrayList<Receiver>()
    private val devices = CopyOnWriteArrayList<MidiDevice>()
    private val connectedNames = mutableSetOf<String>()

    @Volatile private var running = false
    @Volatile private var clockBpm = 120.0
    @Volatile private var clockThread: Thread? = null
    private var connectThread: Thread? = null

    override val isConnected: Boolean get() = sendPorts.isNotEmpty()

    override fun start() {
        if (running) return
        running = true
        connectThread = Thread({ connectLoop() }, "launchpad-connect").also { it.isDaemon = true; it.start() }
    }

    override fun stop() {
        running = false
        connectThread?.interrupt()
        closeAll()
    }

    // ---------------------------------------------------------------- povezivanje

    /**
     * Izabrani kontroler (ime uređaja kao u listi availableDevices()); null ili prazno = automatski (Launchpad).
     * Mijenja se iz podešavanja; nova veza se uspostavi u connectLoop.
     */
    @Volatile var selectedDevice: String? = null
        private set
    @Volatile private var reconnect = false

    fun select(device: String?) {
        selectedDevice = device?.ifBlank { null }
        reconnect = true   // connectLoop ga preuzme u roku od 1 s (interrupt bi ugasio petlju)
    }

    private fun isWanted(portName: String): Boolean {
        val sel = selectedDevice ?: return isLaunchpadName(portName)
        return baseName(portName) == sel && "daw" !in portName.lowercase()
    }

    private fun isLaunchpadName(name: String): Boolean {
        val n = name.lowercase()
        if ("launchkey" in n || "flkey" in n) return false   // Novation klavijature nisu Launchpad
        return ("launchpad" in n || "lpmini" in n || "novation" in n) && "daw" !in n
    }

    companion object {
        private val wrapped = Regex("""^MIDI(?:IN|OUT)\d+\s*\((.*)\)$""")

        /** "MIDIIN2 (LPMiniMK3 MIDI)" -> "LPMiniMK3 MIDI": svi portovi jednog uređaja imaju isto ime. */
        fun baseName(port: String): String = wrapped.find(port.trim())?.groupValues?.get(1)?.trim() ?: port.trim()

        /** MIDI uređaji (bez Javinog sintisajzera/sekvencera i "DAW" portova), za izbor u podešavanjima. */
        fun availableDevices(): List<String> =
            MidiSystem.getMidiDeviceInfo().mapNotNull { info ->
                val dev = try { MidiSystem.getMidiDevice(info) } catch (_: Exception) { null }
                if (dev == null || dev is Synthesizer || dev is Sequencer) null
                else if ("daw" in info.name.lowercase()) null
                else baseName(info.name)
            }.distinct().sortedBy { it.lowercase() }
    }

    private fun connectLoop() {
        while (running) {
            try {
                if (reconnect) {
                    reconnect = false
                    if (sendPorts.isNotEmpty() || devices.isNotEmpty()) {
                        closeAll()
                        listener.onConnectionChanged(false, "MIDI controller changed")
                    }
                }
                if (sendPorts.isEmpty()) tryConnect() else if (!stillPresent()) {
                    closeAll()
                    listener.onConnectionChanged(false, "Launchpad disconnected")
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
        }
    }

    private fun stillPresent(): Boolean {
        val now = MidiSystem.getMidiDeviceInfo().map { it.name }.toSet()
        return connectedNames.all { it in now }
    }

    private fun tryConnect() {
        val found = mutableListOf<MidiDevice>()
        for (info in MidiSystem.getMidiDeviceInfo()) {
            if (!isWanted(info.name)) continue
            val dev = try { MidiSystem.getMidiDevice(info) } catch (_: Exception) { continue }
            if (dev is Synthesizer || dev is Sequencer) continue
            found += dev
        }
        if (found.isEmpty()) return

        var description = "Launchpad"
        for (dev in found) {
            try {
                if (!dev.isOpen) dev.open()
                devices += dev
                connectedNames += dev.deviceInfo.name
                description = dev.deviceInfo.name
                if (dev.maxReceivers != 0) sendPorts += dev.receiver
                if (dev.maxTransmitters != 0) dev.transmitter.receiver = InReceiver()
            } catch (_: MidiUnavailableException) {
                // port zauzet (npr. drugi program) ili nije ulaz/izlaz: preskoči
            } catch (_: Exception) {
            }
        }
        if (sendPorts.isEmpty()) {
            closeAll()
            return
        }
        enterProgrammerMode()
        startClock()
        // Isto kao na Androidu: programmer mode još jednom nakon 0,5 s, spremno nakon 1 s
        Thread.sleep(500)
        if (sendPorts.isNotEmpty()) enterProgrammerMode()
        Thread.sleep(500)
        if (sendPorts.isNotEmpty()) listener.onConnectionChanged(true, description)
    }

    private fun closeAll() {
        stopClock()
        sendPorts.clear()
        for (d in devices) {
            try { d.close() } catch (_: Exception) {}
        }
        devices.clear()
        connectedNames.clear()
    }

    // ---------------------------------------------------------------- ulaz (Launchpad -> app)

    private inner class InReceiver : Receiver {
        override fun send(message: MidiMessage, timeStamp: Long) {
            if (message !is ShortMessage) return
            val d1 = message.data1
            val d2 = message.data2
            when (message.command) {
                ShortMessage.NOTE_ON -> handleNote(d1, down = d2 > 0)
                ShortMessage.NOTE_OFF -> handleNote(d1, down = false)
                ShortMessage.CONTROL_CHANGE -> listener.onControl(d1, down = d2 > 0)
            }
        }

        override fun close() {}
    }

    private fun handleNote(note: Int, down: Boolean) {
        val row = note / 10 - 1
        val col = note % 10 - 1
        if (row in 0..7 && col in 0..7) listener.onPad(row, col, down)
    }

    // ---------------------------------------------------------------- izlaz (app -> Launchpad)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun enterProgrammerMode() {
        sendRaw(bytes(0xF0, 0x00, 0x20, 0x29, 0x02, 0x0D, 0x0E, 0x01, 0xF7))
    }

    /** Razbija niz bajtova na MIDI poruke (SysEx, realtime, 2- i 3-bajtne) i šalje ih svim portovima. */
    override fun sendRaw(data: ByteArray, length: Int) {
        if (length <= 0 || sendPorts.isEmpty()) return
        var i = 0
        while (i < length) {
            val s = data[i].toInt() and 0xFF
            val msg: MidiMessage?
            when {
                s == 0xF0 -> {
                    var j = i
                    while (j < length && (data[j].toInt() and 0xFF) != 0xF7) j++
                    val end = minOf(j + 1, length)
                    msg = SysexMessage(data.copyOfRange(i, end), end - i)
                    i = end
                }
                s >= 0xF8 -> {
                    msg = ShortMessage().also { it.setMessage(s) }
                    i++
                }
                s >= 0x80 -> {
                    val type = s and 0xF0
                    val len = if (type == 0xC0 || type == 0xD0) 1 else 2
                    if (i + len >= length) break   // nepotpuna poruka
                    val d1 = data[i + 1].toInt() and 0x7F
                    val d2 = if (len == 2) data[i + 2].toInt() and 0x7F else 0
                    msg = ShortMessage().also { it.setMessage(type, s and 0x0F, d1, d2) }
                    i += 1 + len
                }
                else -> {
                    msg = null
                    i++
                }
            }
            if (msg != null) {
                for (r in sendPorts) {
                    try { r.send(msg, -1L) } catch (_: Exception) {}
                }
            }
        }
    }

    // ---------------------------------------------------------------- MIDI clock

    override fun setClockBpm(bpm: Double) {
        clockBpm = bpm.coerceIn(20.0, 300.0)
    }

    private fun startClock() {
        stopClock()
        val t = Thread({
            val msg = bytes(0xF8)
            var next = System.nanoTime()
            while (!Thread.currentThread().isInterrupted && sendPorts.isNotEmpty()) {
                val interval = (60_000_000_000.0 / (clockBpm * 24.0)).toLong()
                next += interval
                val wait = next - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait) else if (wait < -200_000_000L) next = System.nanoTime()
                if (Thread.currentThread().isInterrupted) break
                sendRaw(msg, 1)
            }
        }, "launchpad-clock")
        t.isDaemon = true
        clockThread = t
        t.start()
    }

    private fun stopClock() {
        clockThread?.interrupt()
        clockThread = null
    }
}
