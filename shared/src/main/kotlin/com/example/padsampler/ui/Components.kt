package com.example.padsampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import com.example.padsampler.model.Fader

/** Svjetlija verzija boje (za gornji dio gradijenta dugmeta). */
fun Color.lighten(f: Float): Color = Color(
    red = red + (1f - red) * f, green = green + (1f - green) * f, blue = blue + (1f - blue) * f, alpha = alpha
)

/** Tamnija verzija boje (f < 1). */
fun Color.darken(f: Float): Color = Color(red = red * f, green = green * f, blue = blue * f, alpha = alpha)

// ---- Dubina bez prelivâ (korisnik 12. 11. 2026): udubljeno = unutrašnja sjenka, izdignuto = sjenka ispod,
// upaljeno = mali sjaj oko ivice. Sve je samo crtanje (bez slika, bez blur-a), lagano i za tablet.

/**
 * Pritisnuto dugme / tipka: samo malo tamnije, BEZ unutrašnje sjenke (korisnik 12. 11. 2026: inner shadow ne).
 * Prazni padovi i koraci su ravni (tanka ivica). Ide POSLIJE clip + background.
 */
fun Modifier.inset(shape: Shape, depth: Float = 1f): Modifier = drawBehind {
    drawOutline(shape.createOutline(size, layoutDirection, this), ShadowTint.copy(alpha = 0.30f * depth))
}

private const val SHADOW_LAYERS = 6

/** Boja sjenke: malo tamnija od podloge (ne crna), sa providnošću, da je sjenka blaga. */
private val ShadowTint = Color(0xFF050608)

/** Izdignuto (dugme, pad sa semplom, panel): meka sjenka jednako okolo (svjetlo odozgo). Ide PRIJE clip-a. */
fun Modifier.raised(shape: Shape, elevation: Dp = 2.dp): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val e = elevation.toPx()
    // potez je centriran na ivici: spoljna polovina je sjenka, unutrašnju pokrije pozadina.
    // Mnogo tankih slojeva sve šire: uz ivicu najtamnije, prema van postepeno nestaje (razliveno).
    for (i in 1..SHADOW_LAYERS) {
        drawOutline(outline, ShadowTint.copy(alpha = 0.016f), style = Stroke(e * 1.6f * i))   // skoro neprimjetno
    }
}

/** Upaljeno (korak, nota, M/S): mali sjaj boje oko ivice. Ide PRIJE clip-a. */
fun Modifier.glow(shape: Shape, color: Color, strength: Float = 1f): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, color.copy(alpha = 0.10f * strength), style = Stroke(9.dp.toPx()))
    drawOutline(outline, color.copy(alpha = 0.22f * strength), style = Stroke(4.dp.toPx()))
}

/** Pad koji svira (Session): sjaj iznutra i reljef; samo za to stanje (korisniku se sviđa). */
fun Modifier.bevel(
    shape: Shape,
    strength: Float = 1f,
    pressed: Boolean = false,
    glow: Boolean = false
): Modifier = this
    .drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        if (pressed) {
            drawOutline(outline, Color.Black.copy(alpha = 0.22f))
            drawOutline(
                outline,
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.18f), 0.35f to Color.Transparent, endY = size.height
                )
            )
            return@drawBehind
        }
        drawOutline(
            outline,
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.075f * strength),
                0.45f to Color.Transparent,
                0.6f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.20f * strength),
                endY = size.height
            )
        )
        if (glow) {
            drawOutline(
                outline,
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.4f),
                    radius = maxOf(size.width, size.height) * 0.6f
                )
            )
        }
    }
    .border(
        1.dp,
        if (pressed) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.White.copy(alpha = 0.05f)))
        else Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.13f * strength),
            0.5f to Color.White.copy(alpha = 0.03f),
            1f to Color.Black.copy(alpha = 0.45f * strength)
        ),
        shape
    )

/** Niža dugmad (FlatButton, StudioChip): gornja traka DRUMS i KEYS na telefonu. */
val LocalDense = androidx.compose.runtime.compositionLocalOf { false }

/** Veliko dugme sa tekstom: blagi gradijent, tanka svijetla ivica. */
@Composable
fun FlatButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = PanelColor,
    textColor: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = rShape(12)
    val face = if (color == PanelColor) BtnTop else color
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val down = pressed || !enabled
    Box(
        modifier = modifier
            .then(if (down) Modifier else Modifier.raised(shape))
            .clip(shape)
            .background(if (enabled) face else BtnBottom)
            .then(
                if (down) Modifier.inset(shape, if (enabled) 1f else 0.5f)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            )
            .then(if (enabled) Modifier.clickable(interactionSource = source, indication = null) { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = if (LocalDense.current) 5.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) textColor else TextDim.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            maxLines = 1
        )
    }
}

/** Mala dugmad M i S na kanalu. */
@Composable
fun StripButton(
    label: String, on: Boolean, onColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit,
    height: androidx.compose.ui.unit.Dp = 40.dp
) {
    val shape = rShape(10)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(height)
            .then(
                when {
                    pressed -> Modifier
                    on -> Modifier.glow(shape, onColor)
                    else -> Modifier.raised(shape)
                }
            )
            .clip(shape)
            .background(if (on) onColor else BtnTop)
            .then(
                if (pressed) Modifier.inset(shape)
                else Modifier.border(1.dp, Color.White.copy(alpha = if (on) 0.18f else 0.06f), shape)
            )
            .clickable(interactionSource = source, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) Ink else TextDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MuteButton(muted: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    StripButton("M", muted, MuteYellow, modifier, onClick)

private const val SEGMENTS = 20

/**
 * Kanal miksera: ime (sa linijom u boji kolone), LED meter, fader sa kapom, dB oznaka, M i (opciono) S.
 * Dodir ili povlačenje fadera postavlja vrijednost odmah.
 */
@Composable
fun ChannelStrip(
    name: String,
    color: Color,
    value: Float,
    meter: Float,
    muted: Boolean,
    soloed: Boolean,
    showSolo: Boolean,
    onChange: (Float) -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    /** Pan -1..1 (null = bez potenciometra, npr. master). */
    pan: Float? = null,
    onPan: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val shape = rShape(16)
    // uski ekran (telefon): sve manje, slajder dobija preostali prostor
    val compact = LocalUiPlatform.current.screenWidthDp() < 1000
    Column(
        modifier = modifier
            .raised(shape, 3.dp)
            .clip(shape)
            .background(HeaderTop)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(horizontal = if (compact) 4.dp else 10.dp, vertical = if (compact) 6.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            name, color = TextMain, fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(rShape(2))
                .background(color)
        )
        // PAN: lijevo-desno (dupli dodir = sredina)
        if (pan != null && !compact) {   // telefon: bez PAN-a (pan iz sesije i dalje važi)
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            SimpleKnob(
                "PAN", (pan + 1f) / 2f, color, default = 0.5f, bipolar = true,
                format = { v ->
                    val p = ((v * 2f - 1f) * 100).toInt()
                    when { p == 0 -> "C"; p < 0 -> "L${-p}"; else -> "R$p" }
                },
                onChange = { onPan(it * 2f - 1f) },
                onRelease = { onPan(it * 2f - 1f) },
                knobSize = if (compact) 28.dp else 40.dp,
                showValue = !compact   // telefon: bez C/L/R ispod (više mjesta za slajder)
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = if (compact) 4.dp else 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            // LED meter
            Canvas(
                Modifier
                    .width(if (compact) 6.dp else 14.dp)
                    .fillMaxHeight()
            ) {
                val gap = (if (compact) 2 else 3).dp.toPx()
                val segH = (size.height - gap * (SEGMENTS - 1)) / SEGMENTS
                val lit = (meter.coerceIn(0f, 1f) * SEGMENTS + 0.0001f).toInt()
                for (i in 0 until SEGMENTS) {
                    val y = size.height - (i + 1) * segH - i * gap
                    val c = when {
                        i >= 17 -> StopRed
                        i >= 14 -> MuteYellow
                        else -> MeterGreen
                    }
                    drawRoundRect(
                        color = if (i < lit) c else Lcd,
                        topLeft = Offset(0f, y),
                        size = Size(size.width, segH),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
            Spacer(Modifier.width(if (compact) 4.dp else 10.dp))
            // fader (na uskom ekranu zauzme ostatak širine kanala, najviše 46 dp)
            val capH = if (compact) 26.dp else 30.dp
            Box(
                modifier = Modifier
                    .then(if (compact) Modifier.weight(1f, fill = false).widthIn(max = 46.dp) else Modifier.width(46.dp))
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        val capPx = capH.toPx()
                        val pad = 4.dp.toPx()
                        fun valueAt(y: Float) = 1f - ((y - pad - capPx / 2f) / (size.height - 2 * pad - capPx))
                        detectTapGestures(onPress = { o -> onChange(valueAt(o.y).coerceIn(0f, 1f)) })
                    }
                    .pointerInput(Unit) {
                        val capPx = capH.toPx()
                        val pad = 4.dp.toPx()
                        fun valueAt(y: Float) = 1f - ((y - pad - capPx / 2f) / (size.height - 2 * pad - capPx))
                        detectVerticalDragGestures(
                            onDragStart = { o -> onChange(valueAt(o.y).coerceIn(0f, 1f)) },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                onChange(valueAt(change.position.y).coerceIn(0f, 1f))
                            }
                        )
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val capPx = capH.toPx()
                    val pad = 4.dp.toPx()
                    val travel = size.height - 2 * pad - capPx
                    val v = value.coerceIn(0f, 1f)
                    val capTop = pad + travel * (1f - v)
                    // vodilica
                    val slotW = 8.dp.toPx()
                    drawRoundRect(
                        color = Lcd,
                        topLeft = Offset((size.width - slotW) / 2f, pad),
                        size = Size(slotW, size.height - 2 * pad),
                        cornerRadius = CornerRadius(slotW / 2f)
                    )
                    // oznake
                    for (k in 0..6) {
                        val ty = pad + capPx / 2f + travel * k / 6f
                        drawRect(PanelBorder, Offset(0f, ty), Size(6.dp.toPx(), 1.dp.toPx()))
                    }
                    // sjenka kape jednako okolo (svjetlo odozgo)
                    for ((grow, a) in listOf(4f to 0.07f, 2f to 0.13f)) {
                        val g = grow.dp.toPx()
                        drawRoundRect(
                            color = Color.Black.copy(alpha = a),
                            topLeft = Offset(-g, capTop - g),
                            size = Size(size.width + 2 * g, capPx + 2 * g),
                            cornerRadius = CornerRadius(7.dp.toPx() + g)
                        )
                    }
                    // kapa
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFF525967), Color(0xFF2D323C)), startY = capTop, endY = capTop + capPx
                        ),
                        topLeft = Offset(0f, capTop),
                        size = Size(size.width, capPx),
                        cornerRadius = CornerRadius(7.dp.toPx())
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.14f),
                        topLeft = Offset(0f, capTop),
                        size = Size(size.width, capPx),
                        cornerRadius = CornerRadius(7.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                    // urezi za prst (iznad i ispod linije u boji)
                    for (g in listOf(-8, -5, 5, 8)) {
                        val gy = capTop + capPx / 2f + g.dp.toPx()
                        drawRect(Color.Black.copy(alpha = 0.35f), Offset(10.dp.toPx(), gy), Size(size.width - 20.dp.toPx(), 1.dp.toPx()))
                        drawRect(Color.White.copy(alpha = 0.08f), Offset(10.dp.toPx(), gy + 1.dp.toPx()), Size(size.width - 20.dp.toPx(), 1.dp.toPx()))
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(8.dp.toPx(), capTop + capPx / 2f - 1.dp.toPx()),
                        size = Size(size.width - 16.dp.toPx(), 2.dp.toPx())
                    )
                }
            }
        }
        Text("${Fader.label(value)} dB", color = TextDim, fontSize = if (compact) 10.sp else 12.sp, maxLines = 1)
        Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
            val h = if (compact) 32.dp else 40.dp
            StripButton("M", muted, MuteYellow, Modifier.weight(1f), onMute, h)
            if (showSolo) StripButton("S", soloed, SoloBlue, Modifier.weight(1f), onSolo, h)
        }
    }
}
