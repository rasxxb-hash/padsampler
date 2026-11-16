package com.example.padsampler.data

import com.example.padsampler.model.SessionData
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class SessionInfo(val dirName: String, val name: String, val savedAt: Long, val padCount: Int)

/**
 * Imenovane sesije u privatnom folderu aplikacije: sessions/<ime>/ (pads.json + samples/).
 * Svaka sesija je samostalna kopija (semplovi se kopiraju), pa promjene radne sesije ne diraju spremljene.
 */
class SessionStore(baseDir: File) {
    private val root = File(baseDir, "sessions").apply { mkdirs() }

    @Synchronized
    fun list(): List<SessionInfo> {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val json = File(dir, "pads.json")
            if (!json.exists()) return@mapNotNull null
            val data = PadStore(dir).load()
            SessionInfo(dir.name, data.name.ifBlank { dir.name }, json.lastModified(), data.pads.size)
        }.sortedByDescending { it.savedAt }
    }

    /** Sprema radno stanje pod imenom. Ako sesija s istim imenom postoji, zamjenjuje se. */
    @Synchronized
    fun save(name: String, data: SessionData, working: PadStore): SessionInfo {
        val clean = name.trim().ifEmpty { "Session" }
        val existing = list().firstOrNull { it.name.equals(clean, ignoreCase = true) }
        val dirName = existing?.dirName ?: freeDirName(clean)

        val tmp = File(root, ".tmp_$dirName")
        tmp.deleteRecursively()
        val store = PadStore(tmp)
        // semplovi padova + zvukovi Drums (i kitova printanih padova)
        for (name in data.referencedFiles()) {
            val src = working.file(name)
            if (src.exists()) src.copyTo(store.file(name), overwrite = true)
        }
        store.save(data.copy(name = clean))

        val target = File(root, dirName)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) {
            tmp.copyRecursively(target, overwrite = true)
            tmp.deleteRecursively()
        }
        return SessionInfo(dirName, clean, File(target, "pads.json").lastModified(), data.pads.size)
    }

    /** Podaci sesije i njen PadStore (odakle se čitaju semplovi). */
    @Synchronized
    fun open(dirName: String): Pair<SessionData, PadStore>? {
        val dir = File(root, dirName)
        if (!File(dir, "pads.json").exists()) return null
        val store = PadStore(dir)
        return store.load().let { it to store }
    }

    /** Folder sesije u privatnom prostoru (za izvoz). */
    fun dir(dirName: String): File = File(root, dirName)

    /**
     * Uvoz sesije iz foldera (pads.json + samples/). Nikad ne prepisuje postojeću: ako ime već postoji,
     * dodaje se " (2)", " (3)"... Vraća null ako folder nije ispravna sesija.
     */
    @Synchronized
    fun importDir(src: File): SessionInfo? {
        if (!File(src, "pads.json").exists()) return null
        val srcStore = PadStore(src)
        val data = srcStore.load()
        if (data.pads.isEmpty() && data.name.isBlank()) return null
        val base = data.name.ifBlank { "Imported session" }
        val names = list().map { it.name.lowercase() }.toSet()
        var name = base
        var n = 2
        while (name.lowercase() in names) name = "$base (${n++})"
        return save(name, data, srcStore)
    }

    /**
     * Izvoz u jedan fajl (.padsession): ZIP sa pads.json i samples/<folder>/<fajl> (folder = labela pada, npr. Drums;
     * zvukovi kita u Drums/Kit). Isti fajl se otvara na tabletu i računaru.
     * Semplovi se ne sažimaju (audio se slabo sažima, a ovako je brže).
     */
    @Synchronized
    fun exportZip(dirName: String, out: OutputStream): Int {
        val dir = File(root, dirName)
        val json = File(dir, "pads.json")
        if (!json.exists()) throw IllegalStateException("session not found")
        var count = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("pads.json"))
            json.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            // podfolderi po labeli (Drums, Bass...), zvukovi kita u Drums/Kit; uvoz ih vraća u jedan folder
            val folders = PadStore(dir).load().exportFolders()
            File(dir, "samples").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
                zip.putNextEntry(ZipEntry("samples/" + (folders[f.name] ?: "Samples") + "/" + f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    /**
     * Uvoz iz .padsession (ZIP). Uzimaju se samo pads.json i samples/<ime> (i ako je sve u jednom folderu unutar
     * ZIP-a); ništa se ne može upisati izvan privremenog foldera. Ime kao kod importDir (nikad ne prepisuje).
     */
    @Synchronized
    fun importZip(input: InputStream, tmp: File): SessionInfo? {
        tmp.deleteRecursively()
        val samples = File(tmp, "samples").apply { mkdirs() }
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                    val file = parts.lastOrNull() ?: continue
                    if (file == "." || file == "..") continue
                    // samples/ je u korijenu ZIP-a ili u jednom folderu; ispod njega do 2 podfoldera (Drums/Kit)
                    val si = parts.indexOf("samples")
                    val target = when {
                        file == "pads.json" && parts.size <= 2 -> File(tmp, "pads.json")
                        si in 0..1 && parts.size - si in 2..4 && parts.subList(si + 1, parts.size - 1).none { it == ".." } ->
                            File(samples, file)
                        else -> null
                    } ?: continue
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            return importDir(tmp)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Synchronized
    fun delete(dirName: String) {
        if (dirName.isBlank() || dirName.startsWith(".")) return
        File(root, dirName).deleteRecursively()
    }

    private fun freeDirName(name: String): String {
        val base = name.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            .trim('_').take(40).ifEmpty { "session" }
        var candidate = base
        var n = 2
        while (File(root, candidate).exists()) candidate = "${base}_${n++}"
        return candidate
    }
}
