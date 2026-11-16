package com.example.padsampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.SamplerViewModel
import com.example.padsampler.audio.NativeAudio
import com.example.padsampler.model.DrumLayout
import com.example.padsampler.studio.DrumKits
import com.example.padsampler.model.DrumsState
import com.example.padsampler.model.DrumSynthKind
import com.example.padsampler.model.DrumSynthSpec
import com.example.padsampler.model.LiveState
import com.example.padsampler.model.Layout
import com.example.padsampler.model.TrackColors

/**
 * DRUMS: 8 zvukova x 16 koraka (jedan takt; duži pattern ima izbor takta). Lijevo zvuk: dodir svira i bira,
 * "…" učitava zvuk, M utišava, traka ispod imena je jačina. Gore: Play/Stop, dužina, takt, brisanje.
 */
@Composable
fun DrumsScreen(vm: SamplerViewModel, live: LiveState, modifier: Modifier = Modifier) {
    val d by vm.drums.state.collectAsState()
    var soundDialog by remember { mutableStateOf<Int?>(null) }
    var clearDialog by remember { mutableStateOf(false) }
    var printDialog by remember { mutableStateOf(false) }
    // telefon: manje dugmadi gore (ostalo u meniju ⋯), donji red (undo, swing, jačina) se otvara dugmetom VEL
    val compact = LocalUiPlatform.current.screenWidthDp() < 1000
    var velOpen by remember { mutableStateOf(false) }
    val pickMidi = LocalUiPlatform.current.rememberMidiFilePicker { vm.importMidiFile(it, drums = true) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        // ------------------------------------------------------------ kontrole (telefon: niža dugmad)
        androidx.compose.runtime.CompositionLocalProvider(LocalDense provides compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val playing = live.drumsTransport == NativeAudio.STUDIO_PLAYING
            val queued = live.drumsTransport == NativeAudio.STUDIO_QUEUED
            FlatButton(
                when {
                    playing -> "■  STOP"
                    queued -> "▶  ..."
                    else -> "▶  PLAY"
                },
                modifier = Modifier.width(if (compact) 92.dp else 110.dp),
                color = if (playing || queued) Color(0xFF2E4A3E) else PanelColor
            ) { vm.drumsTogglePlay() }

            // dužina i takt: skroluje se ako nema mjesta (8 taktova na tabletu)
            Row(
                modifier = Modifier.weight(1f).hScroll(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LengthMenu(d.bars) { vm.drums.setBars(it) }
                if (d.bars > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text("BAR", color = TextFaint, fontSize = 11.sp, letterSpacing = 1.sp)
                    for (b in 0 until d.bars) {
                        val playingHere = live.drumStep >= 0 && live.drumStep / DrumLayout.STEPS_PER_BAR == b
                        StudioChip("${b + 1}", d.viewBar == b, dot = playingHere) { vm.drums.setViewBar(b) }
                    }
                }
            }
            if (compact) {
                KitMenu(vm, d)
                StudioChip("VEL", velOpen) { velOpen = !velOpen }
                FlatButton("PRINT", color = Accent, textColor = Ink) { printDialog = true }
                MoreMenu(onImport = { pickMidi() }, onClear = { clearDialog = true })
            } else {
                KitMenu(vm, d)
                FlatButton("IMPORT MIDI") { pickMidi() }
                FlatButton("clear all") { clearDialog = true }
                FlatButton("PRINT TO PAD", color = Accent, textColor = Ink) { printDialog = true }
            }
        }

        }

        // ------------------------------------------------------------ undo, swing, jačina izabranog zvuka
        val undoAvail by vm.drums.undoAvailable.collectAsState()
        val velRow: @Composable (Modifier) -> Unit = { m ->
            Row(
                modifier = m.height(if (compact) 76.dp else 64.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.width(170.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (!compact) UndoButtons(undoAvail, onUndo = { vm.drums.undo() }, onRedo = { vm.drums.redo() })
                    SwingControl(d.swing) { vm.drums.setSwing(it) }
                    // telefon: jačina izabranog zvuka je ovdje (traka u redu zvuka je sakrivena)
                    if (compact) d.sounds[d.selected]?.let { snd ->
                        GainBar(snd.gain, Color(TrackColors.all[d.selected].argb)) { vm.drums.setGain(d.selected, it) }
                    }
                }
                val lane = d.selected
                val first = d.viewBar * DrumLayout.STEPS_PER_BAR
                VelocityLane(
                    values = List(DrumLayout.STEPS_PER_BAR) { i ->
                        val st = first + i
                        if (st in d.steps[lane]) d.velocity(lane, st) else null
                    },
                    color = Color(TrackColors.all[lane].argb),
                    gap = 6.dp, beatGap = 4.dp,
                    onSet = { i, v -> vm.drums.setVelocity(lane, first + i, v) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
        val lane: @Composable (Int, Modifier) -> Unit = { l, m ->
            LaneRow(
                lane = l, d = d, live = live, compact = compact,
                onHit = { vm.drums.hitAndSelect(l) },
                onMenu = { soundDialog = l },
                onMute = { vm.drums.toggleMute(l) },
                onGain = { vm.drums.setGain(l, it) },
                onStep = { vm.drums.toggleStep(l, it) },
                modifier = m
            )
        }

        // ------------------------------------------------------------ mreža
        if (compact) {
            // telefon: trake nisu niže od MIN_LANE (skroluju se gore/dolje); VEL se otvara preko donjeg dijela mreže
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val gap = 3.dp
                val fit = (maxHeight - gap * (DrumLayout.LANES - 1)) / DrumLayout.LANES
                val rowH = if (fit > MIN_LANE) fit else MIN_LANE
                val velH = 76.dp + 12.dp
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    for (l in 0 until DrumLayout.LANES) lane(l, Modifier.height(rowH).fillMaxWidth())
                    // da se i donje trake mogu izvući iznad otvorenog VEL-a
                    if (velOpen) Spacer(Modifier.height(velH))
                }
                if (velOpen) {
                    val shape = rShape(12)
                    velRow(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .raised(shape, 3.dp)
                            .clip(shape)
                            .background(HeaderTop)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                            .padding(6.dp)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                for (l in 0 until DrumLayout.LANES) lane(l, Modifier.weight(1f).fillMaxWidth())
            }
            velRow(Modifier.fillMaxWidth())
        }
    }

    // ---------------------------------------------------------------- dijalozi
    soundDialog?.let { lane ->
        val snd = d.sounds[lane]
        val pick = LocalUiPlatform.current.rememberFilePicker { ref -> vm.assignDrumFile(lane, ref) }
        // ugrađeni zvuk: podešavanja se drže ovdje dok se vuče (stanje se upiše kad se pusti)
        var spec by remember(lane) { mutableStateOf(snd?.synth) }
        AlertDialog(
            onDismissRequest = { soundDialog = null },
            title = { Text("Drum sound ${lane + 1}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (snd != null) "${snd.label}  ·  ${snd.name}" else "Empty")
                    // ---- ugrađeni (sintetizovani) zvuk
                    Text("BUILT-IN SOUND", color = TextFaint, fontSize = 10.sp, letterSpacing = 1.sp)
                    Row(
                        modifier = Modifier.hScroll(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (kind in DrumSynthKind.values()) {
                            StudioChip(kind.label, spec?.kind == kind) {
                                val s = (spec ?: DrumSynthSpec(kind)).copy(kind = kind)
                                spec = s
                                vm.editDrumSynth(lane, s, commit = true)
                            }
                        }
                    }
                    spec?.let { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val knobs = listOf<Triple<String, Float, (Float) -> DrumSynthSpec>>(
                                Triple("TUNE", s.tune) { v -> s.copy(tune = v) },
                                Triple("DECAY", s.decay) { v -> s.copy(decay = v) },
                                Triple("TONE", s.tone) { v -> s.copy(tone = v) },
                                Triple("SNAP", s.snap) { v -> s.copy(snap = v) },
                                Triple("GRIT", s.grit) { v -> s.copy(grit = v) }
                            )
                            for ((label, value, with) in knobs) {
                                SimpleKnob(
                                    label, value, Accent, default = if (label == "GRIT") 0f else 0.5f,
                                    onChange = { v -> val n = with(v); spec = n; vm.editDrumSynth(lane, n, commit = false) },
                                    onRelease = { v -> val n = with(v); spec = n; vm.editDrumSynth(lane, n, commit = true) }
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            FlatButton("▶") { vm.drums.hit(lane) }
                        }
                    }
                    // ---- vlastiti sempl
                    Text("YOUR OWN SAMPLE", color = TextFaint, fontSize = 10.sp, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { soundDialog = null; vm.openDrumBrowser(lane) }) {
                            Text("Browse library")
                        }
                        TextButton(onClick = { pick() }) { Text("Choose a file") }
                    }
                    if (snd != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.drums.clearLane(lane); soundDialog = null }) { Text("Clear steps") }
                            TextButton(onClick = { vm.drums.removeSound(lane); soundDialog = null }) { Text("Remove sound") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { soundDialog = null }) { Text("Close") } }
        )
    }
    val midiChoice by vm.midiChoice.collectAsState()
    midiChoice?.takeIf { it.drums }?.let { MidiPartDialog(vm, it) }
    if (printDialog) {
        StudioPrintDialog(
            vm, "Print drums to pad",
            "${d.bars} bar${if (d.bars > 1) "s" else ""} at the current tempo, as a loop. Choose the pad:",
            onPrint = { slot, _ -> vm.printDrumsToPad(slot) }
        ) { printDialog = false }
    }
    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("Clear pattern") },
            text = { Text("Remove all steps of all sounds? The sounds stay loaded.") },
            confirmButton = { TextButton(onClick = { vm.drums.clearAll(); clearDialog = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LaneRow(
    lane: Int,
    d: DrumsState,
    live: LiveState,
    onHit: () -> Unit,
    onMenu: () -> Unit,
    onMute: () -> Unit,
    onGain: (Float) -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val color = Color(TrackColors.all[lane].argb)
    val snd = d.sounds[lane]
    val selected = d.selected == lane
    val muted = d.mutes[lane]
    val steps = d.steps[lane]
    val first = d.viewBar * DrumLayout.STEPS_PER_BAR

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // ---- zvuk: ime (svira + bira), jačina, meni, mute (telefon: uže, bez trake jačine)
        val shape = rShape(10)
        Column(
            modifier = Modifier
                .width(if (compact) 100.dp else 170.dp)
                .fillMaxHeight()
                .clip(shape)
                .background(if (selected) Color(0xFF242932) else PanelColor)
                .border(1.dp, if (selected) color.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.06f), shape)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(5.dp).fillMaxHeight().background(color))
                Text(
                    snd?.label ?: "empty",
                    color = if (snd != null) TextMain else TextFaint,
                    fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { if (snd != null) onHit() else onMenu() }
                        .padding(start = if (compact) 5.dp else 8.dp, top = if (compact) 0.dp else 6.dp)
                        .then(if (compact) Modifier.wrapContentHeight() else Modifier)
                )
                Text(
                    "…", color = TextDim, fontSize = if (compact) 14.sp else 16.sp,
                    modifier = Modifier
                        .clickable { onMenu() }
                        .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 4.dp)
                )
                Text(
                    "M", color = if (muted) Ink else TextDim, fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(end = if (compact) 3.dp else 6.dp)
                        .clip(rShape(6))
                        .background(if (muted) MuteYellow else Color.Transparent)
                        .clickable { onMute() }
                        .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = 3.dp)
                )
            }
            if (snd != null && !compact) GainBar(snd.gain, color, onGain)
        }

        // ---- 16 koraka
        for (i in 0 until DrumLayout.STEPS_PER_BAR) {
            val step = first + i
            val on = step in steps
            val playhead = step == live.drumStep
            val beat = i % 4 == 0
            val cellShape = stepShape()   // kao u Keys piano rollu
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = if (beat && i > 0) (if (compact) 3.dp else 4.dp) else 0.dp)
                    // upaljen korak lagano svijetli (tiši = manje), prazan je udubljen
                    .then(if (on && !muted) Modifier.glow(cellShape, color, 0.4f + 0.6f * d.velocity(lane, step)) else Modifier)
                    .clip(cellShape)
                    .background(
                        when {
                            on && muted -> color.copy(alpha = 0.35f).compositeOver(EmptyPadColor)
                            on -> color.copy(alpha = 0.3f + 0.7f * d.velocity(lane, step)).compositeOver(EmptyPadColor)
                            beat -> Color(0xFF1A1D23)
                            else -> EmptyPadColor
                        }
                    )
                    .then(
                        if (on) Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), cellShape)
                        else Modifier.border(1.dp, Color.White.copy(alpha = 0.04f), cellShape)
                    )
                    .then(if (playhead) Modifier.border(2.dp, Color.White.copy(alpha = 0.85f), cellShape) else Modifier)
                    .pointerInput(lane, step) { detectTapGestures { onStep(step) } }
            )
        }
    }
}

/** Tanka traka jačine (fader taper), povlači se ili dodiruje. */
@Composable
private fun GainBar(value: Float, color: Color, onChange: (Float) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .padding(start = 13.dp, end = 10.dp, bottom = 6.dp)
    ) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Box(
            Modifier
                .fillMaxSize()
                .clip(rShape(3))
                .background(Lcd)
                .pointerInput(w) {
                    detectTapGestures { onChange((it.x / w).coerceIn(0f, 1f)) }
                }
                .pointerInput(w) {
                    detectDragGestures { change, _ -> onChange((change.position.x / w).coerceIn(0f, 1f)) }
                }
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .background(color.copy(alpha = 0.75f))
            )
        }
    }
}

@Composable
internal fun StudioChip(
    label: String, selected: Boolean, dot: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val shape = rShape(9)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Color(0xFF1D2127) else Lcd)
            .border(1.dp, if (selected) Accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f), shape)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = if (LocalDense.current) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
    ) {
        if (dot) Box(Modifier.width(6.dp).height(6.dp).clip(rShape(3)).background(AccentBright))
        Text(
            label,
            color = if (selected) AccentBright else TextMain,
            fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1
        )
    }
}

/**
 * Print to pad: izbor pada (6 scena x 8 trackova kao u Sessionu). Predložen je zadnji pad na koji je printano
 * (ili onaj otvoren sa "Edit in Drums"), inače prvi prazan.
 */
@Composable
internal fun StudioPrintDialog(
    vm: SamplerViewModel,
    title: String,
    info: String,
    categories: List<String>? = null,
    category: String = "",
    onPrint: (slot: Int, category: String) -> Unit,
    onClose: () -> Unit
) {
    val pads by vm.pads.collectAsState()
    var cat by remember { mutableStateOf(category) }
    val last by vm.printTarget.collectAsState()
    var sel by remember {
        mutableStateOf(last ?: (0 until Layout.SLOTS).firstOrNull { it !in pads } ?: 0)
    }
    val occupied = pads[sel]
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(info, color = TextDim, fontSize = 12.sp)
                if (categories != null) {
                    Text("Category (pad label and folder in the export):", color = TextDim, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (c in categories) StudioChip(c, c == cat) { cat = c }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (scene in 0 until Layout.SCENES) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (track in 0 until Layout.TRACKS) {
                                val slot = Layout.slot(scene, track)
                                val cfg = pads[slot]
                                val color = Color(TrackColors.all[track].argb)
                                val shape = rShape(6)
                                Box(
                                    modifier = Modifier
                                        .width(64.dp)
                                        .height(34.dp)
                                        .clip(shape)
                                        .background(if (cfg != null) color.copy(alpha = 0.45f) else EmptyPadColor)
                                        .border(
                                            if (slot == sel) 2.dp else 1.dp,
                                            if (slot == sel) Color.White else Color.White.copy(alpha = 0.06f),
                                            shape
                                        )
                                        .clickable { sel = slot },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        cfg?.label ?: "", color = TextMain, fontSize = 9.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Scene ${sel / Layout.TRACKS + 1}, track ${sel % Layout.TRACKS + 1}" +
                        if (occupied != null) "  ·  replaces \"${occupied.label}\"" else "  ·  empty",
                    color = if (occupied != null) EditBorder else TextDim, fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPrint(sel, cat); onClose() }) { Text("Print") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

/** KIT: ugrađeni kitovi (sintetizovani zvukovi na svih 8 traka). Natpis = kit ako su sve trake iz njega. */
@Composable
private fun KitMenu(vm: SamplerViewModel, d: DrumsState) {
    var open by remember { mutableStateOf(false) }
    val current = DrumKits.all.firstOrNull { kit ->
        d.sounds.indices.all { lane -> d.sounds[lane]?.let { it.synth == kit.sounds.getOrNull(lane) } == true }
    }?.name
    Box {
        FlatButton(if (current != null) "KIT: $current  ▾" else "KIT  ▾") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (kit in DrumKits.all) {
                DropdownMenuItem(
                    text = { Text(kit.name + if (kit.name == current) "  ✓" else "") },
                    onClick = { vm.loadDrumKit(kit); open = false }
                )
            }
        }
    }
}

/** Telefon: meni ⋯ sa IMPORT MIDI i clear all (Drums i Keys). */
@Composable
internal fun MoreMenu(onImport: () -> Unit, onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FlatButton("⋯") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("IMPORT MIDI") }, onClick = { open = false; onImport() })
            DropdownMenuItem(text = { Text("clear all") }, onClick = { open = false; onClear() })
        }
    }
}

/** LENGTH ▾: dužina patterna (1/2/4/8 taktova) u padajućem meniju (Drums i Keys). */
@Composable
internal fun LengthMenu(bars: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        StudioChip("LENGTH  ${if (bars == 1) "1 bar" else "$bars bars"}  ▾", false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (b in DrumLayout.BAR_OPTIONS) {
                DropdownMenuItem(
                    text = { Text((if (b == 1) "1 bar" else "$b bars") + if (b == bars) "  ✓" else "") },
                    onClick = { onSelect(b); open = false }
                )
            }
        }
    }
}
private val MIN_LANE = 30.dp
