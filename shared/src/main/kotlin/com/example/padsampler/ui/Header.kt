package com.example.padsampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.model.Screen
import com.example.padsampler.model.SetClockState
import kotlin.math.floor

/** Gornja traka, ista na svim ekranima: logo, tabovi, ime sesije, metronom, LINK i tempo. */
@Composable
fun AppHeader(
    screen: Screen,
    onScreen: (Screen) -> Unit,
    sessionName: String,
    stretching: Int,
    barPhase: Float,
    linkOn: Boolean,
    linkPeers: Int,
    bpm: Int,
    onLink: () -> Unit,
    onBpmMinus: () -> Unit,
    onBpmPlus: () -> Unit,
    onBpmClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSettings: (() -> Unit)? = null,
    setClock: SetClockState = SetClockState(),
    onSetClock: () -> Unit = {}
) {
    val shape = rShape(14)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(HeaderTop, HeaderBottom)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
    ) {
        LogoMark()
        Text(
            "PadSampler", color = TextMain, fontSize = if (compact) 11.5.sp else 15.5.sp, fontFamily = AppFonts.audiowide, maxLines = 1,
            // blagi sjaj, kao da natpis svijetli
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = 0.55f), blurRadius = 14f)
            )
        )

        // tabovi
        Row(
            modifier = Modifier
                .clip(rShape(11))
                .background(Lcd)
                .padding(3.dp)
        ) {
            Tab("SESSION", screen == Screen.SESSION, compact) { onScreen(Screen.SESSION) }
            Tab("MIXER", screen == Screen.MIXER, compact) { onScreen(Screen.MIXER) }
            Tab("DRUMS", screen == Screen.DRUMS, compact) { onScreen(Screen.DRUMS) }
            Tab("KEYS", screen == Screen.KEYS, compact) { onScreen(Screen.KEYS) }
        }

        // Ime sesije zauzima slobodan prostor između tabova i metronoma (skraćuje se trotačkom). Sat seta, trenutno
        // vrijeme i baterija su u lijevoj koloni (SideRail), da ime sesije ostane vidljivo (korisnik 19. 11. 2026).
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
        ) {
            Text(
                sessionName.ifBlank { "Untitled session" },
                color = TextDim, fontSize = if (compact) 12.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).weight(1f)
            )
        }

        Beats(barPhase)

        // LINK
        val linkShape = rShape(12)
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(linkShape)
                .background(if (linkOn) Color(0xFF1D2127) else Color(0xFF1B1E24))
                .border(1.dp, if (linkOn) Accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f), linkShape)
                .clickable { onLink() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (linkOn) Accent else Color(0xFF3A404A))
            )
            Text(
                if (linkOn) "LINK · ${linkPeers.coerceAtLeast(0)}" else "LINK",
                color = if (linkOn) AccentBright else TextDim,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
            )
        }

        // TEMPO
        TempoButton("−", onBpmMinus)
        Box(
            modifier = Modifier
                .width(if (compact) 84.dp else 104.dp)
                .height(44.dp)
                .clip(rShape(12))
                .background(Lcd)
                .clickable { onBpmClick() },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("$bpm", color = AccentBright, fontSize = if (compact) 18.sp else 22.sp, fontFamily = AppFonts.audiowide, fontWeight = FontWeight.SemiBold)
                Text("BPM", color = AccentDim, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        TempoButton("+", onBpmPlus)

        // podešavanja (samo platforme koje ih imaju, npr. računar)
        if (onSettings != null) TempoButton("⚙", onSettings)
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val shape = rShape(9)
    Box(
        modifier = Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(Brush.verticalGradient(listOf(TabTop, TabBottom)))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 5.dp else 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Ink else TextDim,
            fontSize = if (compact) 9.5.sp else 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = if (compact) 0.2.sp else 0.5.sp
        )
    }
}

@Composable
private fun TempoButton(label: String, onClick: () -> Unit) {
    val shape = rShape(12)
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 40.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(BtnTop, BtnBottom)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
}

/** 8 osmina takta: upaljena je trenutna. */
@Composable
private fun Beats(barPhase: Float) {
    val idx = floor(barPhase.coerceIn(0f, 0.9999f) * 8f).toInt().coerceIn(0, 7)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..7) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            i == idx -> AccentBright
                            i % 2 == 0 -> Color(0xFF383E49)
                            else -> Color(0xFF262A32)
                        }
                    )
            )
        }
    }
}

@Composable
private fun LogoMark() {
    androidx.compose.foundation.Image(
        painter = LocalUiPlatform.current.logoPainter(),
        contentDescription = null,
        modifier = Modifier
            .size(34.dp)
            // blagi sjaj iza loga (meki krug svjetla)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Accent.copy(alpha = 0.30f), Accent.copy(alpha = 0.10f), Color.Transparent),
                        center = center, radius = size.minDimension * 0.85f
                    ),
                    radius = size.minDimension * 0.85f
                )
            }
    )
}

// ---------------------------------------------------------------- sat seta i baterija

internal val ClockWarn = MuteOrange                 // zadnjih 5 minuta
internal val ClockOver = Color(0xFFD9625B)          // prekoračeno

/**
 * Sat seta u lijevoj koloni (iznad sata): "SET", proteklo vrijeme (ili preostalo / "+m:ss" prekoračeno), ispod cilj.
 * Narandžast zadnjih 5 minuta, crven poslije. Bez zvuka i bez dijaloga; dodir otvara podešavanje.
 */
@Composable
internal fun SetClockRail(c: SetClockState, onClick: () -> Unit) {
    val color = when {
        c.over -> ClockOver
        c.warning -> ClockWarn
        else -> AccentBright   // mint kao sat (korisnik 22. 11. 2026)
    }
    val rem = c.remaining
    val main = when {
        c.over -> "+" + SetClockState.format(-rem!!)
        c.showRemaining && rem != null -> SetClockState.format(rem)
        else -> SetClockState.format(c.elapsed.coerceAtLeast(0))
    }
    val sub = when {
        rem == null -> null
        c.over -> "over"
        c.showRemaining -> "left"
        else -> "/ " + SetClockState.format(c.targetMin * 60)
    }
    val shape = rShape(12)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Lcd)
            .border(1.dp, if (c.over || c.warning) color.copy(alpha = 0.5f) else Color.Transparent, shape)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("SET", color = TextFaint, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(
            main, color = color, fontSize = 15.sp, maxLines = 1,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFonts.inter, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
        )
        if (sub != null) Text(sub, color = TextFaint, fontSize = 10.sp, maxLines = 1, fontFamily = AppFonts.inter, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
    }
}

/** Baterija (Android): ikonica sa nivoom, procenat; narandžasto ispod 20 %, crveno ispod 10 %, ⚡ kad se puni. */
@Composable
internal fun BatteryView(b: BatteryInfo, compact: Boolean) {
    val color = when {
        b.charging -> Accent
        b.percent < 10 -> ClockOver
        b.percent < 20 -> ClockWarn
        else -> TextDim
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.width(22.dp).height(11.dp)) {
            val stroke = 1.2.dp.toPx()
            val tip = 2.dp.toPx()
            val bodyW = size.width - tip
            drawRoundRect(
                color, topLeft = Offset(stroke / 2, stroke / 2), size = Size(bodyW - stroke, size.height - stroke),
                cornerRadius = CornerRadius(2.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
            )
            drawRect(color, topLeft = Offset(bodyW, size.height * 0.3f), size = Size(tip, size.height * 0.4f))
            val pad = stroke + 1.dp.toPx()
            val fillW = (bodyW - 2 * pad) * (b.percent / 100f)
            if (fillW > 0f) drawRect(color, topLeft = Offset(pad, pad), size = Size(fillW, size.height - 2 * pad))
        }
        // munja (punjač) nacrtana, ne emoji: emoji ima svoju žutu boju, ova je mint
        if (b.charging) Canvas(Modifier.width(7.dp).height(11.dp)) {
            val w = size.width; val h = size.height
            val bolt = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.62f, 0f)
                lineTo(0f, h * 0.58f)
                lineTo(w * 0.45f, h * 0.58f)
                lineTo(w * 0.35f, h)
                lineTo(w, h * 0.40f)
                lineTo(w * 0.55f, h * 0.40f)
                close()
            }
            drawPath(bolt, Accent)
        }
        Text("${b.percent}%", color = color, fontSize = if (compact) 11.sp else 12.sp, maxLines = 1)
    }
}
