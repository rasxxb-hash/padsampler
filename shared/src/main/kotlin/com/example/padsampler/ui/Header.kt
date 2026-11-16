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
    onSettings: (() -> Unit)? = null
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

        // Ime sesije zauzima sav slobodan prostor između tabova i metronoma (skraćuje se trotačkom)
        Text(
            sessionName.ifBlank { "Untitled session" },
            color = TextDim, fontSize = if (compact) 12.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 4.dp)
                .weight(1f)
        )

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
