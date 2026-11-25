package com.example.padsampler.desktop

import com.example.padsampler.midi.KeyboardListener
import com.example.padsampler.midi.KeyboardParser
import com.example.padsampler.midi.MidiKeyboard
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer

/**
 * MIDI klavijatura preko Java MIDI-ja. Svake sekunde provjerava uređaje: otvara MIDI ulaze (uređaje koji šalju)
 * osim Launchpada i uređaja koji koristi Launchpad veza ([takenByLaunchpad]), zatvara one koji su nestali.
 */
class DesktopKeyboard(
    private val listener: KeyboardListener,
    private val takenByLaunchpad: (String) -> Boolean
) : MidiKeyboard {
    private val open = LinkedHashMap<String, MidiDevice>()   // puno ime porta -> uređaj
    @Volatile private var selected: String? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastReported: List<String>? = null

    override fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "keyboard-connect").also { it.isDaemon = true; it.start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        synchronized(open) {
            for (d in open.values) try { d.close() } catch (_: Exception) {}
            open.clear()
        }
    }

    override fun select(device: String?) {
        selected = device?.ifBlank { null }
    }

    override fun availableDevices(): List<String> =
        inputs().map { DesktopMidi.baseName(DesktopMidi.portName(it.first)) }
            .filter { !MidiKeyboard.isLaunchpadName(it) && !takenByLaunchpad(it) }
            .distinct().sortedBy { it.lowercase() }

    /** Uređaji koji šalju MIDI (bez Javinog sintisajzera/sekvencera i "DAW" portova). */
    private fun inputs(): List<Pair<javax.sound.midi.MidiDevice.Info, MidiDevice>> =
        MidiSystem.getMidiDeviceInfo().mapNotNull { info ->
            if ("daw" in DesktopMidi.portName(info).lowercase()) return@mapNotNull null
            val dev = try { MidiSystem.getMidiDevice(info) } catch (_: Exception) { null } ?: return@mapNotNull null
            if (dev is Synthesizer || dev is Sequencer || dev.maxTransmitters == 0) null else info to dev
        }

    private fun wanted(portName: String): Boolean {
        val base = DesktopMidi.baseName(portName)
        if (MidiKeyboard.isLaunchpadName(base) || takenByLaunchpad(base)) return false
        val sel = selected ?: return true
        return sel != MidiKeyboard.OFF && base == sel
    }

    private fun loop() {
        while (running) {
            try {
                refresh()
            } catch (_: Exception) {
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
        }
    }

    private fun refresh() {
        val present = inputs()
        val names = present.map { DesktopMidi.portName(it.first) }.toSet()
        synchronized(open) {
            // zatvori nestale i one koji više nisu izabrani
            val it = open.entries.iterator()
            while (it.hasNext()) {
                val (name, dev) = it.next()
                if (name !in names || !wanted(name)) {
                    try { dev.close() } catch (_: Exception) {}
                    it.remove()
                }
            }
            for ((info, dev) in present) {
                val name = DesktopMidi.portName(info)
                if (name in open || !wanted(name)) continue
                try {
                    if (!dev.isOpen) dev.open()
                    dev.transmitter.receiver = In(KeyboardParser(listener, DesktopMidi.baseName(name)))
                    open[name] = dev
                } catch (_: Exception) {
                    try { dev.close() } catch (_: Exception) {}   // zauzet (drugi program) ili nije ulaz
                }
            }
        }
        val now = synchronized(open) { open.keys.map { DesktopMidi.baseName(it) }.distinct() }
        if (now != lastReported) {
            lastReported = now
            listener.onKeyboardConnection(now)
        }
    }

    private class In(private val parser: KeyboardParser) : Receiver {
        override fun send(message: MidiMessage, timeStamp: Long) {
            if (message is ShortMessage) parser.message(message.command, message.data1, message.data2)
        }

        override fun close() {}
    }
}
