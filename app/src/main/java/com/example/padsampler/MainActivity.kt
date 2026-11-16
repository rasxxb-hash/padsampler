package com.example.padsampler

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.padsampler.platform.AndroidPlatform
import com.example.padsampler.ui.AppFonts
import com.example.padsampler.ui.AndroidUiPlatform
import com.example.padsampler.ui.LocalUiPlatform
import com.example.padsampler.ui.loadAndroidFonts

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ekran ne smije zaspati tokom svirke
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startAudioService()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        loadAndroidFonts(this)
        setContent {
            val uiPlatform = remember { AndroidUiPlatform(applicationContext) }
            CompositionLocalProvider(LocalUiPlatform provides uiPlatform) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    ProvideTextStyle(TextStyle(fontFamily = AppFonts.inter)) {
                        PadSamplerScreen(rememberSamplerViewModel())
                    }
                }
            }
        }
    }

    // Dok je app na ekranu servis ne drži CPU wake lock (ekran je upaljen); u pozadini / pri zaključavanju drži.
    override fun onStart() {
        super.onStart()
        sendToService(AudioService.ACTION_RELEASE)
    }

    override fun onStop() {
        sendToService(AudioService.ACTION_HOLD)
        super.onStop()
    }

    // Zatvaranje aplikacije (back / finish) gasi servis, pa nema obavijesti ni potrošnje baterije bez razloga
    override fun onDestroy() {
        if (isFinishing) {
            try { stopService(Intent(this, AudioService::class.java)) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun sendToService(action: String) {
        try { startService(Intent(this, AudioService::class.java).setAction(action)) } catch (_: Exception) {}
    }

    // Puni ekran: sakrij statusnu i navigacionu traku (povlačenje s ruba ih privremeno vrati)
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // Nakon dijaloga ili izbora fajla trake se mogu vratiti, pa ih ponovo sakrij
    // Foreground servis drži audio živim i kad je ekran zaključan / app u pozadini (Xiaomi inače ubija procese)
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* servis radi i bez odobrenja */ }

    private fun startAudioService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, AudioService::class.java))
        } catch (_: Exception) {}
        // Štednja baterije: nema direktnog zahtjeva (Google Play to ne dozvoljava muzičkim aplikacijama);
        // ☰ meni (STATUS) pokazuje stanje i otvara podešavanja (AndroidUiPlatform.openBatterySettings).
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}

/** ViewModel dobija Android implementaciju Platform-a (SamplerViewModel više ne zna za Android). */
@Composable
fun rememberSamplerViewModel(): SamplerViewModel {
    val app = LocalContext.current.applicationContext as Application
    return viewModel(factory = viewModelFactory { initializer { SamplerViewModel(AndroidPlatform(app)) } })
}
