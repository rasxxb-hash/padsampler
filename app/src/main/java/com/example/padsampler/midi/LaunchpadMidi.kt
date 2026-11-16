package com.example.padsampler.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport

/**
 * Veza sa Novation Launchpad Mini MK3 u PROGRAMMER MODU (brojevi iz Programmer's Reference PDF-a).
 *
 *  - SysEx zaglavlje: F0 00 20 29 02 0D ; programmer mode: 0E 01 F7
 *  - 8x8 padovi: Note On/Off, nota = (red+1)*10 + (kolona+1), red 0 je DOLJE (11..88)
 *  - gornji red okruglih dugmadi: CC 91..98; desna kolona: CC 89 (gore) .. 19 (dolje)
 *  - LED: Note On (padovi) ili CC (okrugla dugmad); kanal 1 = statično, 2 = treperi (A/B), 3 = pulsira
 *
 * Launchpad izlaže dva MIDI porta: "MIDI" (programmer mode, LED-ovi, padovi) i "DAW".
 * Koristimo samo portove čije ime NE sadrži "DAW"; ako takvih nema, koristimo sve (fallback).
 * Imena portova se ispisuju u Logcat (tag "LaunchpadMidi") radi provjere na uređaju.
 *
 * Aplikacija šalje i MIDI clock (F8, 24 po četvrtini) da treperenje/pulsiranje bude u taktu.
 */
class LaunchpadMidi(context: Context, private val listener: MidiListener) : MidiOut {

    private val tag = "LaunchpadMidi"
    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val handler = Handler(Looper.getMainLooper())

    private var device: MidiDevice? = null
    private var deviceId: Int = -1
    private val sendPorts = CopyOnWriteArrayList<MidiInputPort>()   // mi šaljemo -> Launchpad
    private val receivePorts = mutableListOf<MidiOutputPort>()      // Launchpad šalje -> mi

    @Volatile private var clockBpm = 120.0
    @Volatile private var clockThread: Thread? = null

    override val isConnected: Boolean get() = sendPorts.isNotEmpty()

    /** true tek kad je Launchpad prešao u programmer mode i spreman je primiti LED poruke. */
    @Volatile var isReady: Boolean = false
        private set

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            tryOpen(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == deviceId) {
                closeDevice()
                listener.onConnectionChanged(false, "Launchpad disconnected")
            }
        }
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parse(msg, offset, count)
        }
    }

    // ---------------------------------------------------------------- životni ciklus

    // Sva promjena stanja (otvaranje/zatvaranje) ide preko main niti da nema utrka.
    @Suppress("DEPRECATION")
    override fun start() {
        handler.post {
            manager.registerDeviceCallback(deviceCallback, handler)
            for (info in manager.devices) tryOpen(info)
        }
    }

    override fun stop() {
        handler.post {
            manager.unregisterDeviceCallback(deviceCallback)
            closeDevice()
        }
    }

    private fun isLaunchpad(info: MidiDeviceInfo): Boolean {
        val p = info.properties
        val text = listOfNotNull(
            p.getString(MidiDeviceInfo.PROPERTY_NAME),
            p.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
        ).joinToString(" ").lowercase()
        if ("launchkey" in text || "flkey" in text) return false   // Novation klavijature nisu Launchpad
        return "launchpad" in text || "lpmini" in text || "novation" in text
    }

    private fun isDawPort(name: String?) = name != null && "daw" in name.lowercase()

    private fun tryOpen(info: MidiDeviceInfo) {
        if (device != null || !isLaunchpad(info)) return
        deviceId = info.id
        manager.openDevice(info, { dev ->
            if (dev == null) {
                deviceId = -1
                listener.onConnectionChanged(false, "Launchpad: cannot open device")
                return@openDevice
            }
            device = dev
            openPorts(dev, info)
            enterProgrammerMode()
            startClock()
            // Daj Launchpadu trenutak da promijeni mod prije prvog iscrtavanja LED-ova
            // Programmer mode šaljemo još jednom (prvi put se zna izgubiti dok se uređaj tek pokreće),
            // pa tek onda javljamo da je spreman za LED-ove.
            handler.postDelayed({ if (device != null) enterProgrammerMode() }, 500)
            handler.postDelayed({
                if (device != null) {
                    isReady = true
                    listener.onConnectionChanged(true, describe(info))
                }
            }, 1000)
        }, handler)
    }

    private fun openPorts(dev: MidiDevice, info: MidiDeviceInfo) {
        val ports = info.ports.toList()
        for (p in ports) {
            Log.i(tag, "port: type=${if (p.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) "IN(we send)" else "OUT(we receive)"} " +
                "number=${p.portNumber} name='${p.name}'")
        }
        val inputs = ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
        val outputs = ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }

        // Prednost: portovi bez "DAW" u imenu. Ako ih nema, svi (fallback).
        val useIn = inputs.filter { !isDawPort(it.name) }.ifEmpty { inputs }
        val useOut = outputs.filter { !isDawPort(it.name) }.ifEmpty { outputs }

        for (p in useOut) {
            dev.openOutputPort(p.portNumber)?.let {
                it.connect(receiver); receivePorts += it
                Log.i(tag, "listening on port '${p.name}'")
            }
        }
        for (p in useIn) {
            dev.openInputPort(p.portNumber)?.let {
                sendPorts += it
                Log.i(tag, "sending to port '${p.name}'")
            }
        }
    }

    private fun describe(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Launchpad"

    private fun closeDevice() {
        isReady = false
        stopClock()
        for (p in receivePorts) {
            try { p.disconnect(receiver); p.close() } catch (_: IOException) {}
        }
        for (p in sendPorts) {
            try { p.close() } catch (_: IOException) {}
        }
        receivePorts.clear()
        sendPorts.clear()
        try { device?.close() } catch (_: IOException) {}
        device = null
        deviceId = -1
    }

    // ---------------------------------------------------------------- ulaz (Launchpad -> app)

    private fun parse(msg: ByteArray, offset: Int, count: Int) {
        var i = offset
        val end = offset + count
        while (i < end) {
            val status = msg[i].toInt() and 0xFF
            if (status == 0xF0) {            // SysEx: preskoči do F7
                while (i < end && (msg[i].toInt() and 0xFF) != 0xF7) i++
                i++
                continue
            }
            if (status >= 0xF8) { i++; continue }       // realtime
            if (status < 0x80) { i++; continue }        // zalutali data bajt

            val type = status and 0xF0
            val dataLen = if (type == 0xC0 || type == 0xD0) 1 else 2
            if (i + dataLen >= end) break   // nepotpuna poruka
            val d1 = msg[i + 1].toInt() and 0x7F
            val d2 = if (dataLen == 2) msg[i + 2].toInt() and 0x7F else 0

            when (type) {
                0x90 -> handleNote(d1, down = d2 > 0)
                0x80 -> handleNote(d1, down = false)
                0xB0 -> listener.onControl(d1, down = d2 > 0)
            }
            i += 1 + dataLen
        }
    }

    private fun handleNote(note: Int, down: Boolean) {
        val row = note / 10 - 1
        val col = note % 10 - 1
        if (row in 0..7 && col in 0..7) listener.onPad(row, col, down)
    }

    // ---------------------------------------------------------------- izlaz (app -> Launchpad)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Šalje sirove bajtove (više kompletnih MIDI poruka u jednom paketu je u redu). */
    override fun sendRaw(data: ByteArray, length: Int) {
        if (length <= 0) return
        for (p in sendPorts) {
            try { p.send(data, 0, length) } catch (_: IOException) {}
        }
    }

    private fun enterProgrammerMode() {
        sendRaw(bytes(0xF0, 0x00, 0x20, 0x29, 0x02, 0x0D, 0x0E, 0x01, 0xF7))
    }

    /** Ponovo uključi programmer mode (npr. ako je korisnik pritisnuo dugme na uređaju). */
    fun reenterProgrammerMode() = enterProgrammerMode()

    /** Pad LED. color = indeks Launchpad palete (0 = ugašeno). */
    fun setPad(row: Int, col: Int, color: Int, mode: LedMode = LedMode.STATIC) {
        sendRaw(bytes(mode.noteStatus, (row + 1) * 10 + (col + 1), color))
    }

    /** LED okruglog dugmeta (CC 91..98 gore, 89..19 desno). */
    fun setControl(cc: Int, color: Int, mode: LedMode = LedMode.STATIC) {
        sendRaw(bytes(mode.ccStatus, cc, color))
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
                sendRaw(msg)
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
