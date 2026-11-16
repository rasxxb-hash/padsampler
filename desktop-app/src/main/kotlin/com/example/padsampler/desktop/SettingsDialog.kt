package com.example.padsampler.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.SamplerViewModel
import com.example.padsampler.ui.Accent
import com.example.padsampler.ui.AccentBright
import com.example.padsampler.ui.Lcd
import com.example.padsampler.ui.TextDim
import com.example.padsampler.ui.TextFaint
import com.example.padsampler.ui.TextMain
import com.example.padsampler.ui.rShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BUFFERS = listOf(64, 128, 256, 512, 1024)
private val RATES = listOf(44100, 48000, 88200, 96000)

/** Podešavanja na računaru: audio izlaz (API, uređaj, bafer, frekvencija), MIDI kontroler, full screen. */
@Composable
fun DesktopSettingsDialog(
    platform: DesktopPlatform,
    vm: SamplerViewModel,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // ---- audio: početne vrijednosti = ono što trenutno svira (ili sačuvano)
    val running = remember { DesktopAudio.currentOutput() }
    val apis = remember { DesktopAudio.availableApis().filter { it != "null" } }
    var api by remember { mutableStateOf(running.first.ifBlank { platform.getString(DesktopSettings.AUDIO_API, "wasapi") }) }
    var device by remember { mutableStateOf(running.second.ifBlank { platform.getString(DesktopSettings.AUDIO_DEVICE) }) }
    var buffer by remember { mutableStateOf(platform.getInt(DesktopSettings.AUDIO_BUFFER, 0).takeIf { it > 0 } ?: 256) }
    var rate by remember { mutableStateOf(platform.getInt(DesktopSettings.AUDIO_RATE, 0)) }   // 0 = auto
    var devices by remember { mutableStateOf<List<AudioDevice>?>(null) }
    var audioStatus by remember { mutableStateOf(statusText()) }
    var applying by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        devices = null
        val list = withContext(Dispatchers.IO) { runCatching { DesktopAudio.outputDevices(api) }.getOrDefault(emptyList()) }
        devices = list
        if (list.none { it.name == device }) device = (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.name ?: ""
    }

    // ---- MIDI
    val midi = platform.midi
    var midiDevices by remember { mutableStateOf<List<String>>(emptyList()) }
    var midiSel by remember { mutableStateOf(midi?.selectedDevice ?: "") }
    var midiRefresh by remember { mutableStateOf(0) }
    LaunchedEffect(midiRefresh) {
        midiDevices = withContext(Dispatchers.IO) { runCatching { DesktopMidi.availableDevices() }.getOrDefault(emptyList()) }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ------------------------------------------------ audio
                Section("AUDIO OUTPUT")
                Text(audioStatus, color = TextDim, fontSize = 12.sp)
                Label("Driver")
                ChipRow {
                    for (a in apis) Chip(DesktopAudio.apiLabel(a), api == a) { api = a }
                }
                Label("Device")
                val list = devices
                when {
                    list == null -> Text("Searching...", color = TextDim, fontSize = 12.sp)
                    list.isEmpty() -> Text(
                        if (api == "asio") "No ASIO driver found (install the interface's ASIO driver)." else "No output devices.",
                        color = TextDim, fontSize = 12.sp
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (d in list) Choice(d.name + if (d.isDefault) "  (default)" else "", device == d.name) { device = d.name }
                    }
                }
                Label("Buffer (frames) · smaller = lower latency, more risk of clicks")
                ChipRow {
                    for (b in BUFFERS) Chip("$b", buffer == b) { buffer = b }
                }
                Label("Sample rate")
                val supported = list?.firstOrNull { it.name == device }?.rates.orEmpty()
                ChipRow {
                    Chip("Auto", rate == 0) { rate = 0 }
                    for (r in RATES) {
                        if (supported.isEmpty() || r in supported) Chip("%.1f kHz".format(r / 1000.0), rate == r) { rate = r }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        enabled = !applying && device.isNotBlank(),
                        onClick = {
                            applying = true
                            audioStatus = "Restarting audio..."
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { DesktopAudio.apply(api, device, buffer, rate) }
                                if (ok) {
                                    platform.putString(DesktopSettings.AUDIO_API, api)
                                    platform.putString(DesktopSettings.AUDIO_DEVICE, device)
                                    platform.putInt(DesktopSettings.AUDIO_BUFFER, buffer)
                                    platform.putInt(DesktopSettings.AUDIO_RATE, rate)
                                }
                                vm.refreshAudioInfo()
                                audioStatus = (if (ok) "" else "Could not open this device, previous output restored. ") + statusText()
                                // Drajver može resetovati stream odmah poslije starta (npr. druga frekvencija); motor
                                // ga ponovo otvori, pa se stanje pročita još jednom.
                                delay(1800)
                                vm.refreshAudioInfo()
                                if (ok) audioStatus = statusText()
                                applying = false
                            }
                        }
                    ) { Text("Apply audio settings") }
                }

                // ------------------------------------------------ MIDI
                Section("MIDI CONTROLLER")
                Text("Launchpad Mini MK3 (programmer mode). Auto finds it by name.", color = TextDim, fontSize = 12.sp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Choice("Auto (Launchpad)", midiSel.isBlank()) {
                        midiSel = ""
                        platform.putString(DesktopSettings.MIDI_DEVICE, "")
                        midi?.select(null)
                    }
                    for (m in midiDevices) Choice(m, midiSel == m) {
                        midiSel = m
                        platform.putString(DesktopSettings.MIDI_DEVICE, m)
                        midi?.select(m)
                    }
                }
                TextButton(onClick = { midiRefresh++ }) { Text("Refresh list") }

                // ------------------------------------------------ ekran
                Section("SCREEN")
                ChipRow {
                    Chip("Window", !fullscreen) { onFullscreen(false) }
                    Chip("Full screen (F11)", fullscreen) { onFullscreen(true) }
                }
                Text("The screen does not turn off while PadSampler is running.", color = TextDim, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

private fun statusText(): String {
    val (api, dev) = DesktopAudio.currentOutput()
    val notice = DesktopAudio.notice().let { if (it.isBlank()) "" else "$it. " }
    if (api.isBlank()) return notice + "Audio is not running."
    return notice + "Now: ${DesktopAudio.apiLabel(api)} · $dev · ${com.example.padsampler.audio.NativeAudio.sampleRate()} Hz · " +
        "buffer ${com.example.padsampler.audio.NativeAudio.bufferSize()} · ~${"%.1f".format(DesktopAudio.latencyMs())} ms"
}

@Composable
private fun Section(title: String) {
    Text(title, color = AccentBright, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, color = TextFaint, fontSize = 11.sp)
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = rShape(9)
    Text(
        label,
        color = if (selected) AccentBright else TextMain,
        fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .background(if (selected) Color(0xFF1D2127) else Lcd, shape)
            .border(1.dp, if (selected) Accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f), shape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = rShape(9)
    Text(
        (if (selected) "●  " else "○  ") + label,
        color = if (selected) AccentBright else TextMain,
        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF1D2127) else Color.Transparent, shape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
