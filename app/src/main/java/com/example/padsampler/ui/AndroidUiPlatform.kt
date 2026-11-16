package com.example.padsampler.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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

/** Android implementacija UiPlatform-a: logo iz resursa, SAF izbornici, tipka nazad, baterija. */
class AndroidUiPlatform(private val context: Context) : UiPlatform {
    override fun batteryRestricted(): Boolean? {
        return try {
            val pm = context.getSystemService(PowerManager::class.java)
            pm.isIgnoringBatteryOptimizations(context.packageName).not()
        } catch (_: Exception) {
            null
        }
    }

    // Google Play ne dozvoljava muzičkim aplikacijama REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (direktan zahtjev),
    // pa se otvara stranica aplikacije u podešavanjima: Xiaomi "Battery saver" -> No restrictions,
    // ostali "App battery usage" -> Unrestricted.
    override fun openBatterySettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

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
