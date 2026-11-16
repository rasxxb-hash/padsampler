package com.example.padsampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.model.DrumLayout

/** Malo dugme (Drums/Keys: undo, swing); [enabled] = false: sivo, ne reaguje. */
@Composable
internal fun MiniChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = rShape(8)
    Text(
        label,
        color = if (enabled) TextMain else TextFaint.copy(alpha = 0.5f),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(Lcd)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/** ↶ ↷ (undo / redo izmjena patterna). */
@Composable
internal fun UndoButtons(available: Pair<Boolean, Boolean>, onUndo: () -> Unit, onRedo: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        MiniChip("↶ undo", available.first, onUndo)
        MiniChip("↷", available.second, onRedo)
    }
}

/** SWING − 20% + (korak 5 %). */
@Composable
internal fun SwingControl(swing: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("SWING", color = TextFaint, fontSize = 11.sp, letterSpacing = 1.sp)
        MiniChip("−", swing > 0) { onChange(swing - DrumLayout.SWING_STEP) }
        Text(
            "$swing%", color = if (swing > 0) AccentBright else TextMain, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.width(38.dp)
        )
        MiniChip("+", swing < DrumLayout.MAX_SWING) { onChange(swing + DrumLayout.SWING_STEP) }
    }
}

/**
 * Traka jačine za 16 koraka prikazanog takta, poravnata sa mrežom iznad (isti razmak [gap] i odvajanje
 * četvrtina). [values]: jačina koraka ili null (nema note). Dodir ili povlačenje gore/dolje postavlja jačinu;
 * povlačenje preko više koraka mijenja svaki preko kojeg prst pređe.
 */
@Composable
internal fun VelocityLane(
    values: List<Float?>,
    color: Color,
    gap: Dp,
    beatGap: Dp,
    onSet: (index: Int, velocity: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val gapPx = with(density) { gap.toPx() }
        val n = DrumLayout.STEPS_PER_BAR
        // gest se ne smije restartovati dok se vrijednosti mijenjaju (povlačenje)
        val curValues by rememberUpdatedState(values)
        val curOnSet by rememberUpdatedState(onSet)
        val slot = (w + gapPx) / n
        fun set(x: Float, y: Float) {
            val i = (x / slot).toInt().coerceIn(0, n - 1)
            if (curValues.getOrNull(i) != null) curOnSet(i, (1f - y / h).coerceIn(0f, 1f))
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slot, h) { detectTapGestures { set(it.x, it.y) } }
                .pointerInput(slot, h) {
                    detectDragGestures(onDragStart = { set(it.x, it.y) }) { change, _ ->
                        set(change.position.x, change.position.y)
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            for (i in 0 until n) {
                val v = values.getOrNull(i)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = if (i % 4 == 0 && i > 0) beatGap else 0.dp)
                        .clip(rShape(4))
                        .background(Lcd),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (v != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(v.coerceIn(0.04f, 1f))
                                .background(color.copy(alpha = 0.8f))
                        )
                    }
                }
            }
        }
    }
}
