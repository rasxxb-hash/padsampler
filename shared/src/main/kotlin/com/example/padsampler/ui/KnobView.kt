package com.example.padsampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crta potenciometar (Keys i Drums): tamno dugme osvijetljeno odozgo, luk vrijednosti [pos] 0..1 u boji [color],
 * bijela crta položaja. [bipolar] = luk od sredine (npr. detune).
 */
internal fun DrawScope.drawKnob(pos: Float, bipolar: Boolean, color: Color) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val start = 135f
    val sweep = 270f
    val stroke = 3.dp.toPx()
    val arcSize = Size(2 * r - stroke, 2 * r - stroke)
    val tl = Offset(c.x - r + stroke / 2, c.y - r + stroke / 2)
    // dugme: sjenka jednako okolo (svjetlo odozgo), tijelo, ivica (svijetla gore, tamna dolje)
    val rb = r - stroke * 1.6f
    drawCircle(Color.Black.copy(alpha = 0.10f), radius = rb + 3.dp.toPx(), center = c)
    drawCircle(Color.Black.copy(alpha = 0.22f), radius = rb + 1.5.dp.toPx(), center = c)
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFF4D535E), Color(0xFF2B2F37), Color(0xFF16181D)),
            center = c + Offset(-rb * 0.35f, -rb * 0.45f), radius = rb * 1.6f
        ),
        radius = rb, center = c
    )
    drawCircle(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.5f)),
            startY = c.y - rb, endY = c.y + rb
        ),
        radius = rb, center = c, style = Stroke(1.dp.toPx())
    )
    drawArc(Color.White.copy(alpha = 0.08f), start, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    val from = if (bipolar) start + sweep / 2 else start
    val to = start + sweep * pos.coerceIn(0f, 1f)
    drawArc(color, minOf(from, to), kotlin.math.abs(to - from), false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    val ang = (to * PI / 180.0)
    val inner = r * 0.25f
    val outer = r - stroke * 1.8f
    drawLine(
        Color.White.copy(alpha = 0.85f),
        Offset(c.x + inner * cos(ang).toFloat(), c.y + inner * sin(ang).toFloat()),
        Offset(c.x + outer * cos(ang).toFloat(), c.y + outer * sin(ang).toFloat()),
        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
    )
}

/**
 * Jednostavan potenciometar 0..1: povuci gore/dolje ([onChange] dok se vuče, [onRelease] kad se pusti);
 * dupli dodir = [default].
 */
@Composable
internal fun SimpleKnob(
    label: String,
    value: Float,
    color: Color,
    default: Float = 0.5f,
    bipolar: Boolean = false,
    format: (Float) -> String = { "${(it * 100).toInt()}" },
    onChange: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    knobSize: androidx.compose.ui.unit.Dp = 40.dp,
    showValue: Boolean = true
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val release by rememberUpdatedState(onRelease)
    var dragPos by remember { mutableStateOf(0f) }
    Column(modifier = Modifier.width(knobSize + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextDim, fontSize = 9.sp, maxLines = 1, letterSpacing = 0.5.sp)
        Canvas(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { release(default) }) }
                .pointerInput(Unit) {
                    val range = 160.dp.toPx()
                    detectVerticalDragGestures(
                        onDragStart = { dragPos = current },
                        onDragEnd = { release(dragPos) },
                        onDragCancel = { release(dragPos) },
                        onVerticalDrag = { ch, dy ->
                            ch.consume()
                            dragPos = (dragPos - dy / range).coerceIn(0f, 1f)
                            change(dragPos)
                        }
                    )
                }
        ) { drawKnob(value, bipolar, color) }
        if (showValue) Text(format(value), color = TextMain, fontSize = 9.sp, maxLines = 1)
    }
}
