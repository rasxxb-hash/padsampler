package com.example.padsampler.desktop

import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.padsampler.ui.AppFonts
import java.io.File

/**
 * Isti fajlovi kao na Androidu: u folderu fontova (-Dpadsampler.fonts) tražimo Inter*.ttf i Audiowide*.ttf.
 * Ako ih nema, ostaje sistemski font.
 */
fun loadDesktopFonts() {
    val dir = System.getProperty("compose.application.resources.dir")?.let { File(it, "fonts") }
        ?: System.getProperty("padsampler.fonts")?.let { File(it) } ?: return
    val files = dir.listFiles()?.filter { it.name.endsWith(".ttf", true) } ?: return
    files.firstOrNull { it.name.startsWith("Inter", true) }?.let { f ->
        try {
            AppFonts.inter = FontFamily(listOf(400, 500, 600, 700).map { w ->
                Font(f, FontWeight(w), FontStyle.Normal)
            })
        } catch (_: Exception) {}
    }
    files.firstOrNull { it.name.startsWith("Audiowide", true) }?.let { f ->
        try { AppFonts.audiowide = FontFamily(Font(f, FontWeight.Normal, FontStyle.Normal)) } catch (_: Exception) {}
    }
}
