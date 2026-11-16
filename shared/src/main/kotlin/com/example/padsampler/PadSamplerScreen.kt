package com.example.padsampler

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.padsampler.data.SessionInfo
import com.example.padsampler.model.Fader
import com.example.padsampler.model.Layout
import com.example.padsampler.model.Screen
import com.example.padsampler.ui.AppBackground
import com.example.padsampler.ui.AppBackgroundTop
import com.example.padsampler.ui.BrowserScreen
import com.example.padsampler.ui.EditBorder
import com.example.padsampler.ui.FlatButton
import com.example.padsampler.ui.MixerScreen
import com.example.padsampler.ui.AppFonts
import com.example.padsampler.ui.AppHeader
import com.example.padsampler.ui.ChannelStrip
import com.example.padsampler.ui.DrumsScreen
import com.example.padsampler.ui.KeysScreen
import com.example.padsampler.ui.PanelColor
import com.example.padsampler.ui.SessionScreen
import com.example.padsampler.ui.SideMenu
import com.example.padsampler.ui.SideRail
import com.example.padsampler.ui.MessageToast
import com.example.padsampler.ui.SetListDialog
import com.example.padsampler.ui.AboutDialog
import com.example.padsampler.ui.StopRedBottom
import com.example.padsampler.ui.TextDim
import com.example.padsampler.ui.LocalUiPlatform

@Composable
fun PadSamplerScreen(vm: SamplerViewModel) {
    val info by vm.info.collectAsState()
    val midiStatus by vm.midiStatus.collectAsState()
    val message by vm.message.collectAsState()
    val live by vm.live.collectAsState()
    val control by vm.control.collectAsState()
    val bpm by vm.bpm.collectAsState()
    val linkOn by vm.linkOn.collectAsState()
    val linkPeers by vm.linkPeers.collectAsState()
    val linkAudio by vm.linkAudio.collectAsState()
    val linkOffsetMs by vm.linkOffsetMs.collectAsState()
    val stretching by vm.stretching.collectAsState()
    val pads by vm.pads.collectAsState()
    val editMode by vm.editMode.collectAsState()
    val editSlot by vm.editSlot.collectAsState()
    val sessionName by vm.sessionName.collectAsState()
    val browserSlot by vm.browserSlot.collectAsState()
    val drumBrowserLane by vm.drumBrowserLane.collectAsState()
    var saveDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("") }
    var loadDialog by remember { mutableStateOf(false) }
    var newDialog by remember { mutableStateOf(false) }
    var bpmDialog by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    var setListDialog by remember { mutableStateOf(false) }
    var aboutDialog by remember { mutableStateOf(false) }
    // Uski ekran (telefon u landscape-u): kompaktna gornja traka i uža lijeva kolona
    val ui = LocalUiPlatform.current
    val compact = ui.screenWidthDp() < 1000

    // Izbor fajla preko platforme (Android: SAF). Fajl se kopira u privatni folder aplikacije.
    val pickFile = ui.rememberFilePicker { ref ->
        val slot = vm.editSlot.value
        if (slot != null) vm.assignSample(slot, ref)
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AppBackgroundTop, AppBackground)))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ------------------------------------------------------------ gornja traka (ista na svim ekranima)
        AppHeader(
            screen = control.screen,
            onScreen = { vm.setScreen(it) },
            sessionName = sessionName,
            stretching = stretching,
            barPhase = live.barPhase,
            linkOn = linkOn,
            linkPeers = linkPeers,
            bpm = bpm,
            onLink = { vm.toggleLink() },
            onBpmMinus = { vm.changeBpm(-1) },
            onBpmPlus = { vm.changeBpm(+1) },
            onBpmClick = { bpmDialog = true },
            compact = compact,
            onSettings = if (ui.hasSettings) ({ settingsDialog = true }) else null
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // -------------------------------------------------------- lijeva traka: ☰, edit, STOP ALL
            SideRail(
                editMode = editMode,
                onMenu = { menuOpen = true },
                onEdit = { vm.setEditMode(!editMode) },
                onStopAll = { vm.stopAll() },
                modifier = Modifier
                    .width(if (compact) 72.dp else 84.dp)
                    .fillMaxHeight()
            )

            // -------------------------------------------------------- glavni prikaz
            when (control.screen) {
                Screen.SESSION -> SessionScreen(
                    pads = pads, live = live, control = control, editMode = editMode,
                    onClip = { vm.clipTap(it) },
                    onScene = { vm.launchScene(it) },
                    onBottom = { vm.bottomPad(it) },
                    onCycleMode = { vm.cycleBottomMode() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                Screen.MIXER -> MixerScreen(
                    pads = pads, live = live, control = control,
                    onFader = { t, v -> vm.setFader(t, v) },
                    onMute = { vm.toggleMute(it) },
                    onSolo = { vm.toggleSolo(it) },
                    onPan = { t, v -> vm.setPan(t, v) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                Screen.DRUMS -> DrumsScreen(
                    vm = vm, live = live,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                Screen.KEYS -> KeysScreen(
                    vm = vm, live = live,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            // -------------------------------------------------------- master (telefon: samo na SESSION i MIXER,
            // DRUMS i KEYS trebaju širinu za mrežu)
            if (!compact || control.screen == Screen.SESSION || control.screen == Screen.MIXER) ChannelStrip(
                name = "Master",
                color = TextDim,
                value = control.master,
                meter = Fader.meter(live.masterLevel),
                muted = control.masterMute,
                soloed = false,
                showSolo = false,
                onChange = { vm.setMaster(it) },
                onMute = { vm.toggleMasterMute() },
                onSolo = {},
                modifier = Modifier
                    .width(if (compact) 76.dp else 100.dp)
                    .fillMaxHeight()
            )
        }
    }

    // ☰ meni (preko ekrana)
    if (menuOpen) {
        SideMenu(
            editMode = editMode, linkOn = linkOn, linkAudio = linkAudio, linkOffsetMs = linkOffsetMs,
            midiStatus = midiStatus, info = info,
            onClose = { menuOpen = false },
            onEdit = { vm.setEditMode(!editMode) },
            onSave = { vm.refreshSessions(); saveDialog = true },
            onExport = { vm.refreshSessions(); exportDialog = true },
            onLoad = { vm.refreshSessions(); loadDialog = true },
            onNew = { newDialog = true },
            onSetList = { vm.refreshSessions(); setListDialog = true },
            onLinkAudio = { vm.toggleLinkAudio() },
            onLinkOffset = { vm.changeLinkOffset(it) },
            onAbout = { aboutDialog = true }
        )
    }
    if (browserSlot != null || drumBrowserLane != null) BrowserScreen(vm)
    MessageToast(message, editMode && browserSlot == null && drumBrowserLane == null)

    }

    // ---------------------------------------------------------------- dijalozi za sesije
    val sessions by vm.sessions.collectAsState()
    if (newDialog) {
        AlertDialog(
            onDismissRequest = { newDialog = false },
            title = { Text("New session") },
            text = { Text("Remove all samples from all pads and reset the mixer? Unsaved changes are lost.") },
            confirmButton = { TextButton(onClick = { vm.newSession(); newDialog = false }) { Text("Clear everything") } },
            dismissButton = { TextButton(onClick = { newDialog = false }) { Text("Cancel") } }
        )
    }
    // export session: snimi pod imenom, pa izvoz u izabrani folder (projekt + semplovi po folderima)
    val exportPicker = ui.rememberFolderPicker { tree -> vm.saveAndExport(exportName, tree) }
    if (exportDialog) {
        var name by remember { mutableStateOf(sessionName.ifBlank { "Session" }) }
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("Export session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Session name") },
                        singleLine = true
                    )
                    Text(
                        "The current session is saved under this name, then you choose a folder. It gets a folder " +
                            "with the project file (.padsession) and the samples sorted into folders (Drums, Bass...).",
                        color = TextDim, fontSize = 12.sp
                    )
                    if (sessions.any { it.name.equals(name.trim(), ignoreCase = true) }) {
                        Text("A saved session with this name exists and will be overwritten.", color = EditBorder, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    exportName = name.trim()
                    exportDialog = false
                    exportPicker()
                }) { Text("Choose folder and export") }
            },
            dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("Cancel") } }
        )
    }
    if (saveDialog) {
        var name by remember { mutableStateOf(sessionName) }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Session name") },
                        singleLine = true
                    )
                    if (sessions.any { it.name.equals(name.trim(), ignoreCase = true) }) {
                        Text("A session with this name exists and will be overwritten.", color = EditBorder, fontSize = 12.sp)
                    }
                    if (sessions.isNotEmpty()) {
                        Text("Existing sessions (tap to use the name):", color = TextDim, fontSize = 12.sp)
                        Column(
                            modifier = Modifier
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            for (s in sessions) {
                                Text(
                                    s.name,
                                    color = Color.White,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { name = s.name }
                                        .padding(vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.saveSession(name); saveDialog = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } }
        )
    }
    if (loadDialog) {
        var toDelete by remember { mutableStateOf<SessionInfo?>(null) }
        var toExport by remember { mutableStateOf<SessionInfo?>(null) }
        // izvoz u izabrani folder: projekt (.padsession) + semplovi po folderima (Drums, Bass...)
        val exportFolder = ui.rememberFolderPicker { tree ->
            val s = toExport
            toExport = null
            if (s != null) vm.exportSessionToFolder(s, tree)
        }
        val importFile = ui.rememberSessionFilePicker { ref -> vm.importSessionFile(ref) }
        // stari izvozi (folder sa pads.json + samples/) i dalje se mogu uvesti
        val importFolder = ui.rememberFolderPicker { ref -> vm.importSessions(ref) }
        AlertDialog(
            onDismissRequest = { loadDialog = false },
            title = { Text("Load session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Loading replaces the current session (save it first if you need it).", color = TextDim, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { importFile() }) { Text("Import session file...") }
                        TextButton(onClick = { importFolder() }) { Text("Import old folder...") }
                    }
                    if (sessions.isEmpty()) {
                        Text("No saved sessions yet.")
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (s in sessions) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(s.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${s.padCount} samples · ${formatDate(s.savedAt)}", color = TextDim, fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { vm.loadSession(s); loadDialog = false }) { Text("Load") }
                                    TextButton(onClick = { toExport = s; exportFolder() }) { Text("Export") }
                                    TextButton(onClick = { toDelete = s }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { loadDialog = false }) { Text("Close") } }
        )
        toDelete?.let { s ->
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Delete session?") },
                text = { Text("\"${s.name}\" will be deleted permanently.") },
                confirmButton = { TextButton(onClick = { vm.deleteSession(s); toDelete = null }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
            )
        }
    }

    if (settingsDialog) ui.SettingsDialog { settingsDialog = false }
    if (setListDialog) SetListDialog(vm) { setListDialog = false }
    if (aboutDialog) AboutDialog { aboutDialog = false }

    // ---------------------------------------------------------------- dijalog za tempo
    if (bpmDialog) {
        var text by remember { mutableStateOf(bpm.toString()) }
        val parsed = text.toIntOrNull()
        AlertDialog(
            onDismissRequest = { bpmDialog = false },
            title = { Text("Tempo (60–240 BPM)") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = parsed != null,
                    onClick = { parsed?.let { vm.setBpm(it) }; bpmDialog = false }
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { bpmDialog = false }) { Text("Cancel") } }
        )
    }

    // ---------------------------------------------------------------- dijalog za pad
    val dialogSlot = editSlot
    if (dialogSlot != null) {
        val config = pads[dialogSlot]
        AlertDialog(
            onDismissRequest = { vm.closeEditDialog() },
            title = {
                Text("Scene ${dialogSlot / Layout.TRACKS + 1}, track ${dialogSlot % Layout.TRACKS + 1}")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (config != null) "${config.label}  ·  ${config.name}" else "Empty pad")
                    Button(onClick = { vm.openBrowser(dialogSlot) }) {
                        Text("Browse library (search + preview)")
                    }
                    TextButton(onClick = { pickFile() }) {
                        Text("Choose a file from storage")
                    }
                    if (config != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Switch(checked = config.loop, onCheckedChange = { vm.setLoop(dialogSlot, it) })
                            Text(if (config.loop) "Loop (repeats until stopped)" else "One-shot")
                        }
                        var bpmText by remember(dialogSlot, config.srcBpm) { mutableStateOf(formatBpm(config.srcBpm)) }
                        val parsedBpm = bpmText.toDoubleOrNull()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = bpmText,
                                onValueChange = { bpmText = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                                label = { Text("Original BPM of this sample") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            Row {
                                TextButton(
                                    enabled = parsedBpm != null,
                                    onClick = { parsedBpm?.let { vm.setSampleBpm(dialogSlot, it) } }
                                ) { Text("Set") }
                                TextButton(
                                    enabled = parsedBpm != null,
                                    onClick = { parsedBpm?.let { vm.setSampleBpmAll(it) } }
                                ) { Text("Set for all samples") }
                            }
                        }
                        if (config.studio != null) {
                            Button(onClick = { vm.editPadInDrums(dialogSlot) }) { Text("Edit in Drums (replaces the current drum pattern)") }
                        }
                        if (config.keys != null) {
                            Button(onClick = { vm.editPadInKeys(dialogSlot) }) { Text("Edit in Keys (replaces the current synth sound and notes)") }
                        }
                        TextButton(onClick = { vm.removeSample(dialogSlot) }) { Text("Remove sample") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.closeEditDialog() }) { Text("Close") } }
        )
    }
}

private fun formatBpm(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

private fun formatDate(ms: Long): String = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(ms))
