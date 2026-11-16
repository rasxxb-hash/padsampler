package com.example.padsampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.SamplerViewModel

/**
 * Set lista: sesije poredane za nastup. Dodir na pjesmu je učitava (kao "load session"), Prev/Next idu redom.
 * Samo na ekranu (Launchpad nema dugme za ovo).
 */
@Composable
fun SetListDialog(vm: SamplerViewModel, onClose: () -> Unit) {
    val setList by vm.setList.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val byDir = sessions.associateBy { it.dirName }
    val current = setList.current
    val currentName = setList.items.getOrNull(current)?.let { byDir[it]?.name }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Set list") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 360.dp, max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ---- Prev / trenutna / Next
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("◀  Prev", enabled = current > 0) { vm.setListPrev() }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (currentName != null) "${current + 1} / ${setList.items.size}" else "—",
                            color = TextDim, fontSize = 11.sp
                        )
                        Text(
                            currentName ?: "No song loaded",
                            color = if (currentName != null) AccentBright else TextDim,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    BigButton("Next  ▶", enabled = current + 1 < setList.items.size) { vm.setListNext() }
                }
                Text("Loading a song stops everything (like load session).", color = TextFaint, fontSize = 11.sp)

                // ---- pjesme
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (setList.items.isEmpty()) Text("The set list is empty. Add sessions below.", color = TextDim, fontSize = 12.sp)
                    setList.items.forEachIndexed { i, dir ->
                        val info = byDir[dir]
                        val selected = i == current
                        val shape = rShape(9)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected) Color(0xFF1D2127) else Lcd, shape)
                                .border(1.dp, if (selected) Accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.05f), shape)
                                .clickable(enabled = info != null) { vm.playSetListItem(i) }
                                .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${i + 1}", color = if (selected) AccentBright else TextDim, fontSize = 13.sp, modifier = Modifier.width(26.dp))
                            Text(
                                info?.name ?: "(missing session)",
                                color = when {
                                    info == null -> TextFaint
                                    selected -> AccentBright
                                    else -> TextMain
                                },
                                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            SmallIcon("▲", enabled = i > 0) { vm.moveInSetList(i, -1) }
                            SmallIcon("▼", enabled = i + 1 < setList.items.size) { vm.moveInSetList(i, +1) }
                            SmallIcon("✕") { vm.removeFromSetList(i) }
                        }
                    }
                }

                // ---- dodavanje
                Text("ADD SONG", color = AccentBright, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Column(
                    modifier = Modifier
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (sessions.isEmpty()) Text("No saved sessions yet (save session first).", color = TextDim, fontSize = 12.sp)
                    for (s in sessions) {
                        val count = setList.items.count { it == s.dirName }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.addToSetList(s) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("+", color = Accent, fontSize = 16.sp, modifier = Modifier.width(22.dp))
                            Text(s.name, color = TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (count > 0) Text("in list", color = TextFaint, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun BigButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = rShape(12)
    Box(
        modifier = Modifier
            .background(if (enabled) BtnTop else Lcd, shape)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.1f else 0.04f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) TextMain else TextFaint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallIcon(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) TextDim else TextFaint.copy(alpha = 0.4f), fontSize = 13.sp)
    }
}
