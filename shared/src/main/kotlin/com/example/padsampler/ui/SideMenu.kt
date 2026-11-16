package com.example.padsampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Uska lijeva traka: ☰ (meni), EDIT (dodjela semplova padovima) i STOP ALL (uvijek pri ruci za nastup).
 * Sve ostalo je u meniju [SideMenu].
 */
@Composable
fun SideRail(
    editMode: Boolean,
    onMenu: () -> Unit,
    onEdit: () -> Unit,
    onStopAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = rShape(12)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(shape)
                .background(PanelColor)
                .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
                .clickable { onMenu() },
            contentAlignment = Alignment.Center
        ) { Text("☰", color = Color.White, fontSize = 24.sp) }
        FlatButton(
            if (editMode) "done" else "edit",
            modifier = Modifier.fillMaxWidth(),
            color = if (editMode) EditBorder else PanelColor,
            textColor = if (editMode) Color.Black else Color.White
        ) { onEdit() }
        Spacer(Modifier.weight(1f))
        FlatButton(
            "STOP\nALL",
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            color = StopRedBottom
        ) { onStopAll() }
    }
}

/** Jedna stavka menija: običan tekst preko cijele širine (bez dugmeta). */
@Composable
private fun MenuItem(text: String, hint: String? = null, color: Color = TextMain, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rShape(8))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Text(text, color = color, fontSize = 16.sp)
        if (hint != null) Text(hint, color = TextFaint, fontSize = 11.sp)
    }
}

@Composable
private fun MenuSection(title: String) {
    Text(
        title, color = TextFaint, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp)
    )
}

/**
 * Meni (☰): izlazi s lijeve strane preko ekrana; dodir pored menija ga zatvara. Stavke su tekst, kao u meniju.
 */
@Composable
fun BoxScope.SideMenu(
    editMode: Boolean,
    linkOn: Boolean,
    linkAudio: Boolean,
    linkOffsetMs: Int,
    midiStatus: String,
    info: String,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onLoad: () -> Unit,
    onNew: () -> Unit,
    onSetList: () -> Unit,
    onLinkAudio: () -> Unit,
    onLinkOffset: (Int) -> Unit,
    onAbout: () -> Unit,
    batteryRestricted: Boolean? = null,
    onBattery: () -> Unit = {}
) {
    // zatamnjenje: dodir zatvara meni
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    )
    Column(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .widthIn(max = 320.dp)
            .width(300.dp)
            .background(Color(0xFF181B20))
            .border(1.dp, Color.White.copy(alpha = 0.06f))
            .pointerInput(Unit) { detectTapGestures { } }   // dodir u meniju ne zatvara meni
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PadSampler", color = TextMain, fontSize = 18.sp, fontFamily = AppFonts.audiowide,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = 0.55f), blurRadius = 14f)),
                modifier = Modifier.weight(1f).padding(start = 14.dp)
            )
            Text(
                "✕", color = TextDim, fontSize = 20.sp,
                modifier = Modifier.clip(rShape(8)).clickable { onClose() }.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        MenuSection("SAMPLES")
        MenuItem(
            if (editMode) "Finish editing" else "Load samples",
            if (editMode) "Edit mode is on" else "Tap a pad to assign a sample, set loop or remove it",
            color = if (editMode) EditBorder else TextMain
        ) { onEdit(); onClose() }

        MenuSection("SESSION")
        MenuItem("Save session…") { onSave(); onClose() }
        MenuItem("Export session…", "Project file + samples in folders") { onExport(); onClose() }
        MenuItem("Load session…") { onLoad(); onClose() }
        MenuItem("New session…") { onNew(); onClose() }
        MenuItem("Set list…") { onSetList(); onClose() }

        if (linkOn) {
            MenuSection("ABLETON LINK")
            MenuItem(if (linkAudio) "Link audio: on" else "Link audio: off", "Send the master to Link Audio") { onLinkAudio() }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Link offset", color = TextMain, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(
                    "−", color = TextMain, fontSize = 20.sp,
                    modifier = Modifier.clip(rShape(8)).clickable { onLinkOffset(-5) }.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Text("$linkOffsetMs ms", color = TextMain, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
                Text(
                    "+", color = TextMain, fontSize = 20.sp,
                    modifier = Modifier.clip(rShape(8)).clickable { onLinkOffset(+5) }.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        MenuSection("STATUS")
        Text(midiStatus, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp))
        Text(info, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp))
        // Android: štednja baterije može pauzirati zvuk u pozadini (Xiaomi); dodir otvara podešavanja aplikacije
        when (batteryRestricted) {
            true -> MenuItem(
                "Battery saver is on",
                "Android may pause audio in the background. Tap, then set battery to Unrestricted / No restrictions",
                color = MuteYellow
            ) { onBattery() }
            false -> Text("Battery: unrestricted", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp))
            null -> {}
        }

        MenuSection("ABOUT")
        MenuItem("About PadSampler", "Version, license, source code") { onAbout(); onClose() }
    }
}

/**
 * Poruke (snimljeno, printano, greške...) kao kratka obavijest dolje na sredini, nestaje sama poslije 5 s.
 * Dok je edit mod uključen, iznad nje stoji uputstvo.
 */
@Composable
fun BoxScope.MessageToast(message: String, editMode: Boolean) {
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(message) {
        if (message.isNotEmpty()) {
            shown = message
            delay(5000)
            shown = ""
        }
    }
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (editMode) Toast("Edit mode: tap a pad to assign a sample (wav, aiff, mp3), set loop or remove it", EditBorder)
        if (shown.isNotEmpty()) Toast(shown, TextMain)
    }
}

@Composable
private fun Toast(text: String, color: Color) {
    val shape = rShape(10)
    Text(
        text, color = color, fontSize = 13.sp,
        modifier = Modifier
            .widthIn(max = 640.dp)
            .clip(shape)
            .background(Color(0xF0202429))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    )
}
