package com.example.padsampler

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Snima neuhvaćene greške u fajl, pa ih MainActivity pri sljedećem pokretanju prikaže na ekranu
 * (da se uzrok pada može pročitati bez Logcata).
 */
object CrashReporter {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val f = file(ctx)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                f.writeText("Thread: ${thread.name}\n" + Log.getStackTraceString(error))
            } catch (_: Exception) {}
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() }?.readText()
    } catch (_: Exception) { null }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }
}
