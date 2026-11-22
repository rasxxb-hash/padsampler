package com.example.padsampler.desktop

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * "Check for updates" (⚙): JEDINI mrežni poziv aplikacije osim Ableton Linka, i to samo kad korisnik klikne.
 * Pita GitHub za posljednje izdanje (javni API, bez naloga); ne šalje ništa o korisniku ni o računaru
 * (User-Agent je samo "PadSampler"). Nikad se ne pokreće sam (korisnik, 19. 11. 2026).
 */
object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/rasxxb-hash/padsampler/releases/latest"
    const val DOWNLOAD_PAGE = "https://nerd-instruments.net/download"

    sealed class Result {
        data class UpToDate(val current: String) : Result()
        data class NewVersion(val current: String, val latest: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Blokira (pozadinska nit). */
    fun check(current: String): Result {
        return try {
            val c = URI(LATEST).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "PadSampler")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (c.responseCode != 200) return Result.Failed("Server answered ${c.responseCode}")
                val tag = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("tag_name", "")
                val latest = tag.trim().removePrefix("v").removePrefix("V")
                if (latest.isEmpty()) return Result.Failed("No release found")
                if (compare(latest, current) > 0) Result.NewVersion(current, latest) else Result.UpToDate(current)
            } finally {
                c.disconnect()
            }
        } catch (e: java.net.UnknownHostException) {
            Result.Failed("No internet connection")
        } catch (e: java.net.SocketTimeoutException) {
            Result.Failed("No answer (timeout)")
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Error")
        }
    }

    /** "2.0.10" > "2.0.9"; brojevi po tačkama, ostalo se ignoriše. */
    fun compare(a: String, b: String): Int {
        fun parts(s: String) = s.split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun openDownloadPage() {
        try {
            java.awt.Desktop.getDesktop().browse(URI(DOWNLOAD_PAGE))
        } catch (_: Exception) {}
    }
}
