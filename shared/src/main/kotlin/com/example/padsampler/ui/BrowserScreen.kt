package com.example.padsampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.SamplerViewModel
import com.example.padsampler.data.LibEntry
import com.example.padsampler.model.Layout
import kotlin.math.abs

private val PlayGreen = Color(0xFF3CFF6E)

/** Vrsta sempla za filter: loop (ima BPM ili "loop" u imenu) ili one-shot. */
private enum class Kind(val label: String) { ALL("All"), LOOPS("Loops"), ONESHOTS("One-shots") }

/** Kategorije prepoznate iz imena foldera i fajla. */
private enum class Category(val label: String, val words: List<String>) {
    DRUMS("Drums", listOf("drum", "kick", "snare", "hat", "clap", "perc", "tom", "cymbal", "ride", "crash", "break", "beat", "shaker", "rim")),
    BASS("Bass", listOf("bass", "sub", "808")),
    KEYS("Keys", listOf("key", "piano", "rhodes", "organ", "synth", "chord", "pad", "lead", "arp", "pluck", "string")),
    VOCALS("Vocals", listOf("vox", "vocal", "voice", "acapella", "choir")),
    FX("FX", listOf("fx", "riser", "impact", "sweep", "noise", "uplift", "downlift", "whoosh", "reverse"));

    fun matches(e: LibEntry) = words.any { it in e.hay }
}

private fun isLoop(e: LibEntry) = e.bpm != null || "loop" in e.name.lowercase()

/** Stablo foldera iz indeksa: djeca svakog foldera i broj semplova (sa podfolderima). "" = vrh. */
private class FolderTree(entries: List<LibEntry>) {
    val children = HashMap<String, MutableList<String>>()
    val counts = HashMap<String, Int>()

    init {
        val seen = HashSet<String>()
        for (e in entries) {
            var parent = ""
            var path = ""
            for (seg in e.dir.split('/')) {
                if (seg.isEmpty()) continue
                path = if (path.isEmpty()) seg else "$path/$seg"
                counts[path] = (counts[path] ?: 0) + 1
                if (seen.add(path)) children.getOrPut(parent) { ArrayList() }.add(path)
                parent = path
            }
        }
        for (l in children.values) l.sortBy { it.substringAfterLast('/').lowercase() }
    }
}

/**
 * Browser biblioteke (kao u Abletonu): lijevo stablo foldera, desno semplovi izabranog foldera (sa podfolderima).
 * Dodir na red pušta sempl (ponovni dodir zaustavlja), "Use" ga dodjeljuje. Filteri: loop / one-shot, blizu
 * tempa sesije, kategorija iz imena. Pretraga traži u izabranom folderu.
 */
@Composable
fun BrowserScreen(vm: SamplerViewModel, modifier: Modifier = Modifier) {
    val lib = vm.library
    val roots by lib.roots.collectAsState()
    val scan by lib.scan.collectAsState()
    val count by lib.count.collectAsState()
    val slot by vm.browserSlot.collectAsState()
    val drumLane by vm.drumBrowserLane.collectAsState()
    val previewKey by vm.previewKey.collectAsState()
    val progress by vm.previewProgress.collectAsState()
    val match by vm.previewMatch.collectAsState()
    val bpm by vm.bpm.collectAsState()

    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(browserFolder) }         // "" = cijela biblioteka
    var expanded by remember { mutableStateOf(browserExpanded) }
    var kind by remember { mutableStateOf(Kind.ALL) }
    var nearTempo by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf<Category?>(null) }

    val ui = LocalUiPlatform.current
    val compact = ui.screenWidthDp() < 1000
    var treeOpen by remember { mutableStateOf(!compact) }
    val addFolder = ui.rememberFolderPicker { vm.addLibraryFolder(it) }
    ui.BackHandler { vm.closeBrowser() }

    val tree = remember(count) { FolderTree(lib.entries) }
    // folder koji je nestao (uklonjen / rescan) = cijela biblioteka
    val selected = if (chosen.isEmpty() || chosen in tree.counts) chosen else ""

    val inFolder = remember(selected, count) {
        if (selected.isEmpty()) lib.entries
        else lib.entries.filter { it.dir == selected || it.dir.startsWith("$selected/") }
    }
    val shown = remember(inFolder, query, kind, nearTempo, category, bpm) {
        val tokens = query.lowercase().split(' ', '_', '-').filter { it.isNotBlank() }
        inFolder.filter { e ->
            (tokens.isEmpty() || tokens.all { it in e.hay }) &&
                when (kind) {
                    Kind.ALL -> true
                    Kind.LOOPS -> isLoop(e)
                    Kind.ONESHOTS -> !isLoop(e)
                } &&
                (!nearTempo || e.bpm?.let { abs(it - bpm) <= NEAR_BPM } == true) &&
                (category?.matches(e) ?: true)
        }
    }
    val directFiles = remember(selected, count) { if (selected.isEmpty()) 0 else inFolder.count { it.dir == selected } }

    fun select(path: String) {
        chosen = path
        browserFolder = path
    }

    fun toggle(path: String) {
        expanded = if (path in expanded) expanded - path else expanded + path
        browserExpanded = expanded
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .pointerInput(Unit) { detectTapGestures { } }   // blokira dodire prema ekranu ispod
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ------------------------------------------------------------ naslov
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlatButton("← Back") { vm.closeBrowser() }
            val s = slot
            Text(
                when {
                    drumLane != null -> "Library: drum sound ${drumLane!! + 1}"
                    s != null -> "Library: assign to scene ${s / Layout.TRACKS + 1}, track ${s % Layout.TRACKS + 1}"
                    else -> "Library"
                },
                color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            FlatButton(
                if (match) "Preview: at $bpm BPM" else "Preview: original speed",
                color = if (match) PlayGreen else PanelColor,
                textColor = if (match) Color.Black else Color.White
            ) { vm.setPreviewMatch(!match) }
        }

        // ------------------------------------------------------------ pretraga i folderi biblioteke
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (compact) FlatButton(if (treeOpen) "Hide folders" else "Folders") { treeOpen = !treeOpen }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (selected.isEmpty()) "Search the library" else "Search in ${selected.substringAfterLast('/')}") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (query.isNotEmpty()) FlatButton("✕") { query = "" }
            FlatButton("Add folder") { addFolder() }
            FlatButton("Rescan", enabled = roots.isNotEmpty() && !scan.running) { vm.rescanLibrary() }
        }

        // ------------------------------------------------------------ filteri
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.hScroll()
        ) {
            for (k in Kind.values()) StudioChip(k.label, kind == k) { kind = k }
            Spacer(Modifier.width(6.dp))
            StudioChip("Near $bpm BPM (±${NEAR_BPM.toInt()})", nearTempo) { nearTempo = !nearTempo }
            Spacer(Modifier.width(6.dp))
            StudioChip("Any", category == null) { category = null }
            for (c in Category.values()) StudioChip(c.label, category == c) { category = if (category == c) null else c }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    scan.running -> "Scanning... ${scan.found} found"
                    scan.error != null -> "Scan error: ${scan.error}"
                    else -> "${shown.size} of $count samples"
                },
                color = TextDim, fontSize = 12.sp
            )
        }

        if (roots.isEmpty()) {
            Text(
                "Add the folder that holds your samples (\"Add folder\"). It is scanned once and remembered, " +
                    "so searching thousands of samples afterwards is instant. The BPM is read from folder and file names.",
                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(16.dp)
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // -------------------------------------------------------- stablo foldera
            if (treeOpen) {
                FolderPanel(
                    tree = tree, expanded = expanded, selected = selected, total = count,
                    roots = roots.map { it.id to it.name },
                    onSelect = { select(it); if (compact) treeOpen = false },
                    onToggle = { toggle(it) },
                    onRemoveRoot = { vm.removeLibraryFolder(it) },
                    modifier = Modifier
                        .width(if (compact) 240.dp else 290.dp)
                        .fillMaxHeight()
                )
            }

            // -------------------------------------------------------- semplovi
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (selected.isEmpty()) "All samples" else selected.replace("/", "  ›  "),
                        color = EditBorder, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (slot != null && drumLane == null && directFiles > 0) {
                        FlatButton("Fill column (first ${minOf(directFiles, Layout.SCENES)})", color = Color(0xFF2A4D8F)) {
                            vm.fillColumnFromFolder(selected)
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (shown.isEmpty()) {
                        item { Text("No samples match.", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                    }
                    items(shown.take(MAX_ROWS), key = { it.rootId.toString() + it.docId }) { e ->
                        val sub = if (selected.isEmpty()) e.dir else e.dir.removePrefix(selected).removePrefix("/")
                        SampleRow(
                            e, sub = sub, playing = previewKey == e.docId, progress = progress,
                            onPlay = { vm.togglePreview(e) }, onUse = { vm.useFromLibrary(e) }
                        )
                    }
                    if (shown.size > MAX_ROWS) {
                        item {
                            Text(
                                "Showing the first $MAX_ROWS of ${shown.size}: choose a folder or search to narrow it down.",
                                color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val NEAR_BPM = 8.0
private const val MAX_ROWS = 1500

// Browser pamti izabrani folder i otvorene foldere dok aplikacija radi
private var browserFolder = ""
private var browserExpanded: Set<String> = emptySet()

@Composable
private fun FolderPanel(
    tree: FolderTree,
    expanded: Set<String>,
    selected: String,
    total: Int,
    roots: List<Pair<Int, String>>,
    onSelect: (String) -> Unit,
    onToggle: (String) -> Unit,
    onRemoveRoot: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // vidljivi redovi: (putanja, dubina)
    val rows = remember(tree, expanded) {
        val out = ArrayList<Pair<String, Int>>()
        fun add(parent: String, depth: Int) {
            for (c in tree.children[parent] ?: emptyList()) {
                out += c to depth
                if (c in expanded) add(c, depth + 1)
            }
        }
        add("", 0)
        out
    }
    var removeMode by remember { mutableStateOf(false) }
    val shape = rShape(10)
    Column(
        modifier = modifier
            .clip(shape)
            .background(PanelColor)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(6.dp)
    ) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item {
                FolderRow("All samples", total, 0, hasChildren = false, open = false, selected = selected.isEmpty(),
                    onClick = { onSelect("") }, onToggle = {})
            }
            items(rows, key = { it.first }) { (path, depth) ->
                FolderRow(
                    name = path.substringAfterLast('/'),
                    count = tree.counts[path] ?: 0,
                    depth = depth + 1,
                    hasChildren = tree.children[path]?.isNotEmpty() == true,
                    open = path in expanded,
                    selected = path == selected,
                    onClick = {
                        onSelect(path)
                        if (path !in expanded && tree.children[path]?.isNotEmpty() == true) onToggle(path)
                    },
                    onToggle = { onToggle(path) }
                )
            }
        }
        // uklanjanje glavnih foldera iz biblioteke (fajlovi ostaju na disku)
        Text(
            if (removeMode) "Tap a library folder to remove it:" else "Remove a library folder…",
            color = TextFaint, fontSize = 11.sp,
            modifier = Modifier
                .clickable { removeMode = !removeMode }
                .padding(horizontal = 6.dp, vertical = 6.dp)
        )
        if (removeMode) {
            for ((id, name) in roots) {
                Text(
                    "$name  ✕", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(rShape(6))
                        .background(Color(0xFF3A2224))
                        .clickable { onRemoveRoot(id); removeMode = false }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    count: Int,
    depth: Int,
    hasChildren: Boolean,
    open: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rShape(6))
            .background(if (selected) Color(0xFF2A3140) else Color.Transparent)
            .clickable { onClick() }
            .padding(start = (4 + depth * 12).dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // strelica otvara/zatvara bez izbora foldera
        Box(
            modifier = Modifier
                .width(26.dp)
                .height(36.dp)
                .then(if (hasChildren) Modifier.clickable { onToggle() } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (hasChildren) Text(if (open) "▾" else "▸", color = TextDim, fontSize = 13.sp)
        }
        Text(
            name, color = if (selected) Color.White else TextMain, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Text("$count", color = TextFaint, fontSize = 11.sp)
    }
}

/** Red sempla: dodir bilo gdje pušta/zaustavlja preslušavanje; "Use" dodjeljuje. */
@Composable
private fun SampleRow(
    e: LibEntry,
    sub: String,
    playing: Boolean,
    progress: Float,
    onPlay: () -> Unit,
    onUse: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rShape(8))
            .background(if (playing) Color(0xFF1F3527) else PanelColor)
            .clickable { onPlay() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(if (playing) "■" else "▶", color = if (playing) PlayGreen else TextDim, fontSize = 14.sp, modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(e.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotEmpty()) {
                    Text(sub, color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            e.key?.let { k ->
                Text(k, color = Color(0xFFCC8A4D), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            e.bpm?.let { b ->
                Text(if (b % 1.0 == 0.0) "${b.toInt()} BPM" else "%.1f BPM".format(b), color = PlayGreen, fontSize = 12.sp)
            }
            FlatButton("Use", color = Color(0xFF2A4D8F)) { onUse() }
        }
        if (playing && progress >= 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(PlayGreen)
            )
        } else {
            Spacer(Modifier.height(3.dp))
        }
    }
}
