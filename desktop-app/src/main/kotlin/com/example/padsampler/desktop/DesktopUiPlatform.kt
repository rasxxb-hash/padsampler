package com.example.padsampler.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import com.example.padsampler.SamplerViewModel
import com.example.padsampler.ui.UiPlatform
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import java.util.prefs.Preferences
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Windows implementacija UiPlatform-a: logo iz resursa, standardni dijalozi za fajl i folder, podešavanja.
 * fullscreen / setFullscreen: stanje prozora (Main.kt).
 */
class DesktopUiPlatform(
    private val platform: DesktopPlatform,
    private val vm: SamplerViewModel,
    private val fullscreen: () -> Boolean,
    private val setFullscreen: (Boolean) -> Unit
) : UiPlatform {
    override val hasSettings: Boolean get() = true

    // Laptop: baterija iz Windowsa (GetSystemPowerStatus); stoni računar nema bateriju -> ne prikazuje se.
    override fun battery(): com.example.padsampler.ui.BatteryInfo? {
        val v = try { DesktopAudio.battery() } catch (_: Throwable) { -1 }
        if (v < 0) return null
        return com.example.padsampler.ui.BatteryInfo((v % 1000).coerceIn(0, 100), v >= 1000)
    }

    @Composable
    override fun SettingsDialog(onClose: () -> Unit) {
        DesktopSettingsDialog(platform, vm, fullscreen(), setFullscreen, onClose)
    }

    @Composable
    override fun logoPainter(): Painter = painterResource("ic_logo.xml")

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    override fun screenWidthDp(): Int {
        val w = LocalWindowInfo.current.containerSize.width
        val d = LocalDensity.current.density
        return if (w <= 0) 1400 else (w / d).toInt()
    }

    @Composable
    override fun BackHandler(onBack: () -> Unit) {
        // Na računaru nema tipke "nazad"; ekrani imaju svoja dugmad.
    }

    @Composable
    override fun rememberFilePicker(onPicked: (String) -> Unit): () -> Unit {
        val current by rememberUpdatedState(onPicked)
        return remember {
            {
                SwingUtilities.invokeLater {
                    val ch = newChooser("Choose an audio file").apply {
                        fileSelectionMode = JFileChooser.FILES_ONLY
                        val audio = FileNameExtensionFilter("Audio (wav, aiff, mp3)", "wav", "aif", "aiff", "aifc", "mp3")
                        addChoosableFileFilter(audio)
                        fileFilter = audio
                    }
                    if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        rememberDir(ch.selectedFile.parentFile)
                        current(ch.selectedFile.absolutePath.replace('\\', '/'))
                    }
                }
            }
        }
    }

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
        val current by rememberUpdatedState(onPicked)
        return remember {
            {
                SwingUtilities.invokeLater {
                    val ch = newChooser("Choose a folder").apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                    if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        rememberDir(ch.selectedFile)
                        current(ch.selectedFile.absolutePath.replace('\\', '/'))
                    }
                }
            }
        }
    }

    @Composable
    override fun rememberMidiFilePicker(onPicked: (String) -> Unit): () -> Unit {
        val current by rememberUpdatedState(onPicked)
        return remember {
            {
                SwingUtilities.invokeLater {
                    val ch = newChooser("Import MIDI file").apply {
                        fileSelectionMode = JFileChooser.FILES_ONLY
                        val f = FileNameExtensionFilter("MIDI file (*.mid, *.midi)", "mid", "midi", "smf")
                        addChoosableFileFilter(f)
                        fileFilter = f
                    }
                    if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        rememberDir(ch.selectedFile.parentFile)
                        current(ch.selectedFile.absolutePath.replace('\\', '/'))
                    }
                }
            }
        }
    }

    @Composable
    override fun rememberSessionFilePicker(onPicked: (String) -> Unit): () -> Unit =
        rememberOpenPicker("Import session", "PadSampler session (*.padsession)", "padsession", onPicked)

    @Composable
    override fun rememberSessionSavePicker(onPicked: (String) -> Unit): (String) -> Unit =
        rememberSavePicker("Export session", "PadSampler session (*.padsession)", "padsession", onPicked)

    @Composable
    override fun rememberMidiPresetFilePicker(onPicked: (String) -> Unit): () -> Unit =
        rememberOpenPicker("Import MIDI preset", "PadSampler MIDI preset (*.padmidi)", "padmidi", onPicked)

    @Composable
    override fun rememberMidiPresetSavePicker(onPicked: (String) -> Unit): (String) -> Unit =
        rememberSavePicker("Export MIDI preset", "PadSampler MIDI preset (*.padmidi)", "padmidi", onPicked)

    /** "Open" za jedan tip fajla (ekstenzija bez tačke). */
    @Composable
    private fun rememberOpenPicker(title: String, desc: String, ext: String, onPicked: (String) -> Unit): () -> Unit {
        val current by rememberUpdatedState(onPicked)
        return remember {
            {
                SwingUtilities.invokeLater {
                    val ch = newChooser(title).apply {
                        fileSelectionMode = JFileChooser.FILES_ONLY
                        val f = FileNameExtensionFilter(desc, ext)
                        addChoosableFileFilter(f)
                        fileFilter = f
                    }
                    if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        rememberDir(ch.selectedFile.parentFile)
                        current(ch.selectedFile.absolutePath.replace('\\', '/'))
                    }
                }
            }
        }
    }

    /** "Save as" za jedan tip fajla: dodaje ekstenziju ako fali i pita prije zamjene postojećeg fajla. */
    @Composable
    private fun rememberSavePicker(title: String, desc: String, ext: String, onPicked: (String) -> Unit): (String) -> Unit {
        val current by rememberUpdatedState(onPicked)
        return remember {
            { name: String ->
                SwingUtilities.invokeLater {
                    val ch = newChooser(title).apply {
                        fileSelectionMode = JFileChooser.FILES_ONLY
                        fileFilter = FileNameExtensionFilter(desc, ext)
                        selectedFile = File(currentDirectory, name)
                    }
                    if (ch.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                        var f = ch.selectedFile
                        if (!f.name.endsWith(".$ext", ignoreCase = true)) f = File(f.parentFile, f.name + ".$ext")
                        val ok = !f.exists() || JOptionPane.showConfirmDialog(
                            null, "\"${f.name}\" already exists. Replace it?", title,
                            JOptionPane.YES_NO_OPTION
                        ) == JOptionPane.YES_OPTION
                        if (ok) {
                            rememberDir(f.parentFile)
                            current(f.absolutePath.replace('\\', '/'))
                        }
                    }
                }
            }
        }
    }

    // Dijalog se otvara u zadnje korištenom folderu (pamti se između pokretanja).
    private val prefs = Preferences.userNodeForPackage(DesktopUiPlatform::class.java)

    private fun newChooser(title: String): JFileChooser {
        val last = prefs.get("lastDir", null)?.let { File(it) }?.takeIf { it.isDirectory }
        return JFileChooser(last).apply { dialogTitle = title }
    }

    private fun rememberDir(dir: File?) {
        if (dir != null) prefs.put("lastDir", dir.absolutePath)
    }
}
