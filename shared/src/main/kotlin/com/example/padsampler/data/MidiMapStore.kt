package com.example.padsampler.data

import com.example.padsampler.model.SynthParams
import org.json.JSONObject
import java.io.File

/**
 * MIDI mapiranje potenciometara Keys (MIDI LEARN): po uređaju, CC broj -> id parametra sintisajzera.
 * Fajl midi_map.json u folderu aplikacije (važi za sve sesije): {"Oxygen 8": {"74": 12, ...}, ...}
 *
 * Imenovani preseti mapiranja (nezavisni od uređaja): midi_presets.json {"Oxygen 8 - filter": {"74": 12}, ...}.
 * Preset se može izvesti u fajl .padmidi (isti na Androidu i računaru), vidi [encodeFile] / [decodeFile].
 */
class MidiMapStore(baseDir: File) {
    private val file = File(baseDir, "midi_map.json")
    private val presetsFile = File(baseDir, "midi_presets.json")

    @Synchronized
    fun load(): Map<String, Map<Int, Int>> = readMaps(file)

    @Synchronized
    fun save(map: Map<String, Map<Int, Int>>) = writeMaps(file, map)

    @Synchronized
    fun loadPresets(): Map<String, Map<Int, Int>> = readMaps(presetsFile).toSortedMap(String.CASE_INSENSITIVE_ORDER)

    @Synchronized
    fun savePresets(presets: Map<String, Map<Int, Int>>) = writeMaps(presetsFile, presets)

    private fun readMaps(f: File): Map<String, Map<Int, Int>> {
        if (!f.isFile) return emptyMap()
        return try {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { key -> parseMap(o.optJSONObject(key)) }.filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeMaps(f: File, maps: Map<String, Map<Int, Int>>) {
        val o = JSONObject()
        for ((key, m) in maps) if (m.isNotEmpty()) o.put(key, mapToJson(m))
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val FILE_EXT = "padmidi"
        private const val FORMAT = "padsampler-midi-map"

        /** {"74": 12, ...} -> mapa; neispravni CC-ovi i parametri se preskaču. */
        fun parseMap(m: JSONObject?): Map<Int, Int> {
            if (m == null) return emptyMap()
            return m.keys().asSequence().mapNotNull { k ->
                val cc = k.toIntOrNull() ?: return@mapNotNull null
                val id = m.optInt(k, -1)
                if (cc in 0..127 && id in 0 until SynthParams.COUNT) cc to id else null
            }.toMap()
        }

        fun mapToJson(m: Map<Int, Int>): JSONObject {
            val d = JSONObject()
            for ((cc, id) in m.toSortedMap()) d.put(cc.toString(), id)
            return d
        }

        /** Sadržaj fajla .padmidi. Parametri su po id-u (`SynthParams`), koji se ne mijenjaju (novi idu na kraj). */
        fun encodeFile(name: String, map: Map<Int, Int>): String =
            JSONObject()
                .put("format", FORMAT)
                .put("version", 1)
                .put("name", name)
                .put("map", mapToJson(map))
                .toString(2)

        /** Ime i mapiranje iz fajla .padmidi; null ako fajl nije ispravan. */
        fun decodeFile(text: String): Pair<String, Map<Int, Int>>? = try {
            val o = JSONObject(text)
            if (o.optString("format") != FORMAT) null
            else {
                val map = parseMap(o.optJSONObject("map"))
                val name = o.optString("name", "").trim().take(40)
                if (map.isEmpty()) null else name to map
            }
        } catch (e: Exception) {
            null
        }
    }
}
