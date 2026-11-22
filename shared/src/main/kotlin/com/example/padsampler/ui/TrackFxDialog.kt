package com.example.padsampler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Efekti jednog kanala miksera (dugme FX): SPACE = koliko kanal šalje u zajednički reverb i delay (vezan za tempo),
 * EQ = LOW / MID / HIGH samo za ovaj kanal (-12..+12 dB). Dupli dodir na potenciometar = nula. Pamti se u sesiji.
 */
@Composable
fun TrackFxDialog(
    name: String,
    color: Color,
    reverb: Float,
    delay: Float,
    eq: List<Float>,
    onReverb: (Float) -> Unit,
    onDelay: (Float) -> Unit,
    onEq: (Int, Float) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit
) {
    val compact = LocalUiPlatform.current.screenWidthDp() < 1000
    val knob = if (compact) 48.dp else 64.dp
    val send: (Float) -> String = { v -> val p = (v * 100).toInt(); if (p == 0) "OFF" else "$p" }
    val db: (Float) -> String = { v ->
        val d = (v * 2f - 1f) * 12f
        when {
            kotlin.math.abs(d) < 0.25f -> "0 dB"
            d > 0 -> "+${"%.1f".format(d)} dB"
            else -> "${"%.1f".format(d)} dB"
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.padding(vertical = 12.dp).widthIn(max = 760.dp).fillMaxWidth(0.94f)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("$name  ·  FX", style = MaterialTheme.typography.titleLarge, color = AlertDialogDefaults.titleContentColor)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 28.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SPACE", color = TextFaint, fontSize = 10.sp, letterSpacing = 1.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            SimpleKnob("REVERB", reverb, color, default = 0f, format = send,
                                onChange = onReverb, onRelease = onReverb, knobSize = knob)
                            SimpleKnob("DELAY", delay, color, default = 0f, format = send,
                                onChange = onDelay, onRelease = onDelay, knobSize = knob)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("EQ", color = TextFaint, fontSize = 10.sp, letterSpacing = 1.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            for ((band, label) in listOf("LOW", "MID", "HIGH").withIndex()) {
                                SimpleKnob(label, (eq[band] + 1f) / 2f, color, default = 0.5f, bipolar = true, format = db,
                                    onChange = { onEq(band, it * 2f - 1f) }, onRelease = { onEq(band, it * 2f - 1f) },
                                    knobSize = knob)
                            }
                        }
                    }
                }
                Text(
                    "Reverb and delay are shared by all tracks; the knob sets how much of this track goes into them " +
                        "(more reverb = further away). Delay follows the tempo (1/8 dotted). Double-tap a knob to reset it.",
                    color = TextDim, fontSize = 12.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onReset) { Text("Reset", color = TextDim) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close", color = Accent) }
                }
            }
        }
    }
}
