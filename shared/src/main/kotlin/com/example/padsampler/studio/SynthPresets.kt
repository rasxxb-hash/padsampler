package com.example.padsampler.studio

import com.example.padsampler.data.KeysJson
import com.example.padsampler.model.SynthParams as P
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SynthPreset(val name: String, val params: List<Float>, val builtIn: Boolean, val category: String = "")

/**
 * Preseti sintisajzera: ugrađeni (u kodu) + korisnikovi (synth_presets.json u folderu aplikacije, važe za sve
 * sesije). Talasi: 0 TRI, 1 SAW, 2 SQR, 3 PULSE, 4 NARROW.
 */
class SynthPresets(baseDir: File) {
    private val file = File(baseDir, "synth_presets.json")
    private val tmp = File(baseDir, "synth_presets.json.tmp")

    val builtIn: List<SynthPreset> = listOf(
        preset("INIT", "Init"),
        preset(
            "BASS", "Classic Bass",
            P.OSC1_WAVE to 1f, P.OSC1_OCT to -1f, P.OSC2_WAVE to 2f, P.OSC2_OCT to -1f, P.OSC2_DETUNE to 0.05f,
            P.OSC2_LEVEL to 0.7f, P.OSC3_WAVE to 1f, P.OSC3_OCT to -2f, P.OSC3_LEVEL to 0.5f,
            P.CUTOFF to 400f, P.RESONANCE to 0.35f, P.FILTER_ENV to 0.55f, P.KEY_TRACK to 0.3f,
            P.F_ATTACK to 0.002f, P.F_DECAY to 0.25f, P.F_SUSTAIN to 0.1f, P.F_RELEASE to 0.1f,
            P.A_ATTACK to 0.002f, P.A_DECAY to 0.5f, P.A_SUSTAIN to 1f, P.A_RELEASE to 0.08f
        ),
        preset(
            "BASS", "Sub Bass",
            P.OSC1_WAVE to 0f, P.OSC1_OCT to -1f, P.OSC2_LEVEL to 0f, P.OSC3_LEVEL to 0f,
            P.CUTOFF to 300f, P.RESONANCE to 0.1f, P.FILTER_ENV to 0.1f,
            P.A_ATTACK to 0.005f, P.A_DECAY to 0.3f, P.A_SUSTAIN to 1f, P.A_RELEASE to 0.12f
        ),
        preset(
            "BASS", "Acid Bass",
            P.OSC1_WAVE to 1f, P.OSC1_OCT to -1f, P.OSC2_LEVEL to 0f, P.OSC3_LEVEL to 0f,
            P.CUTOFF to 250f, P.RESONANCE to 0.8f, P.FILTER_ENV to 0.7f, P.KEY_TRACK to 0.3f,
            P.F_ATTACK to 0.001f, P.F_DECAY to 0.18f, P.F_SUSTAIN to 0f, P.F_RELEASE to 0.1f,
            P.A_ATTACK to 0.002f, P.A_DECAY to 0.3f, P.A_SUSTAIN to 0.9f, P.A_RELEASE to 0.06f, P.GLIDE to 0.06f
        ),
        preset(
            "LEAD", "Fat Lead",
            P.OSC1_WAVE to 1f, P.OSC2_WAVE to 1f, P.OSC2_DETUNE to 0.12f, P.OSC3_WAVE to 2f, P.OSC3_OCT to 1f,
            P.OSC3_LEVEL to 0.3f, P.CUTOFF to 2500f, P.RESONANCE to 0.25f, P.FILTER_ENV to 0.35f,
            P.A_ATTACK to 0.005f, P.A_DECAY to 0.3f, P.A_SUSTAIN to 0.9f, P.A_RELEASE to 0.25f, P.GLIDE to 0.05f
        ),
        preset(
            "KEYS", "Brass",
            P.OSC1_WAVE to 1f, P.OSC2_WAVE to 1f, P.OSC2_DETUNE to 0.1f, P.OSC3_LEVEL to 0f,
            P.CUTOFF to 900f, P.RESONANCE to 0.15f, P.FILTER_ENV to 0.6f,
            P.F_ATTACK to 0.08f, P.F_DECAY to 0.5f, P.F_SUSTAIN to 0.5f, P.F_RELEASE to 0.3f,
            P.A_ATTACK to 0.05f, P.A_DECAY to 0.4f, P.A_SUSTAIN to 0.85f, P.A_RELEASE to 0.3f, P.VOICES to 4f
        ),
        preset(
            "PAD", "Warm Pad",
            P.OSC1_WAVE to 1f, P.OSC2_WAVE to 1f, P.OSC2_DETUNE to 0.15f, P.OSC3_WAVE to 0f, P.OSC3_OCT to 1f,
            P.OSC3_LEVEL to 0.4f, P.CUTOFF to 1500f, P.RESONANCE to 0.2f, P.FILTER_ENV to 0.2f,
            P.F_ATTACK to 1.2f, P.F_DECAY to 2f, P.F_SUSTAIN to 0.6f, P.F_RELEASE to 2f,
            P.A_ATTACK to 1f, P.A_DECAY to 1.5f, P.A_SUSTAIN to 0.9f, P.A_RELEASE to 2.5f, P.VOICES to 8f,
            P.VOLUME to 0.6f
        ),
        preset(
            "KEYS", "Pluck Keys",
            P.OSC1_WAVE to 2f, P.OSC2_WAVE to 1f, P.OSC2_DETUNE to 0.03f, P.OSC2_LEVEL to 0.6f, P.OSC3_LEVEL to 0f,
            P.CUTOFF to 1800f, P.RESONANCE to 0.2f, P.FILTER_ENV to 0.6f,
            P.F_ATTACK to 0.001f, P.F_DECAY to 0.35f, P.F_SUSTAIN to 0f, P.F_RELEASE to 0.3f,
            P.A_ATTACK to 0.001f, P.A_DECAY to 0.6f, P.A_SUSTAIN to 0f, P.A_RELEASE to 0.4f, P.VOICES to 8f
        ),
        preset(
            "FX", "Noise Sweep FX",
            P.OSC1_LEVEL to 0f, P.OSC2_LEVEL to 0f, P.OSC3_LEVEL to 0f, P.NOISE to 1f,
            P.CUTOFF to 3000f, P.RESONANCE to 0.7f, P.FILTER_ENV to 0.8f,
            P.F_ATTACK to 0.5f, P.F_DECAY to 1f, P.F_SUSTAIN to 0f, P.F_RELEASE to 1f,
            P.A_ATTACK to 0.01f, P.A_DECAY to 1f, P.A_SUSTAIN to 0f, P.A_RELEASE to 1f, P.VOICES to 4f
        ),

        // ---- BASS
        preset(
            "BASS", "Moog Pluck Bass",
            P.OSC1_WAVE to 1f, P.OSC1_OCT to -1f, P.OSC2_WAVE to 2f, P.OSC2_OCT to -1f, P.OSC2_DETUNE to 0.04f,
            P.OSC2_LEVEL to 0.6f, P.CUTOFF to 280f, P.RESONANCE to 0.4f, P.FILTER_ENV to 0.7f, P.KEY_TRACK to 0.4f,
            P.F_ATTACK to 0.001f, P.F_DECAY to 0.15f, P.F_SUSTAIN to 0f, P.F_RELEASE to 0.1f,
            P.A_ATTACK to 0.001f, P.A_DECAY to 0.45f, P.A_SUSTAIN to 0.2f, P.A_RELEASE to 0.08f
        ),
        preset(
            "BASS", "Reese Bass",
            P.OSC1_WAVE to 1f, P.OSC1_OCT to -1f, P.OSC2_WAVE to 1f, P.OSC2_OCT to -1f, P.OSC2_DETUNE to 0.25f,
            P.OSC2_LEVEL to 1f, P.OSC3_WAVE to 0f, P.OSC3_OCT to -2f, P.OSC3_DETUNE to 0f, P.OSC3_LEVEL to 0.5f,
            P.CUTOFF to 650f, P.RESONANCE to 0.15f, P.FILTER_ENV to 0.1f, P.KEY_TRACK to 0.3f,
            P.A_ATTACK to 0.005f, P.A_DECAY to 0.5f, P.A_SUSTAIN to 1f, P.A_RELEASE to 0.15f
        ),
        preset(
            "BASS", "Square Sub",
            P.OSC1_WAVE to 2f, P.OSC1_OCT to -1f, P.OSC2_WAVE to 0f, P.OSC2_OCT to -2f, P.OSC2_DETUNE to 0f,
            P.OSC2_LEVEL to 0.6f, P.CUTOFF to 450f, P.RESONANCE to 0.1f, P.FILTER_ENV to 0.2f,
            P.F_DECAY to 0.3f, P.F_SUSTAIN to 0.2f,
            P.A_ATTACK to 0.003f, P.A_DECAY to 0.4f, P.A_SUSTAIN to 1f, P.A_RELEASE to 0.1f
        ),
        preset(
            "BASS", "Funk Bass",
            P.OSC1_WAVE to 3f, P.OSC1_OCT to -1f, P.OSC2_WAVE to 1f, P.OSC2_OCT to -1f, P.OSC2_LEVEL to 0.4f,
            P.CUTOFF to 550f, P.RESONANCE to 0.5f, P.FILTER_ENV to 0.8f, P.KEY_TRACK to 0.4f,
            P.F_ATTACK to 0.001f, P.F_DECAY to 0.12f, P.F_SUSTAIN to 0f, P.F_RELEASE to 0.08f,
            P.A_ATTACK to 0.001f, P.A_DECAY to 0.25f, P.A_SUSTAIN to 0.35f, P.A_RELEASE to 0.06f
        ),
        preset(
            "BASS", "Deep 808",
            P.OSC1_WAVE to 0f, P.OSC1_OCT to -2f, P.OSC2_LEVEL to 0f, P.OSC3_LEVEL to 0f,
            P.CUTOFF to 220f, P.RESONANCE to 0f, P.FILTER_ENV to 0.15f, P.F_DECAY to 0.2f, P.F_SUSTAIN to 0f,
            P.A_ATTACK to 0.002f, P.A_DECAY to 1.4f, P.A_SUSTAIN to 0f, P.A_RELEASE to 0.4f, P.GLIDE to 0.08f,
            P.VOLUME to 0.85f
        ),

        // ---- LEAD
        preset(
            "LEAD", "Mono Glide Lead",
            P.OSC1_WAVE to 1f, P.OSC2_WAVE to 1f, P.OSC2_DETUNE to -0.08f, P.OSC3_WAVE to 1f, P.OSC3_OCT to -1f,
            P.OSC3_LEVEL to 0.4f, P.CUTOFF to 1900f, P.RESONANCE to 0.4f, P.FILTER_ENV to 0.3f,
            P.F_DECAY to 0.4f, P.F_SUSTAIN to 0.5f,
            P.A_ATTACK to 0.003f, P.A_DECAY to 0.3f, P.A_SUSTAIN to 1f, P.A_RELEASE to 0.15f, P.GLIDE to 0.15f
        ),
        preset(
            "LEAD", "Square Lead",
            P.OSC1_WAVE to 2f, P.OSC2_WAVE to 3f, P.OSC2_OCT to 1f, P.OSC2_DETUNE to 0.05f, P.OSC2_LEVEL to 0.45f,
            P.CUTOFF to 3200f, P.RESONANCE to 0.3f, P.FILTER_ENV to 0.25f,
            P.A_ATTACK to 0.003f, P.A_DECAY to 0.3f, P.A_SUSTAIN to 0.9f, P.A_RELEASE to 0.2f, P.GLIDE to 0.04f
        ),
        preset(
            "LEAD", "Whistle Lead",
            P.OSC1_WAVE to 0f, P.OSC2_WAVE to 0f, P.OSC2_OCT to 1f, P.OSC2_DETUNE to 0f, P.OSC2_LEVEL to 0.3f,
            P.CUTOFF to 5000f, P.RESONANCE to 0f, P.FILTER_ENV to 0.1f,
            P.A_ATTACK to 0.03f, P.A_DECAY to 0.3f, P.A_SUSTAIN to 1f, P.A_RELEASE to 0.3f, P.GLIDE to 0.1f
        ),
        preset(
            "LEAD", "Fifth Lead",
            P.OSC1_WAVE to 1f, P.OSC2_WAVE to 1f, P.OSC2_DETUNE to 7f, P.OSC2_LEVEL to 0.7f,
            P.CUTOFF to 2200f, P.RESONANCE to 0.3f, P.FILTER_ENV to 0.35f, P.F_DECAY to 0.35f, P.F_SUSTAIN to 0.4f,
            P.A_ATTACK to 0.003f, P.A_DECAY to 0.3f, P.A_SUSTAIN to 0.9f, P.A_RELEASE to 0.2f
        ),

        // ---- PAD
        preset(
            "PAD", "Strings",
            P.OSC1_WAVE to 1f, P.OSC2_WAVE to 1f, P.OSC2_DETUNE to 0.12f, P.OSC3_WAVE to 1f, P.OSC3_OCT to 0f,
            P.OSC3_DETUNE to -0.1f, P.OSC3_LEVEL to 0.6f, P.CUTOFF to 2600f, P.RESONANCE to 0.1f, P.FILTER_ENV to 0.1f,
            P.A_ATTACK to 0.6f, P.A_DECAY to 1f, P.A_SUSTAIN to 0.9f, P.A_RELEASE to 1.8f, P.VOICES to 8f,
            P.VOLUME to 0.55f
        ),
        preset(
            "PAD", "Dark Drone",
            P.OSC1_WAVE to 1f, P.OSC1_OCT to -1f, P.OSC2_WAVE to 0f, P.OSC2_DETUNE to 0.1f, P.OSC3_WAVE to 1f,
            P.OSC3_OCT to -2f, P.OSC3_LEVEL to 0.5f, P.CUTOFF to 500f, P.RESONANCE to 0.35f, P.FILTER_ENV to 0.15f,
            P.F_ATTACK to 2f, P.F_SUSTAIN to 0.8f,
            P.A_ATTACK to 2f, P.A_DECAY to 2f, P.A_SUSTAIN to 1f, P.A_RELEASE to 3f, P.VOICES to 8f, P.VOLUME to 0.6f
        ),
        preset(
            "PAD", "Glass Pad",
            P.OSC1_WAVE to 0f, P.OSC2_WAVE to 2f, P.OSC2_OCT to 1f, P.OSC2_DETUNE to 0.05f, P.OSC2_LEVEL to 0.3f,
            P.CUTOFF to 4000f, P.RESONANCE to 0.2f, P.FILTER_ENV to 0.15f,
            P.A_ATTACK to 0.3f, P.A_DECAY to 1.2f, P.A_SUSTAIN to 0.7f, P.A_RELEASE to 2f, P.VOICES to 8f,
            P.VOLUME to 0.6f
        ),

        // ---- KEYS
        preset(
            "KEYS", "Organ",
            P.OSC1_WAVE to 2f, P.OSC2_WAVE to 2f, P.OSC2_OCT to 1f, P.OSC2_DETUNE to 0f, P.OSC2_LEVEL to 0.6f,
            P.OSC3_WAVE to 0f, P.OSC3_OCT to -1f, P.OSC3_DETUNE to 0f, P.OSC3_LEVEL to 0.5f,
            P.CUTOFF to 6000f, P.RESONANCE to 0f, P.FILTER_ENV to 0f,
            P.A_ATTACK to 0.003f, P.A_DECAY to 0.1f, P.A_SUSTAIN to 1f, P.A_RELEASE to 0.05f, P.VOICES to 8f,
            P.VOLUME to 0.55f
        ),
        preset(
            "KEYS", "E-Piano",
            P.OSC1_WAVE to 0f, P.OSC2_WAVE to 2f, P.OSC2_OCT to 1f, P.OSC2_DETUNE to 0f, P.OSC2_LEVEL to 0.25f,
            P.CUTOFF to 2500f, P.RESONANCE to 0.1f, P.FILTER_ENV to 0.3f, P.F_DECAY to 0.5f, P.F_SUSTAIN to 0.2f,
            P.A_ATTACK to 0.002f, P.A_DECAY to 1.2f, P.A_SUSTAIN to 0.2f, P.A_RELEASE to 0.4f, P.VOICES to 8f
        ),
        preset(
            "KEYS", "Chord Stab",
            P.OSC1_WAVE to 1f, P.OSC2_WAVE to 1f, P.OSC2_DETUNE to 0.1f, P.CUTOFF to 1500f, P.RESONANCE to 0.3f,
            P.FILTER_ENV to 0.6f, P.F_ATTACK to 0.001f, P.F_DECAY to 0.2f, P.F_SUSTAIN to 0f,
            P.A_ATTACK to 0.001f, P.A_DECAY to 0.25f, P.A_SUSTAIN to 0f, P.A_RELEASE to 0.15f, P.VOICES to 8f,
            P.VOLUME to 0.6f
        ),
        preset(
            "KEYS", "Bell",
            P.OSC1_WAVE to 0f, P.OSC2_WAVE to 2f, P.OSC2_OCT to 2f, P.OSC2_DETUNE to 0.03f, P.OSC2_LEVEL to 0.3f,
            P.CUTOFF to 8000f, P.RESONANCE to 0f, P.FILTER_ENV to 0.2f, P.F_DECAY to 0.8f, P.F_SUSTAIN to 0f,
            P.A_ATTACK to 0.001f, P.A_DECAY to 1.5f, P.A_SUSTAIN to 0f, P.A_RELEASE to 1.5f, P.VOICES to 8f
        ),

        // ---- FX
        preset(
            "FX", "Laser Zap",
            P.OSC1_WAVE to 2f, P.OSC1_OCT to 1f, P.OSC2_LEVEL to 0f, P.CUTOFF to 8000f, P.RESONANCE to 0.9f,
            P.FILTER_ENV to 1f, P.F_ATTACK to 0.001f, P.F_DECAY to 0.15f, P.F_SUSTAIN to 0f,
            P.A_ATTACK to 0.001f, P.A_DECAY to 0.2f, P.A_SUSTAIN to 0f, P.A_RELEASE to 0.1f
        ),
        preset(
            "FX", "Riser",
            P.OSC1_WAVE to 1f, P.OSC2_DETUNE to 0.2f, P.NOISE to 0.6f, P.CUTOFF to 200f, P.RESONANCE to 0.6f,
            P.FILTER_ENV to 1f, P.F_ATTACK to 4f, P.F_SUSTAIN to 1f, P.F_RELEASE to 1f,
            P.A_ATTACK to 3f, P.A_SUSTAIN to 1f, P.A_RELEASE to 1f, P.VOICES to 4f, P.VOLUME to 0.6f
        ),
        preset(
            "FX", "Wind",
            P.OSC1_LEVEL to 0f, P.OSC2_LEVEL to 0f, P.NOISE to 1f, P.CUTOFF to 800f, P.RESONANCE to 0.85f,
            P.FILTER_ENV to 0.3f, P.F_ATTACK to 1.5f, P.F_DECAY to 2f, P.F_SUSTAIN to 0.5f, P.F_RELEASE to 2f,
            P.A_ATTACK to 1.5f, P.A_SUSTAIN to 1f, P.A_RELEASE to 2f, P.VOICES to 4f, P.VOLUME to 0.6f
        )
    ).sortedBy { CATEGORY_ORDER.indexOf(it.category) }   // stabilno: unutar grupe redom kako su napisani

    companion object {
        val CATEGORY_ORDER = listOf("INIT", "BASS", "LEAD", "PAD", "KEYS", "FX")
    }

    @Synchronized
    fun user(): List<SynthPreset> {
        if (!file.isFile) return emptyList()
        return try {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "").ifBlank { return@mapNotNull null }
                SynthPreset(name, KeysJson.paramsFromJson(o.optJSONArray("params")), false)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Snima (ili zamjenjuje istoimeni) korisnikov preset. */
    @Synchronized
    fun save(name: String, params: List<Float>) {
        val clean = name.trim().take(40).ifEmpty { return }
        write(user().filterNot { it.name.equals(clean, ignoreCase = true) } + SynthPreset(clean, params, false))
    }

    @Synchronized
    fun delete(name: String) = write(user().filterNot { it.name == name })

    private fun write(list: List<SynthPreset>) {
        val a = JSONArray()
        for (p in list.sortedBy { it.name.lowercase() }) a.put(JSONObject().put("name", p.name).put("params", KeysJson.paramsToJson(p.params)))
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun preset(category: String, name: String, vararg values: Pair<Int, Float>): SynthPreset {
        val p = P.defaults().toMutableList()
        for ((id, v) in values) p[id] = v
        return SynthPreset(name, p, true, category)
    }
}
