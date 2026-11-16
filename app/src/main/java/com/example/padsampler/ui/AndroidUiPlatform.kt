package com.example.padsampler.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import com.example.padsampler.R

/** Android implementacija UiPlatform-a: logo iz resursa, SAF izbornici, tipka nazad. */
class AndroidUiPlatform : UiPlatform {
    @Composable
    override fun logoPainter(): Painter = painterResource(R.drawable.ic_logo)

    @Composable
    override fun screenWidthDp(): Int = LocalConfiguration.current.screenWidthDp

    @Composable
    override fun BackHandler(onBack: () -> Unit) {
        androidx.activity.compose.BackHandler(onBack = onBack)
    }

    @Composable
    override fun rememberFilePicker(onPicked: (String) -> Unit): () -> Unit {
        val current by rememberUpdatedState(onPicked)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) current(uri.toString())
        }
        return remember(launcher) { { launcher.launch(arrayOf("audio/*", "application/octet-stream")) } }
    }

    @Composable
    override fun rememberMidiFilePicker(onPicked: (String) -> Unit): () -> Unit {
        val current by rememberUpdatedState(onPicked)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) current(uri.toString())
        }
        // .mid nema uvijek MIME tip (zavisi od izvora), pa su dozvoljeni svi fajlovi
        return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
    }

    @Composable
    override fun rememberSessionFilePicker(onPicked: (String) -> Unit): () -> Unit {
        val current by rememberUpdatedState(onPicked)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) current(uri.toString())
        }
        // .padsession nema svoj MIME tip, pa su dozvoljeni svi fajlovi
        return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
    }

    @Composable
    override fun rememberSessionSavePicker(onPicked: (String) -> Unit): (String) -> Unit {
        val current by rememberUpdatedState(onPicked)
        // octet-stream: Android ne dodaje svoju ekstenziju (ostaje .padsession)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) current(uri.toString())
        }
        return remember(launcher) { { name: String -> launcher.launch(name) } }
    }

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
        val current by rememberUpdatedState(onPicked)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) current(uri.toString())
        }
        return remember(launcher) { { launcher.launch(null) } }
    }
}
