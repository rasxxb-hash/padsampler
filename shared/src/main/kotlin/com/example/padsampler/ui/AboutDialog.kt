package com.example.padsampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.data.Legal

/**
 * ☰ -> About: verzija, autorska prava, GPL napomena, izvorni kod i licence tuđih biblioteka (GPL traži da korisnik
 * binarne verzije, npr. sa Google Playa, zna za licencu i gdje je izvorni kod).
 */
@Composable
fun AboutDialog(onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    // otvoren tekst licence: (naslov, tekst)
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = 560.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("PadSampler", color = TextMain, fontSize = 24.sp, fontFamily = AppFonts.audiowide)
                Text("Version ${Legal.version}", color = TextDim, fontSize = 13.sp)
                Text(Legal.COPYRIGHT, color = TextMain, fontSize = 14.sp)
                Spacer(Modifier.padding(2.dp))
                Text(
                    "PadSampler is free software: you can redistribute it and/or modify it under the terms of the " +
                        "GNU General Public License as published by the Free Software Foundation, either version 3 " +
                        "of the License, or (at your option) any later version. It is distributed in the hope that " +
                        "it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of " +
                        "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.",
                    color = TextDim, fontSize = 12.sp, lineHeight = 17.sp
                )
                LinkRow("Source code", Legal.SOURCE_URL.removePrefix("https://")) { uri.openUri(Legal.SOURCE_URL) }
                LinkRow("Website", Legal.WEBSITE_URL.removePrefix("https://")) { uri.openUri(Legal.WEBSITE_URL) }
                LinkRow("License", "GNU GPL v3 (full text)") {
                    shown = "GNU General Public License v3" to (Legal.read("LICENSE") ?: "License text missing in this build.")
                }

                Section("THIRD-PARTY SOFTWARE AND FONTS")
                for (c in Legal.components) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(rShape(8))
                            .clickable {
                                val body = c.file?.let { Legal.read(it) } ?: c.note ?: ""
                                shown = c.name to "${c.copyright}\nLicense: ${c.license}\n\n$body"
                            }
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    ) {
                        Text(c.name, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${c.purpose} · ${c.license}", color = TextDim, fontSize = 11.sp)
                    }
                }
                Section("TRADEMARKS")
                Text(Legal.TRADEMARKS, color = TextDim, fontSize = 11.sp, lineHeight = 15.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )

    shown?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(title) },
            text = {
                Column(
                    Modifier
                        .widthIn(min = 320.dp, max = 640.dp)
                        .heightIn(max = 520.dp)
                        .clip(rShape(8))
                        .background(Lcd)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(body, color = TextMain, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("Back") } }
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, color = TextFaint, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(rShape(8))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextDim, fontSize = 12.sp, modifier = Modifier.widthIn(min = 96.dp))
        Text(value, color = AccentBright, fontSize = 13.sp)
    }
}
