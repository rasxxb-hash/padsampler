package com.example.padsampler.data

import com.example.padsampler.model.Fader
import com.example.padsampler.model.Layout
import com.example.padsampler.model.PadConfig
import com.example.padsampler.model.SessionData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Čuva stanje u pads.json (v2). Kopije semplova su u samples/. Stari format (faza 2a) se ignoriše. */
class PadStore(baseDir: File) {
    val samplesDir = File(baseDir, "samples").apply { mkdirs() }
    private val file = File(baseDir, "pads.json")
    private val tmp = File(baseDir, "pads.json.tmp")

    @Synchronized
    fun load(): SessionData {
        if (!file.exists()) return SessionData()
        return try {
            val text = file.readText()
            if (!text.trimStart().startsWith("{")) return SessionData()  // stari format
            val o = JSONObject(text)
            if (o.optInt("version", 0) != 2) return SessionData()

            val sessionBpm = o.optInt("bpm", 120).coerceIn(60, 240)
            val pads = ArrayList<PadConfig>()
            val arr = o.optJSONArray("pads") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val slot = p.getInt("slot")
                if (slot !in 0 until Layout.SLOTS) continue
                val name = p.getString("name")
                val label = p.optString("label", "").ifBlank { name.substringBeforeLast('.') }
                pads.add(
                    PadConfig(
                        slot, name, p.getString("file"), p.optBoolean("loop", false),
                        p.optDouble("srcBpm", sessionBpm.toDouble()).coerceIn(20.0, 400.0),
                        label,
                        // stariji pads.json (ili Android bez putanje): tonalitet iz labele (ime foldera) i imena fajla
                        p.optString("key", "").ifBlank { KeyParser.fromPath(label, name) },
                        DrumsJson.fromJson(p.optJSONObject("studio")),
                        KeysJson.fromJson(p.optJSONObject("keys"))
                    )
                )
            }
            val faders = readFloats(o.optJSONArray("faders"), Fader.DEFAULT)
            val pansArr = o.optJSONArray("pans")
            val pans = List(Layout.TRACKS) { i ->
                if (pansArr != null && i < pansArr.length()) pansArr.optDouble(i, 0.0).toFloat().coerceIn(-1f, 1f) else 0f
            }
            val mutes = readBools(o.optJSONArray("mutes"))
            SessionData(
                name = o.optString("name", ""),
                bpm = o.optInt("bpm", 120).coerceIn(60, 240),
                pads = pads,
                faders = faders,
                pans = pans,
                mutes = mutes,
                master = o.optDouble("master", 1.0).toFloat().coerceIn(0f, 1f),
                drums = DrumsJson.fromJson(o.optJSONObject("drums")),
                keys = KeysJson.fromJson(o.optJSONObject("keys"))
            )
        } catch (e: Exception) {
            SessionData()
        }
    }

    @Synchronized
    fun save(data: SessionData) {
        val pads = JSONArray()
        for (p in data.pads.sortedBy { it.slot }) {
            pads.put(
                JSONObject()
                    .put("slot", p.slot)
                    .put("name", p.name)
                    .put("file", p.fileName)
                    .put("loop", p.loop)
                    .put("srcBpm", p.srcBpm)
                    .put("label", p.label)
                    .put("key", p.key ?: "")
                    .apply { p.studio?.let { put("studio", DrumsJson.toJson(it)) } }
                    .apply { p.keys?.let { put("keys", KeysJson.toJson(it)) } }
            )
        }
        val o = JSONObject()
            .put("version", 2)
            .put("name", data.name)
            .put("bpm", data.bpm)
            .put("master", data.master.toDouble())
            .put("faders", JSONArray(data.faders.map { it.toDouble() }))
            .put("pans", JSONArray(data.pans.map { it.toDouble() }))
            .put("mutes", JSONArray(data.mutes))
            .put("pads", pads)
            .apply { data.drums?.let { put("drums", DrumsJson.toJson(it)) } }
            .apply { data.keys?.let { put("keys", KeysJson.toJson(it)) } }
        tmp.writeText(o.toString(2))
        // zamjena u jednom koraku da se fajl ne ošteti pri prekidu (Windows: renameTo ne prepisuje, pa kopija)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun fileFor(config: PadConfig) = File(samplesDir, config.fileName)
    fun file(name: String) = File(samplesDir, name)

    private fun readFloats(arr: JSONArray?, default: Float): List<Float> =
        List(Layout.TRACKS) { i ->
            if (arr != null && i < arr.length()) arr.optDouble(i, default.toDouble()).toFloat().coerceIn(0f, 1f) else default
        }

    private fun readBools(arr: JSONArray?): List<Boolean> =
        List(Layout.TRACKS) { i -> arr != null && i < arr.length() && arr.optBoolean(i, false) }
}
