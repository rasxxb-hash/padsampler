package com.example.padsampler.data

import com.example.padsampler.model.DrumLayout
import com.example.padsampler.model.DrumSound
import com.example.padsampler.model.DrumSynthKind
import com.example.padsampler.model.DrumSynthSpec
import com.example.padsampler.model.DrumsState
import com.example.padsampler.model.Fader
import org.json.JSONArray
import org.json.JSONObject

/** Drums stanje (kit + pattern) u JSON: u pads.json (sesija i printani pad) i u starom drums.json. */
object DrumsJson {
    fun toJson(s: DrumsState): JSONObject {
        val sounds = JSONArray()
        s.sounds.forEachIndexed { lane, snd ->
            if (snd != null) sounds.put(
                JSONObject().put("lane", lane).put("name", snd.name).put("file", snd.fileName)
                    .put("label", snd.label).put("gain", snd.gain.toDouble())
                    .also { o -> snd.synth?.let { o.put("synth", synthToJson(it)) } }
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("bars", s.bars)
            .put("selected", s.selected)
            .put("sounds", sounds)
            .put("steps", JSONArray(s.steps.map { JSONArray(it.sorted()) }))
            .put("mutes", JSONArray(s.mutes))
            .put("swing", s.swing)
            // jačine različite od 1, po traci: [[korak, jačina], ...]
            .put("vel", JSONArray(s.velocities.mapIndexed { lane, m ->
                JSONArray(m.filter { it.key in s.steps[lane] && it.value < 1f }.toSortedMap()
                    .map { JSONArray(listOf(it.key, it.value.toDouble())) })
            }))
    }

    fun fromJson(o: JSONObject?): DrumsState? {
        if (o == null) return null
        return try {
            val sounds = MutableList<DrumSound?>(DrumLayout.LANES) { null }
            val arr = o.optJSONArray("sounds") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val lane = p.optInt("lane", -1)
                val file = p.optString("file", "")
                if (lane !in 0 until DrumLayout.LANES || file.isBlank() || file.contains('/') || file.contains('\\')) continue
                sounds[lane] = DrumSound(
                    p.optString("name", ""), file, p.optString("label", ""),
                    p.optDouble("gain", Fader.DEFAULT.toDouble()).toFloat().coerceIn(0f, 1f),
                    synthFromJson(p.optJSONObject("synth"))
                )
            }
            val stepsArr = o.optJSONArray("steps")
            val steps = List(DrumLayout.LANES) { lane ->
                val a = stepsArr?.optJSONArray(lane) ?: return@List emptySet<Int>()
                (0 until a.length()).map { a.optInt(it, -1) }.filter { it in 0 until DrumLayout.MAX_STEPS }.toSet()
            }
            val velArr = o.optJSONArray("vel")
            val velocities = List(DrumLayout.LANES) { lane ->
                val a = velArr?.optJSONArray(lane) ?: return@List emptyMap<Int, Float>()
                val m = HashMap<Int, Float>()
                for (i in 0 until a.length()) {
                    val e = a.optJSONArray(i) ?: continue
                    val st = e.optInt(0, -1)
                    val v = e.optDouble(1, 1.0).toFloat()
                    if (st in steps[lane] && v.isFinite() && v < 1f) m[st] = v.coerceAtLeast(DrumLayout.MIN_VELOCITY)
                }
                m
            }
            val mutesArr = o.optJSONArray("mutes")
            val mutes = List(DrumLayout.LANES) { mutesArr?.optBoolean(it, false) ?: false }
            val bars = o.optInt("bars", 1).let { if (it in DrumLayout.BAR_OPTIONS) it else 1 }
            DrumsState(
                sounds = sounds, steps = steps, velocities = velocities, mutes = mutes, bars = bars,
                swing = o.optInt("swing", 0).coerceIn(0, DrumLayout.MAX_SWING),
                selected = o.optInt("selected", 0).coerceIn(0, DrumLayout.LANES - 1)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Ugrađeni zvuk: vrsta + podešavanja (wav se iz njih može ponovo izračunati). */
    private fun synthToJson(s: DrumSynthSpec) = JSONObject()
        .put("kind", s.kind.name).put("tune", s.tune.toDouble()).put("decay", s.decay.toDouble())
        .put("tone", s.tone.toDouble()).put("snap", s.snap.toDouble()).put("grit", s.grit.toDouble())

    private fun synthFromJson(o: JSONObject?): DrumSynthSpec? {
        if (o == null) return null
        val kind = runCatching { DrumSynthKind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
        fun f(key: String, def: Float) = o.optDouble(key, def.toDouble()).toFloat().coerceIn(0f, 1f)
        return DrumSynthSpec(kind, f("tune", 0.5f), f("decay", 0.5f), f("tone", 0.5f), f("snap", 0.5f), f("grit", 0f))
    }
}
