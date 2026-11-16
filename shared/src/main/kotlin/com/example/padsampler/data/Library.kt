package com.example.padsampler.data

import com.example.padsampler.platform.DocFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Jedan izabrani glavni folder sa semplovima (SAF stablo, dozvola ostaje trajno). */
data class LibRoot(val id: Int, val uri: String, val name: String)

/** Jedan sempl u indeksu. dir = putanja foldera počevši od imena glavnog foldera, npr. "Samples/Pack 124 BPM/Drums". */
class LibEntry(
    val rootId: Int,
    val docId: String,
    val dir: String,
    val name: String,
    val size: Long
) {
    /** Originalni BPM iz imena foldera ili fajla (null ako ga nema). */
    val bpm: Double? = BpmParser.fromPath(dir, name)

    /** Tonalitet iz imena foldera ili fajla, npr. "Am" (null ako ga nema). Samo prikaz. */
    val key: String? = KeyParser.fromPath(dir, name)

    /** Mala slova, za pretragu. */
    val hay: String = (dir + "/" + name).lowercase()
}

data class ScanState(val running: Boolean = false, val found: Int = 0, val error: String? = null)

/**
 * Indeks biblioteke semplova. Glavni folderi se biraju jednom (SAF), indeks se pravi skeniranjem i sprema u fajl,
 * a drži se u memoriji pa pretraga hiljada semplova traje milisekunde.
 */
class Library(private val docs: DocFiles, baseDir: File) {
    private val rootsFile = File(baseDir, "library_roots.json")
    private val indexFile = File(baseDir, "library_index.tsv")

    private val _roots = MutableStateFlow<List<LibRoot>>(emptyList())
    val roots: StateFlow<List<LibRoot>> = _roots

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    @Volatile var entries: List<LibEntry> = emptyList()
        private set

    private var nextId = 1
    private val audioExt = setOf("wav", "aif", "aiff", "aifc", "mp3", "flac", "ogg")

    // ---------------------------------------------------------------- učitavanje / spremanje

    @Synchronized
    fun load() {
        try {
            if (rootsFile.exists()) {
                val o = JSONObject(rootsFile.readText())
                nextId = o.optInt("nextId", 1)
                val arr = o.optJSONArray("roots") ?: JSONArray()
                _roots.value = (0 until arr.length()).map {
                    val r = arr.getJSONObject(it)
                    LibRoot(r.getInt("id"), r.getString("uri"), r.getString("name"))
                }
            }
            if (indexFile.exists()) {
                val list = ArrayList<LibEntry>()
                indexFile.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val p = line.split('\t')
                        if (p.size < 5) continue
                        list.add(LibEntry(p[0].toIntOrNull() ?: continue, p[1], p[2], p[3], p[4].toLongOrNull() ?: 0L))
                    }
                }
                // unosi foldera koji više nisu u biblioteci (uklonjen dok je skeniranje trajalo) se odbacuju
                val ids = _roots.value.map { it.id }.toSet()
                val clean = list.filter { it.rootId in ids }
                entries = clean
                _count.value = clean.size
                if (clean.size != list.size) saveIndex(clean)
            }
        } catch (e: Exception) {
            // oštećen indeks: počni ispočetka, korisnik može ponovo skenirati
            entries = emptyList()
            _count.value = 0
        }
    }

    @Synchronized
    private fun saveRoots() {
        val arr = JSONArray()
        for (r in _roots.value) arr.put(JSONObject().put("id", r.id).put("uri", r.uri).put("name", r.name))
        rootsFile.writeText(JSONObject().put("nextId", nextId).put("roots", arr).toString())
    }

    @Synchronized
    private fun saveIndex(list: List<LibEntry>) {
        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            for (e in list) {
                w.write("${e.rootId}\t${clean(e.docId)}\t${clean(e.dir)}\t${clean(e.name)}\t${e.size}\n")
            }
        }
        // Windows: renameTo ne prepisuje postojeći fajl
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    // ---------------------------------------------------------------- folderi

    /** Dodaje glavni folder (tree iz izbora foldera) i trajno pamti dozvolu. Vraća id ili -1. */
    @Synchronized
    fun addRoot(tree: String): Int {
        docs.rememberTree(tree)
        val existing = _roots.value.firstOrNull { it.uri == tree }
        if (existing != null) return existing.id
        val treeId = docs.treeRootId(tree) ?: return -1
        val name = treeId.substringAfterLast('/').substringAfter(':').ifBlank { "Library" }
        val root = LibRoot(nextId++, tree, name)
        _roots.value = _roots.value + root
        saveRoots()
        return root.id
    }

    @Synchronized
    fun removeRoot(id: Int) {
        val root = _roots.value.firstOrNull { it.id == id } ?: return
        docs.forgetTree(root.uri)
        _roots.value = _roots.value.filter { it.id != id }
        saveRoots()
        val list = entries.filter { it.rootId != id }
        entries = list
        _count.value = list.size
        saveIndex(list)
    }

    /** Referenca na dokument sempla (za čitanje preko DocFiles). */
    fun documentRef(entry: LibEntry): String? {
        val root = _roots.value.firstOrNull { it.id == entry.rootId } ?: return null
        return docs.childRef(root.uri, entry.docId)
    }

    // ---------------------------------------------------------------- skeniranje (zvati iz pozadinske niti)

    /** Ponovo skenira jedan glavni folder i zamjenjuje njegove unose u indeksu. */
    fun scan(rootId: Int) {
        val root = _roots.value.firstOrNull { it.id == rootId } ?: return
        _scan.value = ScanState(running = true, found = 0)
        try {
            val tree = root.uri
            val found = ArrayList<LibEntry>()
            val stack = ArrayDeque<Pair<String, String>>()   // (documentId, putanja)
            stack.addLast((docs.treeRootId(tree) ?: throw IllegalStateException("Invalid folder")) to root.name)
            while (stack.isNotEmpty()) {
                val (docId, path) = stack.removeLast()
                for (c in docs.children(tree, docId)) {
                    if (c.isDir) {
                        stack.addLast(c.id to "$path/${c.name}")
                    } else if (c.name.substringAfterLast('.', "").lowercase() in audioExt) {
                        found.add(LibEntry(rootId, c.id, path, c.name, c.size))
                        if (found.size % 200 == 0) _scan.value = ScanState(running = true, found = found.size)
                    }
                }
            }
            found.sortWith(compareBy<LibEntry>({ it.dir.lowercase() }, { it.name.lowercase() }))
            commitScan(rootId, found)
            _scan.value = ScanState(running = false, found = found.size)
        } catch (e: Exception) {
            _scan.value = ScanState(running = false, found = 0, error = e.message ?: "Scan failed")
        }
    }

    /** Rezultat skeniranja u indeks; ako je folder u međuvremenu uklonjen, rezultat se odbacuje. */
    @Synchronized
    private fun commitScan(rootId: Int, found: List<LibEntry>) {
        val ids = _roots.value.map { it.id }.toSet()
        val list = entries.filter { it.rootId != rootId && it.rootId in ids } + (if (rootId in ids) found else emptyList())
        entries = list
        _count.value = list.size
        saveIndex(list)
    }

    // ---------------------------------------------------------------- upiti

    /** Pretraga: svi pojmovi (razmakom odvojeni) moraju se pojaviti u putanji ili imenu. */
    fun search(query: String, limit: Int = 400): List<LibEntry> {
        val tokens = query.lowercase().split(' ', '_', '-').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<LibEntry>()
        for (e in entries) {
            if (tokens.all { it in e.hay }) {
                out.add(e)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** Sadržaj foldera: podfolderi (ime, broj semplova unutra) i semplovi direktno u njemu. dir "" = vrh. */
    fun listDir(dir: String): Pair<List<Pair<String, Int>>, List<LibEntry>> {
        val folders = LinkedHashMap<String, Int>()
        val files = ArrayList<LibEntry>()
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        for (e in entries) {
            if (e.dir == dir) {
                files.add(e)
            } else if (e.dir.startsWith(prefix)) {
                val next = e.dir.substring(prefix.length).substringBefore('/')
                folders[next] = (folders[next] ?: 0) + 1
            }
        }
        return folders.entries.map { it.key to it.value }.sortedBy { it.first.lowercase() } to files
    }
}
