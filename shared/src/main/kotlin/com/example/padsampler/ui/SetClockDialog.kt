package com.example.padsampler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.model.SetClock
import com.example.padsampler.model.SetClockState
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** Trenutno vrijeme za gornju traku (24 h). */
fun currentTimeText(): String = LocalTime.now().format(HM)

/** Dodir na sat seta: trajanje seta, prikaz proteklo/preostalo, reset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetClockDialog(clock: SetClock, s: SetClockState, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Set timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (s.started) "Running: ${SetClockState.format(s.elapsed)} since the first clip started."
                    else "Starts by itself when the first clip, Drums or Keys starts playing.",
                    color = TextDim, fontSize = 13.sp
                )
                Text("SET LENGTH", color = TextFaint, fontSize = 10.sp, letterSpacing = 1.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (m in SetClockState.TARGETS) {
                        StudioChip(if (m == 0) "off" else "$m min", s.targetMin == m) { clock.setTarget(m) }
                    }
                }
                if (s.targetMin > 0) {
                    Text("SHOW", color = TextFaint, fontSize = 10.sp, letterSpacing = 1.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StudioChip("elapsed", !s.showRemaining) { clock.setShowRemaining(false) }
                        StudioChip("remaining", s.showRemaining) { clock.setShowRemaining(true) }
                    }
                    Text(
                        "The last 5 minutes turn orange; after the end it turns red and counts the extra time (+2:10).",
                        color = TextFaint, fontSize = 12.sp
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = s.started, onClick = { clock.reset() }) { Text("Reset") }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
