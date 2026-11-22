package com.example.padsampler.studio

import com.example.padsampler.data.DrumsJson
import com.example.padsampler.model.DrumLayout
import com.example.padsampler.model.DrumSound
import com.example.padsampler.model.DrumsState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Korisnikovi kitovi za Drums: 8 zvukova (sempl ili ugrađeni zvuk), jačine i imena, pod imenom kita. Važe za sve
 * sesije. Folder `drum_kits/` u folderu aplikacije: `kits.json` (spisak) i po jedan podfolder sa kopijama fajlova,
 * pa kit radi i kad se original obriše ili premjesti.
 */
class UserDrumKits(baseDir: File) {
    class Kit(val name: String, val dir: String, val sounds: List<DrumSound?>) {
        val count get() = sounds.count { it != null }
    }

    private val root = File(baseDir, "drum_kits")
    private val index = File(root, "kits.json")
    private val tmp = File(root, "kits.json.tmp")

    @Synchronized
    fun list(): List<Kit> {
        if (!index.isFile) return emptyList()
        return try {
            val a = JSONArray(index.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "").ifBlank { return@mapNotNull null }
                val dir = o.optString("dir", "").takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') }
                    ?: return@mapNotNull null
                val s = DrumsJson.fromJson(o.optJSONObject("drums")) ?: return@mapNotNull null
                Kit(name, dir, s.sounds)
            }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Fajl zvuka u kitu. */
    fun file(kit: Kit, snd: DrumSound) = File(File(root, kit.dir), snd.fileName)

    /**
     * Snima kit (isto ime = zamjena) sa kopijama fajlova iz [samplesDir]. Trake čiji fajl fali ostanu prazne.
     * Vraća snimljeni kit ili null ako nijedan zvuk nije mogao biti kopiran.
     */
    @Synchronized
    fun save(name: String, sounds: List<DrumSound?>, samplesDir: File): Kit? {
        val clean = name.trim().take(40).ifEmpty { return null }
        root.mkdirs()
        val dirName = "kit_${System.currentTimeMillis()}"
        val dir = File(root, dirName).apply { mkdirs() }
        val copied = sounds.map { snd ->
            if (snd == null) return@map null
            val src = File(samplesDir, snd.fileName)
            if (!src.isFile) return@map null
            try {
                src.copyTo(File(dir, snd.fileName), overwrite = true)
                snd
            } catch (e: Exception) {
                null
            }
        }
        if (copied.all { it == null }) {
            dir.deleteRecursively()
            return null
        }
        val kit = Kit(clean, dirName, copied)
        val old = list()
        old.filter { it.name.equals(clean, ignoreCase = true) }.forEach { File(root, it.dir).deleteRecursively() }
        write(old.filterNot { it.name.equals(clean, ignoreCase = true) } + kit)
        return kit
    }

    @Synchronized
    fun delete(name: String) {
        val old = list()
        old.filter { it.name == name }.forEach { File(root, it.dir).deleteRecursively() }
        write(old.filterNot { it.name == name })
    }

    private fun write(list: List<Kit>) {
        root.mkdirs()
        val a = JSONArray()
        for (k in list.sortedBy { it.name.lowercase() }) {
            val sounds = List(DrumLayout.LANES) { k.sounds.getOrNull(it) }
            a.put(JSONObject().put("name", k.name).put("dir", k.dir).put("drums", DrumsJson.toJson(DrumsState(sounds = sounds))))
        }
        tmp.writeText(a.toString())
        if (!tmp.renameTo(index)) {
            tmp.copyTo(index, overwrite = true)
            tmp.delete()
        }
    }
}
