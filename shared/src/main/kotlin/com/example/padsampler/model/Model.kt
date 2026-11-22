package com.example.padsampler.model

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

enum class Screen { SESSION, MIXER, DRUMS, KEYS }

/** Mod donjeg reda padova (dugme "Stop Solo Mute"): crveno, plavo, žuto, pa ponovo crveno. */
enum class BottomMode {
    STOP, SOLO, MUTE;

    fun next(): BottomMode = values()[(ordinal + 1) % values().size]
}

/** Raspored. Red 0 je DOLJE na Launchpadu (MIDI nota = (red+1)*10 + kolona+1). */
object Layout {
    const val TRACKS = 8
    const val SCENES = 6
    const val SLOTS = TRACKS * SCENES
    const val METRONOME_ROW = 7
    const val FUNCTION_ROW = 0

    /** slot = scena * 8 + track */
    fun slot(scene: Int, track: Int) = scene * TRACKS + track

    /** Redovi 6..1 su scene 0..5 (scena 0 je gore). */
    fun sceneOfRow(row: Int) = 6 - row
    fun rowOfScene(scene: Int) = 6 - scene
}

/** Slajder: v je 0..1, taper dB = -60 * (1 - v)^2. Na Launchpadu 7 koraka (v = korak/7). */
object Fader {
    const val STEPS = 7
    const val DEFAULT = 5f / 7f

    fun db(v: Float): Float = if (v <= 0.001f) Float.NEGATIVE_INFINITY else -60f * (1f - v) * (1f - v)

    fun gain(v: Float): Float = if (v <= 0.001f) 0f else 10.0.pow(db(v) / 20.0).toFloat()

    fun valueForStep(step: Int): Float = step.coerceIn(0, STEPS) / STEPS.toFloat()

    /** Broj upaljenih padova (0..7) za vrijednost slajdera. */
    fun stepFor(v: Float): Int = (v * STEPS + 0.5f).toInt().coerceIn(0, STEPS)

    fun label(v: Float): String {
        val d = db(v)
        if (d == Float.NEGATIVE_INFINITY) return "-inf"
        return if (abs(d) < 0.05f) "0.0" else "%.1f".format(d)
    }

    /** Linearni peak (0..1) u 0..1 za prikaz na metru (-60 dB do 0 dB). */
    fun meter(level: Float): Float = ((20f * log10(maxOf(level, 1e-4f)) + 60f) / 60f).coerceIn(0f, 1f)
}

/**
 * Boja trackova na ekranu i na Launchpadu (indeksi palete). Od 12. 11. 2026 JEDNA "zaštitna" boja: mint (akcent
 * aplikacije), na Launchpadu puna mint 33 (svira) i tamna 34 (miruje). Pastelni red palete izgleda isprano na LED-u.
 * Lista ostaje po kolonama, da se boja jedne kolone može opet promijeniti na jednom mjestu.
 */
class TrackColor(val argb: Long, val lpBright: Int, val lpDim: Int)

/** Mint (= Accent u ui/Theme.kt). */
const val MINT = 0xFF86B9A0
const val LP_MINT = 33
const val LP_MINT_DIM = 34

object TrackColors {
    val all = List(8) { TrackColor(MINT, LP_MINT, LP_MINT_DIM) }
}


/** Snimak stanja audio engine-a za UI i LED-ove (puni se iz NativeAudio.pollState). */
class LiveState(
    val slotStates: ByteArray = ByteArray(Layout.SLOTS),
    val levels: FloatArray = FloatArray(Layout.TRACKS),
    val progress: FloatArray = FloatArray(Layout.TRACKS),
    val barPhase: Float = 0f,
    val masterLevel: Float = 0f,
    /** Studio: NativeAudio.STUDIO_* za Drums i Keys (svaki svoj transport) i korak Drums patterna koji svira (-1 = ne svira). */
    val drumsTransport: Int = 0,
    val keysTransport: Int = 0,
    val drumStep: Int = -1,
    /** Korak Keys patterna koji svira (-1 = ne svira). */
    val keysStep: Int = -1
)

/** Ono što LED-ovi na Launchpadu trebaju znati osim LiveState. */
data class ControlState(
    val screen: Screen = Screen.SESSION,
    val bottomMode: BottomMode = BottomMode.STOP,
    val faders: List<Float> = List(Layout.TRACKS) { Fader.DEFAULT },
    /** Pan po tracku: -1 lijevo .. 0 sredina .. +1 desno. */
    val pans: List<Float> = List(Layout.TRACKS) { 0f },
    /** DJ filter na masteru: -1 low-pass .. 0 isključen .. +1 high-pass. */
    val masterFilter: Float = 0f,
    /** Send kanala u zajednički reverb / delay, 0..1. */
    val revSends: List<Float> = List(Layout.TRACKS) { 0f },
    val dlySends: List<Float> = List(Layout.TRACKS) { 0f },
    /** EQ kanala [track][LOW, MID, HIGH], -1..1 = -12..+12 dB, 0 = ravno. */
    val eqs: List<List<Float>> = List(Layout.TRACKS) { List(3) { 0f } },
    val mutes: List<Boolean> = List(Layout.TRACKS) { false },
    val solos: List<Boolean> = List(Layout.TRACKS) { false },
    val master: Float = 1f,
    val masterMute: Boolean = false
)

/** srcBpm = originalni BPM sempla (iz imena foldera/fajla ili ručno unesen). */
/** name = ime fajla, label = tekst na padu (ime foldera u kojem je sempl, npr. Drums, Bass, Vocal). */
data class PadConfig(
    val slot: Int,
    val name: String,
    val fileName: String,
    val loop: Boolean,
    val srcBpm: Double,
    val label: String = name.substringBeforeLast('.'),
    val key: String? = null,     // tonalitet iz imena (samo prikaz), npr. "Am"
    /** Pad nastao iz Drums (Print to pad): pattern i kit, da se može ponovo otvoriti i izmijeniti. */
    val studio: DrumsState? = null,
    /** Pad nastao iz Keys (Print to pad): zvuk sintisajzera i piano roll. */
    val keys: KeysState? = null
)

/** Sve što se sprema između pokretanja. */
data class SessionData(
    val name: String = "",
    val bpm: Int = 120,
    val pads: List<PadConfig> = emptyList(),
    val faders: List<Float> = List(Layout.TRACKS) { Fader.DEFAULT },
    /** Pan po tracku: -1 lijevo .. 0 sredina .. +1 desno. */
    val pans: List<Float> = List(Layout.TRACKS) { 0f },
    /** DJ filter na masteru: -1 low-pass .. 0 isključen .. +1 high-pass. */
    val masterFilter: Float = 0f,
    /** Send kanala u zajednički reverb / delay, 0..1. */
    val revSends: List<Float> = List(Layout.TRACKS) { 0f },
    val dlySends: List<Float> = List(Layout.TRACKS) { 0f },
    /** EQ kanala [track][LOW, MID, HIGH], -1..1. */
    val eqs: List<List<Float>> = List(Layout.TRACKS) { List(3) { 0f } },
    val mutes: List<Boolean> = List(Layout.TRACKS) { false },
    val master: Float = 1f,
    /** Drums (kit + pattern) sesije. Fajlovi zvukova su u istom folderu kao semplovi (samples/). */
    val drums: DrumsState? = null,
    /** Keys (sintisajzer + piano roll) sesije. */
    val keys: KeysState? = null
) {
    /**
     * Podfolder svakog fajla u izvozu (.padsession): sempl pada ide u folder svoje labele (Drums, Bass, Vocal...),
     * zvukovi Drums kitova u "Drums/Kit". U aplikaciji su svi fajlovi u jednom folderu (samples/).
     */
    fun exportFolders(): Map<String, String> {
        val out = HashMap<String, String>()
        fun kit(s: DrumsState?) = s?.sounds?.forEach { snd -> if (snd != null) out.putIfAbsent(snd.fileName, "Drums/Kit") }
        for (p in pads) out[p.fileName] = safeFolder(p.label)
        kit(drums)
        for (p in pads) kit(p.studio)
        return out
    }

    /** Svi fajlovi u samples/ koje sesija koristi (semplovi padova, zvukovi Drums i kitovi printanih padova). */
    fun referencedFiles(): Set<String> {
        val out = HashSet<String>()
        for (p in pads) {
            out += p.fileName
            p.studio?.sounds?.forEach { s -> if (s != null) out += s.fileName }
        }
        drums?.sounds?.forEach { s -> if (s != null) out += s.fileName }
        return out
    }
}

/** Ime foldera bez znakova koje Windows/Android ne dozvoljavaju. Prazno -> "Samples". */
fun safeFolder(label: String): String =
    label.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.').take(40).ifEmpty { "Samples" }
