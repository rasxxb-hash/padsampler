package com.example.padsampler.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.padsampler.audio.MediaDecoder
import com.example.padsampler.midi.LaunchpadMidi
import com.example.padsampler.midi.MidiListener
import com.example.padsampler.midi.MidiOut
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Android implementacija Platform-a (SAF, SharedPreferences, MediaCodec, Android MIDI). */
class AndroidPlatform(private val app: Application) : Platform {
    private val prefs = app.getSharedPreferences("padsampler", Context.MODE_PRIVATE)

    override val filesDir: File get() = app.filesDir
    override val cacheDir: File get() = app.cacheDir
    override val docs: DocFiles = AndroidDocFiles(app)

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun acquireLinkLock(): AutoCloseable? {
        // Bez multicast lock-a Android filtrira Link-ove pakete na Wi-Fi mreži
        val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val lock = wm.createMulticastLock("padsampler-link").apply { setReferenceCounted(false); acquire() }
        return AutoCloseable { lock.release() }
    }

    override fun decodeAudio(path: String): DecodedAudio? {
        val d = MediaDecoder.decode(path) ?: return null
        return DecodedAudio(d.data, d.frames, d.sampleRate)
    }

    override fun createMidi(listener: MidiListener): MidiOut = LaunchpadMidi(app, listener)

    override fun createKeyboard(listener: com.example.padsampler.midi.KeyboardListener): com.example.padsampler.midi.MidiKeyboard =
        com.example.padsampler.midi.AndroidKeyboard(app, listener)
}

private class AndroidDocFiles(private val app: Application) : DocFiles {
    private val cr get() = app.contentResolver

    override fun displayName(ref: String): String? =
        cr.query(Uri.parse(ref), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun documentPath(ref: String): String? = try {
        val uri = Uri.parse(ref)
        if (DocumentsContract.isDocumentUri(app, uri)) DocumentsContract.getDocumentId(uri) else uri.path
    } catch (e: Exception) {
        null
    }

    override fun openInput(ref: String): InputStream? = cr.openInputStream(Uri.parse(ref))

    override fun openOutputFile(ref: String): OutputStream? = cr.openOutputStream(Uri.parse(ref), "wt")

    override fun treeRootId(tree: String): String? = try {
        DocumentsContract.getTreeDocumentId(Uri.parse(tree))
    } catch (e: Exception) {
        null
    }

    override fun children(tree: String, docId: String): List<DocChild> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(tree), docId)
        val out = ArrayList<DocChild>()
        cr.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                out.add(DocChild(id, name, isDir, if (c.isNull(3)) 0L else c.getLong(3)))
            }
        }
        return out
    }

    override fun childRef(tree: String, docId: String): String =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), docId).toString()

    override fun createChild(tree: String, parentId: String, name: String, dir: Boolean): String? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), parentId)
        val mime = if (dir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        val created = DocumentsContract.createDocument(cr, parent, mime, name)
        return created?.let { DocumentsContract.getDocumentId(it) }
    }

    override fun openOutput(tree: String, docId: String): OutputStream? =
        cr.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), docId), "wt")

    override fun rememberTree(tree: String) {
        try {
            cr.takePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    override fun forgetTree(tree: String) {
        try {
            cr.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }
}
