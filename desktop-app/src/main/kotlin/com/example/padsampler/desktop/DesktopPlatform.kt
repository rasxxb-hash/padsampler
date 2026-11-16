package com.example.padsampler.desktop

import com.example.padsampler.midi.MidiListener
import com.example.padsampler.midi.KeyboardListener
import com.example.padsampler.midi.MidiKeyboard
import com.example.padsampler.midi.MidiOut
import com.example.padsampler.platform.DecodedAudio
import com.example.padsampler.platform.DocChild
import com.example.padsampler.platform.DocFiles
import com.example.padsampler.platform.Platform
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/** Windows implementacija Platform-a: obični folderi i putanje, Java MIDI. */
class DesktopPlatform : Platform {
    override val filesDir: File = (System.getenv("APPDATA")?.let { File(it, "PadSampler") }
        ?: File(System.getProperty("user.home"), ".padsampler")).also { it.mkdirs() }
    override val cacheDir: File = File(System.getProperty("java.io.tmpdir"), "PadSampler").also { it.mkdirs() }
    override val docs: DocFiles = DesktopDocFiles()

    private val settingsFile = File(filesDir, "settings.properties")
    private val settings = Properties().also {
        try { if (settingsFile.isFile) settingsFile.inputStream().use { s -> it.load(s) } } catch (_: Exception) {}
    }

    @Synchronized
    override fun getInt(key: String, default: Int): Int = settings.getProperty(key)?.toIntOrNull() ?: default

    @Synchronized
    override fun putInt(key: String, value: Int) = putString(key, value.toString())

    @Synchronized
    override fun getString(key: String, default: String): String = settings.getProperty(key) ?: default

    @Synchronized
    override fun putString(key: String, value: String) {
        settings.setProperty(key, value)
        try { settingsFile.outputStream().use { settings.store(it, "PadSampler") } } catch (_: Exception) {}
    }

    override fun acquireLinkLock(): AutoCloseable? = null

    // mp3 dekodira DLL (dr_mp3) direktno iz bajtova, pa ovdje ne treba ništa.
    override fun decodeAudio(path: String): DecodedAudio? = null

    /** Launchpad veza (za izbor kontrolera u podešavanjima). */
    var midi: DesktopMidi? = null
        private set

    override fun createMidi(listener: MidiListener): MidiOut =
        DesktopMidi(listener).also {
            it.select(getString(DesktopSettings.MIDI_DEVICE))
            midi = it
        }

    // Klavijatura ne otvara uređaj koji koristi Launchpad veza (Windows dozvoljava samo jedno otvaranje)
    override fun createKeyboard(listener: KeyboardListener): MidiKeyboard =
        DesktopKeyboard(listener) { name -> midi?.selectedDevice?.let { it == name } ?: false }
}

/**
 * Folderi kao putanje. Id dokumenta je puna putanja s kosim crtama (C:/Muzika/Paket/kick.wav),
 * pa Library može pravi naziv foldera izvući isto kao na Androidu.
 */
private class DesktopDocFiles : DocFiles {
    private fun norm(f: File) = f.absolutePath.replace('\\', '/')

    override fun displayName(ref: String): String? = File(ref).name.ifEmpty { null }

    override fun documentPath(ref: String): String? = ref.replace('\\', '/')

    override fun openInput(ref: String): InputStream? = File(ref).takeIf { it.isFile }?.inputStream()

    override fun openOutputFile(ref: String): OutputStream? = FileOutputStream(File(ref))

    override fun treeRootId(tree: String): String? = File(tree).takeIf { it.isDirectory }?.let { norm(it) }

    override fun children(tree: String, docId: String): List<DocChild> =
        File(docId).listFiles()?.map {
            DocChild(norm(it), it.name, it.isDirectory, if (it.isFile) it.length() else 0L)
        } ?: emptyList()

    override fun childRef(tree: String, docId: String): String = docId

    override fun createChild(tree: String, parentId: String, name: String, dir: Boolean): String? {
        val f = File(parentId, name)
        return try {
            if (dir) f.mkdirs() else f.createNewFile()
            norm(f)
        } catch (e: Exception) {
            null
        }
    }

    override fun openOutput(tree: String, docId: String): OutputStream? =
        try { FileOutputStream(docId) } catch (e: Exception) { null }

    override fun rememberTree(tree: String) {}
    override fun forgetTree(tree: String) {}
}
