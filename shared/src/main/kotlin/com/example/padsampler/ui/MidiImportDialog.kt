package com.example.padsampler.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.SamplerViewModel
import com.example.padsampler.model.KeysLayout

/** IMPORT MIDI (Keys i Drums): fajl ima više instrumenata, bira se jedan. */
@Composable
internal fun MidiPartDialog(vm: SamplerViewModel, choice: SamplerViewModel.MidiChoice) {
    AlertDialog(
        onDismissRequest = { vm.cancelMidiChoice() },
        title = { Text(if (choice.drums) "Import MIDI to Drums: choose a part" else "Import MIDI: choose a part") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                choice.song.parts.forEachIndexed { i, p ->
                    val lo = KeysLayout.noteName(p.notes.minOf { it.pitch })
                    val hi = KeysLayout.noteName(p.notes.maxOf { it.pitch })
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(rShape(8))
                            .clickable { vm.applyMidiPart(choice, i) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(p.name, color = if (p.isDrums && choice.drums) AccentBright else TextMain, fontSize = 14.sp)
                        Text("${p.notes.size} notes  ·  $lo–$hi", color = TextFaint, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.cancelMidiChoice() }) { Text("Cancel") } }
    )
}
