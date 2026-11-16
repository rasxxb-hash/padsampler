@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.example.padsampler.ui

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Fontovi se traže u assets/fonts: fajl koji počinje sa "Audiowide" i fajl koji počinje sa "Inter" (.ttf). */
fun loadAndroidFonts(context: Context) {
    val am = context.assets
    val files = try { am.list("fonts")?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
    val interFile = files.firstOrNull { it.startsWith("Inter", ignoreCase = true) && it.endsWith(".ttf", ignoreCase = true) }
    val audioFile = files.firstOrNull { it.startsWith("Audiowide", ignoreCase = true) && it.endsWith(".ttf", ignoreCase = true) }
    if (interFile != null) {
        try {
            AppFonts.inter = FontFamily(
                listOf(400, 500, 600, 700).map { w ->
                    Font(
                        path = "fonts/$interFile", assetManager = am,
                        weight = FontWeight(w), style = FontStyle.Normal,
                        variationSettings = FontVariation.Settings(FontVariation.weight(w))
                    )
                }
            )
        } catch (_: Exception) {}
    }
    if (audioFile != null) {
        try {
            AppFonts.audiowide = FontFamily(Font(path = "fonts/$audioFile", assetManager = am))
        } catch (_: Exception) {}
    }
}
