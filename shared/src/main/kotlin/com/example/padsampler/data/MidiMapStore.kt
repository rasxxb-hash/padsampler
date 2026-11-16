package com.example.padsampler.data

import com.example.padsampler.model.SynthParams
import org.json.JSONObject
import java.io.File

/**
 * MIDI mapiranje potenciometara Keys (MIDI LEARN): po uređaju, CC broj -> id parametra sintisajzera.
 * Fajl midi_map.json u folderu aplikacije (važi za sve sesije): {"Oxygen 8": {"74": 12, ...}, ...}
 */
class MidiMapStore(baseDir: File) {
    private val file = File(baseDir, "midi_map.json")
    private val tmp = File(baseDir, "midi_map.json.tmp")

    @Synchronized
    fun load(): Map<String, Map<Int, Int>> {
        if (!file.isFile) return emptyMap()
        return try {
            val o = JSONObject(file.readText())
            o.keys().asSequence().associateWith { dev ->
                val m = o.optJSONObject(dev) ?: JSONObject()
                m.keys().asSequence().mapNotNull { k ->
                    val cc = k.toIntOrNull() ?: return@mapNotNull null
                    val id = m.optInt(k, -1)
                    if (cc in 0..127 && id in 0 until SynthParams.COUNT) cc to id else null
                }.toMap()
            }.filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    fun save(map: Map<String, Map<Int, Int>>) {
        val o = JSONObject()
        for ((dev, m) in map) {
            if (m.isEmpty()) continue
            val d = JSONObject()
            for ((cc, id) in m.toSortedMap()) d.put(cc.toString(), id)
            o.put(dev, d)
        }
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
