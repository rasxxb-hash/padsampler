package com.example.padsampler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground servis: sprječava Android (posebno Xiaomi) da ubije aplikaciju ili uspori audio kad je ekran
 * zaključan ili je aplikacija u pozadini. Audio engine i dalje živi u procesu (NativeAudio); servis samo drži
 * proces "u prvom planu" i CPU budnim dok aplikacija radi.
 */
class AudioService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Audio engine", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setContentTitle("PadSampler")
            .setContentText("Audio engine running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        // CPU lock treba samo dok app nije na ekranu (zaključan ekran / pozadina). Dok je app otvorena,
        // ekran je upaljen (FLAG_KEEP_SCREEN_ON), pa wake lock samo troši bateriju bez koristi.
        when (intent?.action) {
            ACTION_HOLD -> holdWakeLock()
            else -> releaseWakeLock()      // pokretanje i ACTION_RELEASE
        }
        return START_NOT_STICKY   // ako proces ipak umre, nema smisla ponovo dizati servis bez audio engine-a
    }

    private fun holdWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "padsampler:audio").apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    /** Korisnik je zatvorio aplikaciju (swipe iz recents): ugasi servis i wake lock da ne troše bateriju. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_HOLD = "com.example.padsampler.HOLD_CPU"
        const val ACTION_RELEASE = "com.example.padsampler.RELEASE_CPU"
        private const val CHANNEL = "padsampler_audio"
        private const val NOTIF_ID = 1
    }
}
