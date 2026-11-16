package com.example.padsampler.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * MIDI klavijatura preko android.media.midi (USB class compliant, bez dozvola). Otvara sve uređaje koji šalju
 * MIDI (izlazni portovi uređaja) osim Launchpada, ili samo izabrani. Stanje se mijenja samo na main niti.
 */
class AndroidKeyboard(context: Context, private val listener: KeyboardListener) : MidiKeyboard {
    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val handler = Handler(Looper.getMainLooper())

    private class Open(val info: MidiDeviceInfo, val device: MidiDevice, val ports: List<MidiOutputPort>)

    private val open = HashMap<Int, Open>()          // id uređaja -> otvoreni portovi
    private val opening = HashSet<Int>()
    @Volatile private var selected: String? = null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = refresh()
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            close(info.id)
            report()
        }
    }

    @Suppress("DEPRECATION")
    override fun start() {
        handler.post {
            manager.registerDeviceCallback(callback, handler)
            refresh()
        }
    }

    override fun stop() {
        handler.post {
            manager.unregisterDeviceCallback(callback)
            for (id in open.keys.toList()) close(id)
        }
    }

    override fun select(device: String?) {
        selected = device?.ifBlank { null }
        handler.post { refresh() }
    }

    @Suppress("DEPRECATION")
    override fun availableDevices(): List<String> =
        manager.devices.filter { isKeyboard(it) }.map { nameOf(it) }.distinct().sortedBy { it.lowercase() }

    private fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI device ${info.id}"
    }

    private fun isKeyboard(info: MidiDeviceInfo): Boolean {
        if (info.outputPortCount == 0) return false
        val p = info.properties
        val text = listOfNotNull(
            p.getString(MidiDeviceInfo.PROPERTY_NAME),
            p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        ).joinToString(" ")
        return !MidiKeyboard.isLaunchpadName(text)
    }

    private fun wanted(info: MidiDeviceInfo): Boolean {
        if (!isKeyboard(info)) return false
        val sel = selected ?: return true
        return sel != MidiKeyboard.OFF && nameOf(info) == sel
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        val devices = manager.devices
        for ((id, o) in open.toList()) if (devices.none { it.id == id } || !wanted(o.info)) close(id)
        for (info in devices) {
            if (!wanted(info) || info.id in open || info.id in opening) continue
            opening += info.id
            manager.openDevice(info, { dev ->
                opening -= info.id
                if (dev == null) return@openDevice
                if (!wanted(info)) {
                    try { dev.close() } catch (_: Exception) {}
                    return@openDevice
                }
                val ports = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT && "daw" !in (it.name ?: "").lowercase() }.mapNotNull { pi ->
                    dev.openOutputPort(pi.portNumber)?.also { port ->
                        val parser = KeyboardParser(listener, nameOf(info))   // jedan parser po portu (running status)
                        port.connect(object : MidiReceiver() {
                            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) =
                                parser.feed(msg, offset, count)
                        })
                    }
                }
                open[info.id] = Open(info, dev, ports)
                report()
            }, handler)
        }
        report()
    }

    private fun close(id: Int) {
        val o = open.remove(id) ?: return
        for (p in o.ports) try { p.close() } catch (_: Exception) {}
        try { o.device.close() } catch (_: Exception) {}
    }

    private var lastReported: List<String>? = null

    private fun report() {
        val now = open.values.map { nameOf(it.info) }.distinct()
        if (now != lastReported) {
            lastReported = now
            listener.onKeyboardConnection(now)
        }
    }
}
