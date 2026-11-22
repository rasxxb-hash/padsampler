package com.example.padsampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.SamplerViewModel
import com.example.padsampler.audio.NativeAudio
import com.example.padsampler.midi.MidiKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.padsampler.model.DrumLayout
import com.example.padsampler.model.KeysLayout
import com.example.padsampler.model.KeyNote
import com.example.padsampler.model.KeysState
import com.example.padsampler.model.LiveState
import com.example.padsampler.model.Scale
import com.example.padsampler.model.SynthParamDef
import com.example.padsampler.model.SynthParams as P
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

private val KeysColor = Accent          // mint (kao LED-ovi Keys na Launchpadu)
private val SectionColor = Color(0xFF191C21)

/**
 * KEYS: Minimoog-sintisajzer (potenciometri: povuci gore/dolje, dupli dodir = početna vrijednost, dodir na
 * WAVE mijenja talas) i piano roll u skali (dvije oktave od tonike). Klavijatura lijevo od piano rolla svira
 * dok se drži. Gore: Play/Stop, dužina, takt, brisanje, Print to pad.
 */
@Composable
fun KeysScreen(vm: SamplerViewModel, live: LiveState, modifier: Modifier = Modifier) {
    val k by vm.keys.state.collectAsState()
    val held by vm.keys.held.collectAsState()
    var clearDialog by remember { mutableStateOf(false) }
    var printDialog by remember { mutableStateOf(false) }
    var presetDialog by remember { mutableStateOf(false) }
    // SELECT: biranje i pomjeranje nota u piano rollu (samo u aplikaciji)
    var selectMode by remember { mutableStateOf(false) }
    // tab panela potenciometara: 0 = SYNTH, 1 = ARP / FX
    var panelTab by remember { mutableStateOf(0) }
    var selectedRaw by remember { mutableStateOf(emptySet<KeyNote>()) }
    val selected = remember(selectedRaw, k.notes) { selectedRaw.filter { it in k.notes }.toSet() }
    val rows = remember(k.root, k.scale, k.octave) { KeysLayout.rollNotes(k) }
    // panel je u stanju Keys (ostaje i poslije promjene ekrana; strelica dolje na Launchpadu ga otvara/zatvara)
    val panelOpen = k.panelOpen
    val setPanelOpen: (Boolean) -> Unit = { vm.keys.setPanelOpen(it) }
    // telefon: manje dugmadi gore (ostalo u meniju ⋯), otvoren panel prekrije piano roll, VELOCITY na dugme VEL
    val compact = LocalUiPlatform.current.screenWidthDp() < 1000
    var velOpen by remember { mutableStateOf(false) }
    val pickMidi = LocalUiPlatform.current.rememberMidiFilePicker { vm.importMidiFile(it, drums = false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        // ------------------------------------------------------------ transport i dužina (telefon: niža dugmad)
        CompositionLocalProvider(LocalDense provides compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val playing = live.keysTransport == NativeAudio.STUDIO_PLAYING
            val queued = live.keysTransport == NativeAudio.STUDIO_QUEUED
            FlatButton(
                when {
                    playing -> "■  STOP"
                    queued -> "▶  ..."
                    else -> "▶  PLAY"
                },
                modifier = Modifier.width(if (compact) 92.dp else 110.dp),
                color = if (playing || queued) Color(0xFF2E4A3E) else PanelColor
            ) { vm.keysTogglePlay() }
            // REC: odbrojavanje 1 takt (ako ne svira), pa snimanje sviranja (dosnimava preko postojećih nota)
            FlatButton(
                when (k.rec) {
                    KeysState.REC_ON -> "●  REC"
                    KeysState.REC_COUNT_IN -> "●  1 2 3 4"
                    else -> "●  REC"
                },
                modifier = Modifier.width(if (compact) 104.dp else 110.dp),
                color = when (k.rec) {
                    KeysState.REC_ON -> StopRed
                    KeysState.REC_COUNT_IN -> StopRedBottom
                    else -> PanelColor
                },
                textColor = if (k.rec == KeysState.REC_OFF) StopRed else Color.White
            ) { vm.keysToggleRec() }

            // dužina i takt: skroluje se ako nema mjesta (8 taktova na tabletu)
            Row(
                modifier = Modifier.weight(1f).hScroll(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LengthMenu(k.bars) { vm.keys.setBars(it) }
                if (k.bars > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text("BAR", color = TextFaint, fontSize = 11.sp, letterSpacing = 1.sp)
                    for (b in 0 until k.bars) {
                        val playingHere = live.keysStep >= 0 && live.keysStep / DrumLayout.STEPS_PER_BAR == b
                        StudioChip("${b + 1}", k.viewBar == b, dot = playingHere) { vm.keys.setViewBar(b) }
                    }
                }
            }
            if (compact) {
                StudioChip("VEL", velOpen) { velOpen = !velOpen }
                FlatButton("PRINT", color = Accent, textColor = Ink) { printDialog = true }
                MoreMenu(onImport = { pickMidi() }, onClear = { clearDialog = true })
            } else {
                FlatButton("IMPORT MIDI") { pickMidi() }
                FlatButton("CLEAR ALL") { clearDialog = true }
                FlatButton("PRINT TO PAD", color = Accent, textColor = Ink) { printDialog = true }
            }
        }

        }

        // ------------------------------------------------------------ sintisajzer (sklapa se, da piano roll ima mjesta)
        val collapsed: @Composable () -> Unit = {
            CollapsedPanel(vm, k, compact, onPresets = { presetDialog = true }, onExpand = { setPanelOpen(true) })
        }
        if (panelOpen) {
            val learnOn by vm.learnMode.collectAsState()
            val target by vm.learnTarget.collectAsState()
            val map by vm.midiMap.collectAsState()
            val device by vm.learnDevice.collectAsState()
            val devMap = device?.let { map[it] } ?: emptyMap()
            val learn = LearnUi(
                active = learnOn, target = target,
                ccOf = { id -> devMap.entries.firstOrNull { it.value == id }?.key },
                onPick = { vm.setLearnTarget(it) }
            )
            if (learnOn) LearnBar(vm, device, target)
            CompositionLocalProvider(LocalLearn provides learn) {
                // telefon: panel zauzme sav prostor ispod (piano roll se vrati sa "hide") i skroluje se gore/dolje
                Box(if (compact) Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier) {
                    SynthPanel(vm, k, panelTab, { panelTab = it }, onPresets = { presetDialog = true }, onCollapse = { if (learnOn) vm.toggleLearn(); setPanelOpen(false) })
                }
            }
        } else if (!compact) {
            collapsed()
        }

        // ------------------------------------------------------------ skala, oktava, dužina note
        val toolbar: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().hScroll(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!compact) {
                val undoAvail by vm.keys.undoAvailable.collectAsState()
                UndoButtons(undoAvail, onUndo = { vm.keys.undo() }, onRedo = { vm.keys.redo() })
            }
            SwingControl(k.swing) { vm.keys.setSwing(it) }
            Spacer(Modifier.width(6.dp))
            // SELECT: prevuci po praznom = izbor pravougaonikom, tap na notu = dodaj/izbaci, prevuci notu = pomjeri izbor
            StudioChip("SELECT", selectMode) {
                selectMode = !selectMode
                if (!selectMode) selectedRaw = emptySet()
            }
            if (selectMode) {
                val any = selected.isNotEmpty()
                MiniChip("ALL") { selectedRaw = k.notes.filter { it.step < k.totalSteps }.toSet() }
                MiniChip("◀", any) { selectedRaw = vm.keys.moveNotes(selected, -1, 0, rows, "move") }
                MiniChip("▶", any) { selectedRaw = vm.keys.moveNotes(selected, +1, 0, rows, "move") }
                // izbor se poravna na najbližu liniju takta (prva izabrana nota)
                MiniChip("|◀ to bar", any) {
                    val min = selected.minOf { it.step }
                    val bar = DrumLayout.STEPS_PER_BAR
                    val target = ((min + bar / 2) / bar) * bar
                    selectedRaw = vm.keys.moveNotes(selected, target - min, 0, rows)
                }
                MiniChip("DEL", any) { vm.keys.deleteNotes(selected); selectedRaw = emptySet() }
            }
            Spacer(Modifier.width(6.dp))
            // poravnavanje snimljenih nota (REC)
            Text("REC GRID", color = TextFaint, fontSize = 11.sp, letterSpacing = 1.sp)
            StudioChip("1/16", k.recQuantize == 1) { vm.keys.setRecQuantize(1) }
            StudioChip("1/8", k.recQuantize == 2) { vm.keys.setRecQuantize(2) }
            Spacer(Modifier.width(6.dp))
            Text("KEY", color = TextFaint, fontSize = 11.sp, letterSpacing = 1.sp)
            Menu(KeysLayout.NOTE_NAMES[k.root], KeysLayout.NOTE_NAMES) { vm.keys.setRoot(it) }
            Menu(k.scale.label, Scale.values().map { it.label }) { vm.keys.setScale(Scale.values()[it]) }
            Spacer(Modifier.width(6.dp))
            Text("OCTAVE", color = TextFaint, fontSize = 11.sp, letterSpacing = 1.sp)
            StudioChip("−", false) { vm.keys.shiftOctave(-1) }
            Text(KeysLayout.noteName(KeysLayout.baseNote(k)), color = TextMain, fontSize = 12.sp, modifier = Modifier.width(34.dp))
            StudioChip("+", false) { vm.keys.shiftOctave(+1) }
            Spacer(Modifier.width(6.dp))
            Text("NOTE", color = TextFaint, fontSize = 11.sp, letterSpacing = 1.sp)
            for (len in KeysLayout.NOTE_LENGTHS) {
                val label = when (len) {
                    1 -> "1/16"; 2 -> "1/8"; 4 -> "1/4"; 8 -> "1/2"; else -> "1 bar"
                }
                StudioChip(label, k.noteLength == len) { vm.keys.setNoteLength(len) }
            }
            Spacer(Modifier.width(6.dp))
            KeyboardMenu(vm)
        }
        }

        // ------------------------------------------------------------ piano roll
        val roll: @Composable (Modifier, androidx.compose.ui.unit.Dp?) -> Unit = { m, vh ->
            PianoRoll(
                k = k, live = live, held = held, rows = rows,
                selectMode = selectMode, selected = selected,
                onSelect = { selectedRaw = it },
                onMove = { sel, ds, dr -> selectedRaw = vm.keys.moveNotes(sel, ds, dr, rows) },
                onCell = { step, pitch -> vm.keys.toggleNote(step, pitch) },
                onKeyDown = { vm.keys.noteOn(it); vm.keys.selectNote(it) },
                onKeyUp = { vm.keys.noteOff(it) },
                viewportHeight = vh,
                modifier = m
            )
        }

        // ------------------------------------------------------------ jačina nota (po koraku prikazanog takta)
        val velRow: @Composable (Modifier) -> Unit = { m ->
            Row(
                modifier = m.height(44.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "VELOCITY", color = TextFaint, fontSize = if (compact) 8.sp else 9.sp, letterSpacing = 0.5.sp, maxLines = 1,
                    modifier = Modifier.width(if (compact) 48.dp else 70.dp).padding(start = 4.dp)
                )
                val first = k.viewBar * DrumLayout.STEPS_PER_BAR
                val values = remember(k.notes, first) {
                    List(DrumLayout.STEPS_PER_BAR) { i ->
                        k.notes.filter { it.step == first + i }.maxOfOrNull { it.velocity }
                    }
                }
                VelocityLane(
                    values = values, color = KeysColor, gap = 3.dp, beatGap = 3.dp,
                    onSet = { i, v -> vm.keys.setVelocity(first + i, v) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        if (compact) {
            // telefon: red sa zvukom, traka (swing, select, key...) i piano roll se skroluju zajedno;
            // VELOCITY se otvara preko donjeg dijela
            if (!panelOpen) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val vh = maxHeight
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompositionLocalProvider(LocalDense provides true) {
                        collapsed()
                        toolbar()
                    }
                    roll(Modifier.fillMaxWidth(), vh)
                    if (velOpen) Spacer(Modifier.height(44.dp + 12.dp))
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
            toolbar()
            roll(Modifier.weight(1f).fillMaxWidth(), null)
            velRow(Modifier.fillMaxWidth())
        }
    }

    // ---------------------------------------------------------------- dijalozi
    if (printDialog) {
        StudioPrintDialog(
            vm, "Print keys to pad",
            "${k.bars} bar${if (k.bars > 1) "s" else ""} at the current tempo, as a loop (sound: ${k.presetName}).",
            categories = KeysLayout.CATEGORIES,
            category = k.category,
            onPrint = { slot, cat -> vm.printKeysToPad(slot, cat) }
        ) { printDialog = false }
    }
    if (presetDialog) PresetDialog(vm, k) { presetDialog = false }
    val midiChoice by vm.midiChoice.collectAsState()
    midiChoice?.takeIf { !it.drums }?.let { MidiPartDialog(vm, it) }
    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("Clear notes") },
            text = { Text("Remove all notes? The synth sound stays.") },
            confirmButton = { TextButton(onClick = { vm.keys.clearAll(); clearDialog = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancel") } }
        )
    }
}

// -------------------------------------------------------------------- panel sintisajzera


/** Sklopljen panel: samo preset (ime, ◀ ▶, lista) i dugme za otvaranje potenciometara. */
@Composable
private fun CollapsedPanel(vm: SamplerViewModel, k: KeysState, compact: Boolean, onPresets: () -> Unit, onExpand: () -> Unit) {
    val shape = rShape(12)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PanelColor)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(horizontal = 8.dp, vertical = if (compact) 3.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!compact) Text("SOUND", color = TextFaint, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
        Text(
            k.presetName, color = AccentBright, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(if (compact) 140.dp else 180.dp)
                .clip(rShape(6))
                .background(Lcd)
                .clickable { onPresets() }
                .padding(horizontal = 8.dp, vertical = 7.dp)
        )
        StudioChip("◀", false) { stepPreset(vm, k, -1) }
        StudioChip("▶", false) { stepPreset(vm, k, +1) }
        StudioChip("…", false) { onPresets() }
        Spacer(Modifier.weight(1f))
        // ARP brzo uključi/isključi i kad su potenciometri sakriveni
        StudioChip("ARP", k.params[P.ARP_ON] >= 0.5f) { vm.keys.setParam(P.ARP_ON, if (k.params[P.ARP_ON] >= 0.5f) 0f else 1f) }
        StudioChip("KNOBS  ▾", false) { onExpand() }
    }
}

@Composable
private fun SynthPanel(
    vm: SamplerViewModel, k: KeysState, tab: Int, onTab: (Int) -> Unit, onPresets: () -> Unit, onCollapse: () -> Unit
) {
    val set: (Int, Float) -> Unit = { id, v -> vm.keys.setParam(id, v) }
    val shape = rShape(12)
    // visina taba SYNTH se pamti, da panel (i piano roll ispod) ne skače pri promjeni taba
    var synthHeight by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PanelColor)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(8.dp)
            .then(if (tab == 0) Modifier.onSizeChanged { synthHeight = it.height } else Modifier)
            .then(if (tab != 0 && synthHeight > 0) Modifier.heightIn(min = with(density) { synthHeight.toDp() }) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // sekcije se skroluju vodoravno kad prozor nije dovoljno širok; tabovi i "hide" su uvijek vidljivi desno
        // sve sekcije iste visine (najviša određuje), poravnate gore
        Row(
            modifier = Modifier
                .weight(1f)
                .hScroll()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (tab == 0) SynthSections(vm, k, set, onPresets) else ArpFxSections(k, set)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
            // tačka na tabu: arp ili neki efekat je uključen
            val arpOn = k.params[P.ARP_ON] >= 0.5f ||
                listOf(P.FX_DRIVE, P.FX_CHORUS, P.FX_DELAY_MIX, P.FX_REVERB_MIX).any { k.params[it] > 0.001f }
            // četiri dugmeta iste veličine; MIDI LEARN je tu da se vidi na oba taba (SYNTH i ARP / FX)
            val w = Modifier.width(112.dp)
            StudioChip("SYNTH", tab == 0, modifier = w) { onTab(0) }
            StudioChip("ARP / FX", tab == 1, dot = arpOn, modifier = w) { onTab(1) }
            StudioChip("MIDI LEARN", LocalLearn.current.active, modifier = w) { vm.toggleLearn() }
            StudioChip("▲", false, modifier = w, fontSize = 15.sp) { onCollapse() }   // zatvori panel
        }
    }
}

/** Tab ARP / FX: arpeggiator i efekti (drive -> chorus -> delay -> reverb), po dva reda potenciometara. */
@Composable
private fun ArpFxSections(k: KeysState, set: (Int, Float) -> Unit) {
    Section("ARPEGGIATOR", Modifier.fillMaxHeight()) {
        KnobColumn(k, set, P.ARP_ON, P.ARP_OCT, null)
        KnobColumn(k, set, P.ARP_MODE, P.ARP_GATE, null)
        KnobColumn(k, set, P.ARP_RATE, P.ARP_HOLD, null)
    }
    Section("COLOR", Modifier.fillMaxHeight()) {
        KnobColumn(k, set, P.FX_DRIVE, P.FX_CHORUS, null)
    }
    Section("DELAY", Modifier.fillMaxHeight()) {
        KnobColumn(k, set, P.FX_DELAY_TIME, P.FX_DELAY_FB, null)
        KnobColumn(k, set, P.FX_DELAY_MIX, null, null)
    }
    Section("REVERB", Modifier.fillMaxHeight()) {
        KnobColumn(k, set, P.FX_REVERB_SIZE, P.FX_REVERB_MIX, null)
    }
}

@Composable
private fun SynthSections(vm: SamplerViewModel, k: KeysState, set: (Int, Float) -> Unit, onPresets: () -> Unit) {
    run {
        // preset + kontrole
        Section("PRESET", Modifier.fillMaxHeight()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(120.dp)) {
                Text(
                    k.presetName, color = AccentBright, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(rShape(6))
                        .background(Lcd)
                        .clickable { onPresets() }
                        .padding(horizontal = 8.dp, vertical = 7.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StudioChip("◀", false) { stepPreset(vm, k, -1) }
                    StudioChip("▶", false) { stepPreset(vm, k, +1) }
                    StudioChip("…", false) { onPresets() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Knob(P.GLIDE, k, set)
                    Knob(P.VOICES, k, set)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Knob(P.VIBRATO, k, set)
                    Knob(P.VOLUME, k, set)
                }
            }
        }
        Section("OSCILLATORS", Modifier.fillMaxHeight()) {
            KnobColumn(k, set, P.OSC1_WAVE, P.OSC1_OCT, null, title = "1")
            KnobColumn(k, set, P.OSC2_WAVE, P.OSC2_OCT, P.OSC2_DETUNE, title = "2")
            KnobColumn(k, set, P.OSC3_WAVE, P.OSC3_OCT, P.OSC3_DETUNE, title = "3")
        }
        Section("MIXER", Modifier.fillMaxHeight()) {
            KnobColumn(k, set, P.OSC1_LEVEL, P.OSC2_LEVEL, P.OSC3_LEVEL)
            KnobColumn(k, set, P.NOISE, null, null)
        }
        // FILTER i ispod LOUDNESS (iste širine), da sve stane bez skrolanja lijevo-desno
        Column(
            modifier = Modifier.fillMaxHeight().width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Section("FILTER", Modifier.fillMaxWidth()) {
                KnobColumn(k, set, P.CUTOFF, P.F_ATTACK, null)
                KnobColumn(k, set, P.RESONANCE, P.F_DECAY, null)
                KnobColumn(k, set, P.FILTER_ENV, P.F_SUSTAIN, null)
                KnobColumn(k, set, P.KEY_TRACK, P.F_RELEASE, null)
            }
            Section("LOUDNESS", Modifier.fillMaxWidth().weight(1f)) {
                KnobColumn(k, set, P.A_ATTACK, null, null)
                KnobColumn(k, set, P.A_DECAY, null, null)
                KnobColumn(k, set, P.A_SUSTAIN, null, null)
                KnobColumn(k, set, P.A_RELEASE, null, null)
                KnobColumn(k, set, P.VEL_TONE, null, null)
            }
        }
    }
}

private fun stepPreset(vm: SamplerViewModel, k: KeysState, dir: Int) {
    val all = vm.builtInPresets + vm.userPresets.value
    if (all.isEmpty()) return
    val i = all.indexOfFirst { it.name == k.presetName }
    val next = if (i < 0) (if (dir > 0) 0 else all.size - 1) else (i + dir + all.size) % all.size
    vm.loadPreset(all[next])
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = rShape(9)
    Column(
        modifier = modifier
            .clip(shape)
            .background(SectionColor)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = TextFaint, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun KnobColumn(k: KeysState, set: (Int, Float) -> Unit, a: Int?, b: Int?, c: Int?, title: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (title != null) Text(title, color = KeysColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        for (id in listOf(a, b, c)) if (id != null) Knob(id, k, set)
    }
}

/** MIDI LEARN stanje za potenciometre (ccOf = CC mapiran na parametar trenutnog uređaja). */
private class LearnUi(
    val active: Boolean = false,
    val target: Int? = null,
    val ccOf: (Int) -> Int? = { null },
    val onPick: (Int) -> Unit = {}
)

private val LocalLearn = staticCompositionLocalOf { LearnUi() }

/** Traka iznad panela dok je MIDI LEARN uključen. */
@Composable
private fun LearnBar(vm: SamplerViewModel, device: String?, target: Int?) {
    val shape = rShape(10)
    var presetsOpen by remember { mutableStateOf(false) }
    // pickeri su ovdje (ne u dijalogu), da dijalog može ostati otvoren dok se bira fajl
    val ui = LocalUiPlatform.current
    var exportName by remember { mutableStateOf<String?>(null) }
    val importPreset = ui.rememberMidiPresetFilePicker { vm.importMidiPresetFile(it) }
    val exportPreset = ui.rememberMidiPresetSavePicker { ref -> exportName?.let { vm.exportMidiPreset(it, ref) } }
    if (presetsOpen) {
        MidiPresetDialog(
            vm, device,
            onImport = importPreset,
            onExport = { name -> exportName = name; exportPreset(vm.midiPresetFileName(name)) },
            onClose = { presetsOpen = false }
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(EditBorder.copy(alpha = 0.18f))
            .border(1.dp, EditBorder.copy(alpha = 0.5f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            when {
                device == null -> "MIDI LEARN: connect a MIDI keyboard (MIDI IN)"
                target == null -> "MIDI LEARN ($device): tap a knob, then move a knob or slider on the keyboard"
                else -> "MIDI LEARN ($device): move a control on the keyboard for ${P.all[target].label}"
            },
            color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (target != null) StudioChip("clear knob", false) { vm.clearLearnTarget() }
        StudioChip("clear all", false) { vm.clearAllMappings() }
        StudioChip("presets", false) { presetsOpen = true }
        StudioChip("done", true) { vm.toggleLearn() }
    }
}

/**
 * Preseti MIDI mapiranja: učitavanje na trenutnu klavijaturu, spremanje trenutnog mapiranja pod imenom,
 * izvoz u .padmidi i uvoz (za drugi tablet/računar ili drugog korisnika).
 */
@Composable
private fun MidiPresetDialog(
    vm: SamplerViewModel,
    device: String?,
    onImport: () -> Unit,
    onExport: (String) -> Unit,
    onClose: () -> Unit
) {
    val presets by vm.midiPresets.collectAsState()
    val all by vm.midiMap.collectAsState()
    val current = device?.let { all[it] }.orEmpty()
    var name by remember { mutableStateOf(device ?: "") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("MIDI presets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (device == null) "Connect a MIDI keyboard to load or save a preset."
                    else "Keyboard: $device · ${current.size} knob${if (current.size == 1) "" else "s"} mapped. " +
                        "Tap a preset to load it for this keyboard.",
                    color = TextDim, fontSize = 12.sp
                )
                Column(
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (presets.isEmpty()) Text("No presets yet", color = TextDim, fontSize = 12.sp)
                    for ((p, map) in presets) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(rShape(6))
                                .background(if (map == current && current.isNotEmpty()) Color(0xFF1D2127) else Color.Transparent)
                                .clickable { vm.loadMidiPreset(p) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                p, color = if (map == current && current.isNotEmpty()) AccentBright else TextMain,
                                fontSize = 14.sp, modifier = Modifier.weight(1f)
                            )
                            Text("${map.size} CC", color = TextFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                            Text("export", color = TextDim, fontSize = 12.sp, modifier = Modifier.clickable { onExport(p) }.padding(horizontal = 8.dp))
                            Text("✕", color = TextDim, fontSize = 13.sp, modifier = Modifier.clickable { vm.deleteMidiPreset(p) }.padding(horizontal = 8.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        label = { Text("Save current mapping as") }, modifier = Modifier.weight(1f)
                    )
                    TextButton(enabled = name.isNotBlank() && current.isNotEmpty(), onClick = { vm.saveMidiPreset(name) }) { Text("Save") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onImport) { Text("Import file…") } },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

/** Potenciometar: povuci gore/dolje; dupli dodir vraća početnu vrijednost; dodir na WAVE bira sljedeći talas. */
@Composable
private fun Knob(id: Int, k: KeysState, set: (Int, Float) -> Unit) {
    val def = P.all[id]
    val value = k.params[id]
    val current by rememberUpdatedState(value)
    var dragPos by remember { mutableStateOf(0f) }
    val learn = LocalLearn.current
    val learning by rememberUpdatedState(learn.active)
    val onPick by rememberUpdatedState(learn.onPick)
    val isTarget = learn.active && learn.target == id
    val cc = learn.ccOf(id)
    Column(
        modifier = Modifier
            .width(52.dp)
            .clip(rShape(6))
            .background(
                when {
                    isTarget -> EditBorder.copy(alpha = 0.35f)
                    learn.active && cc != null -> KeysColor.copy(alpha = 0.18f)
                    else -> Color.Transparent
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(def.label, color = TextDim, fontSize = 9.sp, maxLines = 1, letterSpacing = 0.5.sp)
        Canvas(
            modifier = Modifier
                .size(34.dp)
                .pointerInput(id) {
                    detectTapGestures(
                        onTap = {
                            when {
                                learning -> onPick(id)    // MIDI LEARN: izbor potenciometra
                                def.kind == SynthParamDef.Kind.WAVE -> set(id, ((Math.round(current) + 1) % P.WAVES.size).toFloat())
                                def.kind == SynthParamDef.Kind.CHOICE -> set(id, if (current + 1f > def.max + 0.01f) def.min else Math.round(current + 1f).toFloat())
                            }
                        },
                        onDoubleTap = { set(id, def.def) }
                    )
                }
                .pointerInput(id) {
                    val range = 160.dp.toPx()
                    detectVerticalDragGestures(
                        onDragStart = { dragPos = def.toKnob(current) },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            dragPos = (dragPos - dy / range).coerceIn(0f, 1f)
                            set(id, def.fromKnob(dragPos))
                        }
                    )
                }
        ) {
            // bipolarni (detune) od sredine, ostali od početka
            val bipolar = def.min < 0f && def.max > 0f && def.kind != SynthParamDef.Kind.INT
            drawKnob(def.toKnob(value), bipolar, KeysColor)
        }
        Text(def.format(value), color = TextMain, fontSize = 9.sp, maxLines = 1)
        if (learn.active) {
            Text(
                if (cc != null) "CC $cc" else if (isTarget) "move…" else "–",
                color = if (isTarget) EditBorder else if (cc != null) AccentBright else TextFaint,
                fontSize = 9.sp, maxLines = 1
            )
        }
    }
}

// -------------------------------------------------------------------- piano roll

@Composable
private fun PianoRoll(
    k: KeysState,
    live: LiveState,
    held: Set<Int>,
    rows: List<Int>,
    selectMode: Boolean,
    selected: Set<KeyNote>,
    onSelect: (Set<KeyNote>) -> Unit,
    onMove: (Set<KeyNote>, Int, Int) -> Unit,
    onCell: (Int, Int) -> Unit,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    /** Telefon: visina vidljivog dijela (piano roll je u spoljnom skrolu, sam se ne skroluje). */
    viewportHeight: androidx.compose.ui.unit.Dp? = null,
    modifier: Modifier = Modifier
) {
    val first = k.viewBar * DrumLayout.STEPS_PER_BAR
    // prevlačenje u SELECT modu: pomak izbora (koraci, redovi) i pravougaonik izbora (ćelije, relativno na takt)
    var drag by remember { mutableStateOf<Triple<Set<KeyNote>, Int, Int>?>(null) }
    var marquee by remember { mutableStateOf<Pair<Pair<Int, Int>, Pair<Int, Int>>?>(null) }
    // dok se vuče, note se crtaju na novom mjestu (upisuju se tek kad se pusti)
    val (notes, shownSel) = remember(k.notes, selected, drag, rows) {
        val d = drag
        if (d == null) k.notes to selected
        else KeysLayout.moveNotes(k.notes, d.first, d.second, d.third, rows, k.totalSteps)
    }
    // (pitch, step) -> true ako nota počinje tu, false ako se samo nastavlja
    val cover = remember(notes, k.bars) {
        val m = HashMap<Long, Boolean>()
        for (n in notes) {
            val end = minOf(n.step + n.length, k.totalSteps)
            for (s in n.step until end) m[n.pitch.toLong() * 1000 + s] = s == n.step || (m[n.pitch.toLong() * 1000 + s] == true)
        }
        m
    }
    // ćelije izabranih nota
    val selCover = remember(shownSel, k.bars) {
        val m = HashSet<Long>()
        for (n in shownSel) for (s in n.step until minOf(n.step + n.length, k.totalSteps)) m += n.pitch.toLong() * 1000 + s
        m
    }
    // (pitch, step) početka note -> jačina (tiša nota je blijeđa)
    val startVel = remember(notes) { notes.associate { it.pitch.toLong() * 1000 + it.step to it.velocity } }
    val hidden = remember(k.notes, rows) { k.notes.count { it.pitch !in rows } }
    val cur = rememberUpdatedState(Triple(k, rows, selected))
    // telefon: uže tipke klavijature, polja za note dobiju širinu
    val keyW = if (LocalUiPlatform.current.screenWidthDp() < 1000) 48.dp else 70.dp

    // Red ne smije biti niži od MIN_ROW (tipke i note se moraju vidjeti i pogoditi prstom); ako svi redovi ne
    // stanu, piano roll se skroluje gore/dolje.
    BoxWithConstraints(modifier = modifier) {
        val hint = if (hidden > 0) 16.dp else 0.dp
        val fit = ((viewportHeight ?: maxHeight) - hint) / rows.size.coerceAtLeast(1) - 2.dp
        val rowH = if (fit > MIN_ROW) fit else MIN_ROW
    Column(
        modifier = Modifier
            .then(if (viewportHeight != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize().verticalScroll(rememberScrollState())),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      Box {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (pitch in rows) {
            val isRoot = (pitch - k.root) % 12 == 0
            Row(
                modifier = Modifier
                    .height(rowH)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // tipka klavijature (svira dok se drži)
                val down = pitch in held
                val keyShape = rShape(5)
                Box(
                    modifier = Modifier
                        .width(keyW)
                        .fillMaxHeight()
                        // tipka je izdignuta; pritisnuta se utisne i svijetli
                        .then(if (down) Modifier.glow(keyShape, KeysColor, 0.7f) else Modifier.raised(keyShape, 1.5.dp))
                        .clip(keyShape)
                        .background(
                            when {
                                down -> KeysColor
                                KeysLayout.isBlack(pitch) -> Color(0xFF2A2E35)
                                else -> Color(0xFFCDD2DA)
                            }
                        )
                        .then(if (down) Modifier.inset(keyShape, 0.6f) else Modifier)
                        // nota izabrana za Launchpad STEP mod
                        .border(2.dp, if (k.lpStep && pitch == k.stepNote) Color(0xFF6FAF7A) else Color.Transparent, keyShape)
                        .pointerInput(pitch) {
                            detectTapGestures(onPress = {
                                onKeyDown(pitch)
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    onKeyUp(pitch)
                                }
                            })
                        }
                        .padding(start = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        KeysLayout.noteName(pitch),
                        color = when {
                            down -> Color.White
                            KeysLayout.isBlack(pitch) -> TextMain
                            else -> Ink
                        },
                        fontSize = 10.sp,
                        fontWeight = if (isRoot) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
                // 16 koraka trenutnog takta
                for (i in 0 until DrumLayout.STEPS_PER_BAR) {
                    val step = first + i
                    val c = cover[pitch.toLong() * 1000 + step]
                    val beat = i % 4 == 0
                    val playhead = step == live.keysStep
                    val cellShape = stepShape()
                    val isSel = selectMode && (pitch.toLong() * 1000 + step) in selCover
                    val rowIdx = rows.indexOf(pitch)
                    val inMarquee = marquee?.let { (a, b) ->
                        i in minOf(a.first, b.first)..maxOf(a.first, b.first) &&
                            rowIdx in minOf(a.second, b.second)..maxOf(a.second, b.second)
                    } ?: false
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = if (beat && i > 0) 3.dp else 0.dp)
                            // nota lagano svijetli, prazno polje je udubljeno
                            .then(if (c != null) Modifier.glow(cellShape, KeysColor, 0.5f) else Modifier)
                            .clip(cellShape)
                            .background(
                                when {
                                    c == true -> KeysColor.copy(
                                        alpha = 0.35f + 0.65f * (startVel[pitch.toLong() * 1000 + step] ?: 1f)
                                    ).compositeOver(EmptyPadColor)
                                    c == false -> KeysColor.copy(alpha = 0.6f).compositeOver(EmptyPadColor)
                                    isRoot -> Color(0xFF1C2230)
                                    beat -> Color(0xFF1A1D23)
                                    else -> EmptyPadColor
                                }
                            )
                            .then(
                                if (c != null) Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), cellShape)
                                else Modifier.border(1.dp, Color.White.copy(alpha = 0.04f), cellShape)
                            )
                            .then(if (playhead) Modifier.border(2.dp, Color.White.copy(alpha = 0.8f), cellShape) else Modifier)
                            // izabrana nota: žuti okvir; pravougaonik izbora: blago osvijetljena polja
                            .then(if (isSel) Modifier.border(2.dp, MuteYellow, cellShape) else Modifier)
                            .then(if (inMarquee) Modifier.background(Color.White.copy(alpha = 0.10f)) else Modifier)
                            .then(
                                if (selectMode) Modifier
                                else Modifier.pointerInput(pitch, step) { detectTapGestures { onCell(step, pitch) } }
                            )
                    )
                }
            }
        }
      }
        // SELECT: jedan sloj preko polja nota (tipke lijevo ostaju za sviranje)
        if (selectMode) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = keyW + 3.dp)
                    .pointerInput(rowH, first) {
                        val gap = 3.dp.toPx()
                        val rowP = rowH.toPx() + 2.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val (ks, rs, sel) = cur.value
                            val colW = (size.width + gap) / DrumLayout.STEPS_PER_BAR
                            fun col(x: Float) = floor(x / colW).toInt().coerceIn(0, DrumLayout.STEPS_PER_BAR - 1)
                            fun row(y: Float) = floor(y / rowP).toInt().coerceIn(0, (rs.size - 1).coerceAtLeast(0))
                            val c0 = col(down.position.x)
                            val r0 = row(down.position.y)
                            val step0 = first + c0
                            val hit = rs.getOrNull(r0)?.let { p ->
                                ks.notes.firstOrNull { it.pitch == p && step0 >= it.step && step0 < it.step + it.length }
                            }
                            val moveSel = if (hit != null && hit !in sel) setOf(hit) else sel
                            var dragging = false
                            var last = Triple(moveSel, 0, 0)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val d = ch.position - down.position
                                if (!dragging && d.getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    if (hit != null) onSelect(moveSel)
                                }
                                if (dragging) {
                                    ch.consume()
                                    if (hit != null) {
                                        last = Triple(moveSel, (d.x / colW).roundToInt(), (d.y / rowP).roundToInt())
                                        drag = last
                                    } else {
                                        marquee = (c0 to r0) to (col(ch.position.x) to row(ch.position.y))
                                    }
                                }
                            }
                            when {
                                !dragging && hit != null -> onSelect(if (hit in sel) sel - hit else sel + hit)
                                !dragging -> onSelect(emptySet())
                                hit != null -> {
                                    drag = null
                                    if (last.second != 0 || last.third != 0) onMove(last.first, last.second, last.third)
                                }
                                else -> {
                                    val m = marquee
                                    marquee = null
                                    if (m != null && rs.isNotEmpty()) {
                                        val (a, b) = m
                                        val steps = (first + minOf(a.first, b.first))..(first + maxOf(a.first, b.first))
                                        val pitches = rs.subList(minOf(a.second, b.second), maxOf(a.second, b.second) + 1).toSet()
                                        onSelect(ks.notes.filter { it.step in steps && it.pitch in pitches }.toSet())
                                    }
                                }
                            }
                        }
                    }
            )
        }
      }
        if (hidden > 0) {
            Text(
                "$hidden note${if (hidden > 1) "s" else ""} outside this view (other octave or scale)",
                color = TextFaint, fontSize = 10.sp
            )
        }
    }
    }
}

private val MIN_ROW = 30.dp

// -------------------------------------------------------------------- izbor i dijalozi

@Composable
private fun Menu(label: String, items: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        StudioChip("$label  ▾", false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, s ->
                DropdownMenuItem(text = { Text(s) }, onClick = { onSelect(i); open = false })
            }
        }
    }
}

/** MIDI IN: izbor MIDI klavijature (Auto = svi uređaji osim Launchpada). Tačka = spojeno. */
@Composable
private fun KeyboardMenu(vm: SamplerViewModel) {
    val connected by vm.keyboardDevices.collectAsState()
    val selection by vm.keyboardSelection.collectAsState()
    var open by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(open) {
        if (open) choices = withContext(Dispatchers.IO) { vm.keyboardChoices() }
    }
    val label = when {
        selection == MidiKeyboard.OFF -> "off"
        selection.isNotEmpty() -> selection
        connected.isNotEmpty() -> "Auto: " + connected.joinToString()
        else -> "Auto"
    }
    Text("MIDI IN", color = TextFaint, fontSize = 11.sp, letterSpacing = 1.sp)
    Box {
        StudioChip(label.take(28) + "  ▾", false, dot = connected.isNotEmpty() && selection != MidiKeyboard.OFF) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Auto (all except Launchpad)") }, onClick = { vm.selectKeyboard(""); open = false })
            for (d in (choices + listOfNotNull(selection.takeIf { it.isNotEmpty() && it != MidiKeyboard.OFF })).distinct()) {
                DropdownMenuItem(
                    text = { Text(d + if (d in connected) "  ●" else "") },
                    onClick = { vm.selectKeyboard(d); open = false }
                )
            }
            DropdownMenuItem(text = { Text("Off") }, onClick = { vm.selectKeyboard(MidiKeyboard.OFF); open = false })
        }
    }
}

@Composable
private fun PresetDialog(vm: SamplerViewModel, k: KeysState, onClose: () -> Unit) {
    val user by vm.userPresets.collectAsState()
    var name by remember { mutableStateOf(if (vm.builtInPresets.any { it.name == k.presetName }) "" else k.presetName) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Synth presets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // ugrađeni po grupama (BASS, LEAD, PAD, KEYS, FX)
                    var lastCat = ""
                    for (p in vm.builtInPresets) {
                        if (p.category != lastCat && p.category != "INIT") {
                            Spacer(Modifier.height(4.dp))
                            Text(p.category, color = TextFaint, fontSize = 10.sp, letterSpacing = 1.sp)
                        }
                        lastCat = p.category
                        PresetRow(p.name, p.name == k.presetName, null) { vm.loadPreset(p); onClose() }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("MY PRESETS", color = TextFaint, fontSize = 10.sp, letterSpacing = 1.sp)
                    if (user.isEmpty()) Text("None yet", color = TextDim, fontSize = 12.sp)
                    for (p in user) {
                        PresetRow(p.name, p.name == k.presetName, onDelete = { vm.deleteUserPreset(p.name) }) {
                            vm.loadPreset(p); onClose()
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        label = { Text("Save current sound as") }, modifier = Modifier.weight(1f)
                    )
                    TextButton(enabled = name.isNotBlank(), onClick = { vm.saveUserPreset(name); onClose() }) { Text("Save") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun PresetRow(name: String, current: Boolean, onDelete: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rShape(6))
            .background(if (current) Color(0xFF1D2127) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = if (current) AccentBright else TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (onDelete != null) {
            Text("✕", color = TextDim, fontSize = 13.sp, modifier = Modifier.clickable { onDelete() }.padding(horizontal = 8.dp))
        }
    }
}
