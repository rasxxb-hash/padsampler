package com.example.padsampler.desktop

/** Izlazni uređaj (stereo) za jedan audio API. */
class AudioDevice(val name: String, val isDefault: Boolean, val rates: List<Int>)

/**
 * Samo računar: podešavanja zvuka i "ne gasi ekran". C++ strana: desktop/src/desktop_jni.cpp (u padsampler.dll).
 * API imena su RtAudio imena: "wasapi", "asio", "ds" (DirectSound), "null" (bez zvuka).
 */
object DesktopAudio {
    init {
        if (System.getProperty("padsampler.preloaded") == null) System.loadLibrary("padsampler")
    }

    /** Postavke za sljedeće pokretanje motora (prije NativeAudio.start()). Prazno / 0 = podrazumijevano. */
    external fun setConfig(api: String, device: String, buffer: Int, rate: Int)

    /** Zaustavi zvuk i pokreni sa novim postavkama. Ako ne uspije, vrate se prethodne; tada vraća false. */
    external fun apply(api: String, device: String, buffer: Int, rate: Int): Boolean

    external fun keepAwake(on: Boolean)
    external fun latencyMs(): Double
    /** Baterija laptopa: 0..100 (+1000 = na punjaču); -1 = nema baterije ili nepoznato. */
    external fun battery(): Int

    private external fun apis(): Array<String>
    private external fun devices(api: String): Array<String>
    private external fun current(): String

    fun availableApis(): List<String> = apis().toList()

    /** Može trajati (ASIO učitava drajvere): zvati iz pozadinske niti. */
    fun outputDevices(api: String): List<AudioDevice> = devices(api).mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 3) null
        else AudioDevice(p[0], p[1] == "1", p[2].split(',').mapNotNull { it.toIntOrNull() })
    }

    /** (api, ime uređaja) koji trenutno sviraju; prazno ako zvuk ne radi. */
    fun currentOutput(): Pair<String, String> {
        val p = current().split('\t')
        return p.getOrElse(0) { "" } to p.getOrElse(1) { "" }
    }

    /** Napomena motora, npr. "ASIO stopped, switched to the default output"; prazno ako je sve u redu. */
    fun notice(): String = current().split('\t').getOrElse(2) { "" }

    fun apiLabel(api: String): String = when (api) {
        "wasapi" -> "WASAPI"
        "asio" -> "ASIO"
        "ds" -> "DirectSound"
        "null" -> "No audio"
        else -> api.uppercase()
    }
}

/** Ključevi u settings.properties (%APPDATA%\PadSampler). */
object DesktopSettings {
    const val AUDIO_API = "audio_api"
    const val AUDIO_DEVICE = "audio_device"
    const val AUDIO_BUFFER = "audio_buffer"
    const val AUDIO_RATE = "audio_rate"
    const val MIDI_DEVICE = "midi_device"
    const val FULLSCREEN = "fullscreen"

    /** Sačuvane audio postavke idu u motor prije prvog pokretanja zvuka. */
    fun applySavedAudio(p: DesktopPlatform) {
        DesktopAudio.setConfig(
            p.getString(AUDIO_API), p.getString(AUDIO_DEVICE),
            p.getInt(AUDIO_BUFFER, 0), p.getInt(AUDIO_RATE, 0)
        )
    }
}
