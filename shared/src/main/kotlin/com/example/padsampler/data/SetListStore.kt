package com.example.padsampler.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Set lista: redoslijed sesija za nastup (setlist.json u privatnom folderu).
 * items = dirName sesija (SessionInfo.dirName), current = indeks pjesme koja je zadnja učitana (-1 = nijedna).
 */
data class SetList(val items: List<String> = emptyList(), val current: Int = -1)

class SetListStore(baseDir: File) {
    private val file = File(baseDir, "setlist.json")
    private val tmp = File(baseDir, "setlist.json.tmp")

    @Synchronized
    fun load(): SetList = try {
        if (!file.exists()) SetList() else {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = List(arr.length()) { arr.getString(it) }
            SetList(items, o.optInt("current", -1).coerceIn(-1, items.size - 1))
        }
    } catch (e: Exception) {
        SetList()
    }

    @Synchronized
    fun save(list: SetList) {
        val o = JSONObject().put("items", JSONArray(list.items)).put("current", list.current)
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(file)) {   // Windows: renameTo ne prepisuje postojeći fajl
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
