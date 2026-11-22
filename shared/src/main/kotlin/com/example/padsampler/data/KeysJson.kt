package com.example.padsampler.data

import com.example.padsampler.model.DrumLayout
import com.example.padsampler.model.KeyNote
import com.example.padsampler.model.KeysLayout
import com.example.padsampler.model.KeysState
import com.example.padsampler.model.Scale
import com.example.padsampler.model.SynthParams
import org.json.JSONArray
import org.json.JSONObject

/** Keys stanje (zvuk + piano roll) u JSON: pads.json (sesija i printani pad). */
object KeysJson {
    fun toJson(s: KeysState): JSONObject = JSONObject()
        .put("version", 1)
        .put("params", paramsToJson(s.params))
        .put("preset", s.presetName)
        .put("bars", s.bars)
        .put("root", s.root)
        .put("scale", s.scale.name)
        .put("octave", s.octave)
        .put("noteLength", s.noteLength)
        .put("category", s.category)
        .put("swing", s.swing)
        // [korak, dužina, nota] ili [korak, dužina, nota, jačina] (jačina samo kad nije 1)
        .put("notes", JSONArray(s.notes.map {
            JSONArray(
                if (it.velocity < 1f) listOf(it.step, it.length, it.pitch, it.velocity.toDouble())
                else listOf(it.step, it.length, it.pitch)
            )
        }))

    fun fromJson(o: JSONObject?): KeysState? {
        if (o == null) return null
        return try {
            val notesArr = o.optJSONArray("notes") ?: JSONArray()
            val notes = (0 until notesArr.length()).mapNotNull { i ->
                val a = notesArr.optJSONArray(i) ?: return@mapNotNull null
                val v = a.optDouble(3, 1.0).toFloat().let { if (it.isFinite()) it.coerceIn(DrumLayout.MIN_VELOCITY, 1f) else 1f }
                val n = KeyNote(a.optInt(0, -1), a.optInt(1, 1), a.optInt(2, -1), v)
                n.takeIf { it.step in 0 until DrumLayout.MAX_STEPS && it.pitch in 0..127 && it.length >= 1 }
            }
            val bars = o.optInt("bars", 1).let { if (it in DrumLayout.BAR_OPTIONS) it else 1 }
            KeysState(
                params = paramsFromJson(o.optJSONArray("params")),
                presetName = o.optString("preset", "Init"),
                notes = notes,
                bars = bars,
                root = o.optInt("root", 0).coerceIn(0, 11),
                scale = runCatching { Scale.valueOf(o.optString("scale", "MINOR")) }.getOrDefault(Scale.MINOR),
                octave = o.optInt("octave", 1).coerceIn(KeysLayout.MIN_OCTAVE, KeysLayout.MAX_OCTAVE),
                noteLength = o.optInt("noteLength", 2).let { if (it in KeysLayout.NOTE_LENGTHS) it else 2 },
                // "Pad" je do 2.0.1 bilo ime kategorije; sada "Pads" (kao u Sessionu)
                category = o.optString("category", "Bass").ifBlank { "Bass" }.let { if (it == "Pad") "Pads" else it },
                swing = o.optInt("swing", 0).coerceIn(0, DrumLayout.MAX_SWING)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun paramsToJson(p: List<Float>) = JSONArray(p.map { it.toDouble() })

    /** Nepoznate / nedostajuće vrijednosti dobiju podrazumijevane, sve se ograniči na opseg parametra. */
    fun paramsFromJson(a: JSONArray?): List<Float> = SynthParams.all.map { d ->
        val v = if (a != null && d.id < a.length()) a.optDouble(d.id, d.def.toDouble()).toFloat() else d.def
        if (v.isFinite()) v.coerceIn(d.min, d.max) else d.def
    }
}
