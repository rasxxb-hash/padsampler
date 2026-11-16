package com.example.padsampler.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Red koji se skroluje lijevo-desno: prstom (kao horizontalScroll), a mišem i točkićem (gore/dolje = lijevo/desno)
 * i povlačenjem sa pritisnutim lijevim dugmetom (Compose Desktop to sam ne radi). Povlačenje poništi klik na dugme
 * ispod miša.
 */
@Composable
fun Modifier.hScroll(state: ScrollState = rememberScrollState()): Modifier =
    this.mouseScroll(state).horizontalScroll(state)

private fun Modifier.mouseScroll(state: ScrollState): Modifier = pointerInput(state) {
    val slop = viewConfiguration.touchSlop
    val wheelStep = 48.dp.toPx()
    awaitPointerEventScope {
        var downX = Float.NaN
        var lastX = 0f
        var dragging = false
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull() ?: continue
            if (ch.type != PointerType.Mouse) continue
            when (ev.type) {
                PointerEventType.Scroll -> if (!ch.isConsumed) {
                    val d = if (ch.scrollDelta.x != 0f) ch.scrollDelta.x else ch.scrollDelta.y
                    if (d != 0f && state.maxValue > 0) {
                        state.dispatchRawDelta(d * wheelStep)
                        ch.consume()
                    }
                }
                PointerEventType.Press -> {
                    downX = ch.position.x
                    lastX = downX
                    dragging = false
                }
                PointerEventType.Move -> if (!downX.isNaN() && ch.pressed) {
                    if (!dragging && state.maxValue > 0 && abs(ch.position.x - downX) > slop) dragging = true
                    if (dragging) {
                        state.dispatchRawDelta(lastX - ch.position.x)
                        ch.consume()
                    }
                    lastX = ch.position.x
                }
                PointerEventType.Release -> {
                    if (dragging) ch.consume()
                    downX = Float.NaN
                    dragging = false
                }
            }
        }
    }
}
