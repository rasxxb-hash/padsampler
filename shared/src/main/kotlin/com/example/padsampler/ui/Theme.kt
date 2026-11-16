package com.example.padsampler.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// ---- Paleta (prigušena, jedan akcent). Vidi dizajn prijedlog.
val AppBackground = Color(0xFF14161A)
val AppBackgroundTop = Color(0xFF1B1E24)
val PanelColor = Color(0xFF1E2228)
val PanelBorder = Color(0xFF2E323A)
val EditBorder = Color(0xFFC9B35C)
val TextMain = Color(0xFFE4E7EC)
val TextDim = Color(0xFF8A919D)
val TextFaint = Color(0xFF646B76)

val Accent = Color(0xFF86B9A0)
val AccentBright = Color(0xFF9CCDB4)
val AccentDim = Color(0xFF5F8A76)
val TabTop = Color(0xFF93C4AB)
val TabBottom = Color(0xFF7FB394)

val Ink = Color(0xFF14171B)            // tamni tekst na obojenoj podlozi
val BtnTop = Color(0xFF272B33)
val BtnBottom = Color(0xFF1F2329)
val HeaderTop = Color(0xFF1E2228)
val HeaderBottom = Color(0xFF181B20)
val Lcd = Color(0xFF0B0D10)
val EmptyPadColor = Color(0xFF13151A)
val PadBase = Color(0xFF1A1D23)

val StopRed = Color(0xFFA34848)       // tamna crvena (ne drečeća)
val StopRedTop = Color(0xFF8A4141)
val StopRedBottom = Color(0xFF6E3333)
val SoloBlue = Color(0xFF6277C4)
val MuteYellow = Color(0xFFC9B35C)
val MuteOrange = Color(0xFFCC8A4D)
val MeterGreen = Color(0xFF86B9A0)    // mint (zaštitna boja)

/** Fontovi aplikacije. Postavlja ih platforma pri pokretanju (Android: AndroidFonts.kt, računar: svoj loader). */
object AppFonts {
    var inter: FontFamily = FontFamily.Default
    var audiowide: FontFamily = FontFamily.Default
}

/**
 * Zaobljeni uglovi, blaži od zadate vrijednosti (korisnik 12. 11. 2026: manje zakrivljenje svuda); na uskim
 * ekranima (telefon) još manji, da pad ne izgleda kao elipsa.
 */
@androidx.compose.runtime.Composable
fun rShape(dp: Int): androidx.compose.foundation.shape.RoundedCornerShape {
    val compact = LocalUiPlatform.current.screenWidthDp() < 1000
    val r = when {
        dp <= 3 -> dp
        compact -> maxOf(3, (dp * 0.4f).toInt())
        else -> maxOf(3, Math.round(dp * 0.55f))
    }
    return androidx.compose.foundation.shape.RoundedCornerShape(androidx.compose.ui.unit.Dp(r.toFloat()))
}

/** Uglovi polja za note/korake (Keys piano roll i Drums): ostaju kao u piano rollu (4 dp; telefon 3). */
@androidx.compose.runtime.Composable
fun stepShape(): androidx.compose.foundation.shape.RoundedCornerShape {
    val compact = LocalUiPlatform.current.screenWidthDp() < 1000
    return androidx.compose.foundation.shape.RoundedCornerShape(androidx.compose.ui.unit.Dp(if (compact) 3f else 4f))
}
