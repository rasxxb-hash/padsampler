package com.example.padsampler.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.padsampler.PadSamplerScreen
import com.example.padsampler.SamplerViewModel
import com.example.padsampler.ui.AppFonts
import com.example.padsampler.ui.LocalUiPlatform
import java.io.File

/** Učitava motor iz resursa aplikacije: padsampler.dll (Windows) / libpadsampler.so (Linux). */
private fun loadNativeEngine() {
    val res = System.getProperty("compose.application.resources.dir")
    val dll = res?.let { File(it, System.mapLibraryName("padsampler")) }
    if (dll != null && dll.isFile) {
        System.load(dll.absolutePath)
        System.setProperty("padsampler.preloaded", "1")
    }
    // inače NativeAudio traži biblioteku u java.library.path
}

fun main() {
    java.util.Locale.setDefault(java.util.Locale.ENGLISH)  // system dialogs in English
    loadNativeEngine()
    // Windows izgled dijaloga za izbor fajla/foldera
    try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
    loadDesktopFonts()

    val platform = DesktopPlatform()
    // Sačuvani audio izlaz važi od prvog pokretanja zvuka (SamplerViewModel zove NativeAudio.start()).
    try { DesktopSettings.applySavedAudio(platform) } catch (_: Throwable) {}
    // Ekran (i računar) ne gase se dok aplikacija radi
    try { DesktopAudio.keepAwake(true) } catch (_: Throwable) {}

    application {
        val vm = remember { SamplerViewModel(platform) }
        // Običan prozor (pamti veličinu i mjesto dok je aplikacija u full screenu)
        val normalState = rememberWindowState(width = 1280.dp, height = 800.dp)
        var fullscreen by remember { mutableStateOf(platform.getInt(DesktopSettings.FULLSCREEN, 0) == 1) }
        // Monitor na kojem je prozor (za full screen); null prije prvog prikaza
        val currentWindow = remember { arrayOfNulls<ComposeWindow>(1) }

        fun setFullscreen(on: Boolean) {
            if (on == fullscreen) return
            fullscreen = on
            platform.putInt(DesktopSettings.FULLSCREEN, if (on) 1 else 0)
        }
        val uiPlatform = remember {
            DesktopUiPlatform(platform, vm, fullscreen = { fullscreen }, setFullscreen = { setFullscreen(it) })
        }

        // Full screen = prozor BEZ okvira preko cijelog monitora (WindowPlacement.Fullscreen na Windowsu samo
        // raširi prozor sa naslovnom trakom). Okvir se ne može mijenjati na otvorenom prozoru, pa se prozor
        // ponovo pravi (key); stanje aplikacije je u vm-u i ostaje.
        key(fullscreen) {
            val state = if (fullscreen) {
                remember {
                    val b = currentWindow[0]?.graphicsConfiguration?.bounds
                        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                    WindowState(
                        placement = WindowPlacement.Floating,
                        position = WindowPosition(b.x.dp, b.y.dp),
                        size = DpSize(b.width.dp, b.height.dp)
                    )
                }
            } else normalState

            Window(
                onCloseRequest = {
                    vm.shutdown()   // saves, stops MIDI, Ableton Link and audio
                    try { DesktopAudio.keepAwake(false) } catch (_: Throwable) {}
                    exitApplication()
                    // Make sure no native thread keeps the process (and the installed files) alive
                    Runtime.getRuntime().halt(0)
                },
                title = "PadSampler",
                icon = painterResource("app_icon.png"),
                state = state,
                undecorated = fullscreen,
                resizable = !fullscreen,
                onPreviewKeyEvent = { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.F11) {
                        setFullscreen(!fullscreen)
                        true
                    } else if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.Z) {
                        vm.studioUndo(redo = e.isShiftPressed)       // Ctrl+Z / Ctrl+Shift+Z (Drums, Keys)
                    } else if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.Y) {
                        vm.studioUndo(redo = true)
                    } else false
                }
            ) {
                SideEffect { currentWindow[0] = window }
                CompositionLocalProvider(LocalUiPlatform provides uiPlatform) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        ProvideTextStyle(TextStyle(fontFamily = AppFonts.inter)) {
                            PadSamplerScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
