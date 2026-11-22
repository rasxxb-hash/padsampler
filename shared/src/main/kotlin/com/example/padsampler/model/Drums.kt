package com.example.padsampler.model

/** Drums (Studio, vidi SPECIFIKACIJA "Studio"): 8 zvukova, step sequencer u šesnaestinama, 1-8 taktova. */
object DrumLayout {
    const val LANES = 8
    const val STEPS_PER_BAR = 16
    const val MAX_BARS = 8
    const val MAX_STEPS = STEPS_PER_BAR * MAX_BARS
    /** Na Launchpadu: redovi 7..4 = 32 koraka (2 takta) jedne strane; red 3 prazan. */
    const val LP_STEPS_PER_PAGE = 32
    val BAR_OPTIONS = listOf(1, 2, 4, 8)
    /** Imena za Print to pad (labela pada i folder u izvozu sesije). */
    val CATEGORIES = listOf("Drums", "Beat", "Percussion", "Hats", "Fill", "FX")

    // Launchpad raspored (red 0 dolje)
    const val ROW_SOUNDS = 2
    const val ROW_MUTES = 1
    const val COL_PLAY = 0          // red 0
    const val COL_LENGTH = 1        // red 0, kolone 1..4 = 1/2/4/8 taktova
    const val COL_CLEAR = 7         // red 0
    /** Desna kolona: strane po 2 takta (CC 79 = strana 1 ... CC 49 = strana 4). */
    fun pageCc(page: Int) = (7 - page) * 10 + 9
    fun pageOfCc(cc: Int): Int? = if (cc % 10 == 9 && cc / 10 in 4..7) 7 - cc / 10 else null

    /** Swing 0..100 %: svaka druga šesnaestina kasni, najviše pola šesnaestine (100 % = 12 tickova od 24). */
    const val MAX_SWING = 100
    const val SWING_STEP = 5
    fun swingTick(step: Int, swing: Int, ticksPerStep: Int): Int =
        step * ticksPerStep + if (step % 2 == 1) swing.coerceIn(0, MAX_SWING) * ticksPerStep / 2 / 100 else 0

    /** Najmanja jačina note/udarca (ispod se ne bi čulo, a korak bi izgledao upaljen). */
    const val MIN_VELOCITY = 0.05f

    // ---- IMPORT MIDI: General MIDI bubnjevi -> 8 vrsta zvuka (redom = podrazumijevana traka)
    val GM_KINDS = listOf("Kick", "Snare", "Clap/Rim", "Closed HH", "Open HH", "Tom", "Cymbal", "Perc")

    /** Vrsta zvuka za GM notu (kanal 10). Sve što nije prepoznato ide u Perc. */
    fun gmKind(pitch: Int): Int = when (pitch) {
        35, 36 -> 0
        38, 40 -> 1
        37, 39 -> 2
        42, 44 -> 3
        46 -> 4
        41, 43, 45, 47, 48, 50 -> 5
        49, 51, 52, 53, 55, 57, 59 -> 6
        else -> 7
    }

    /** Riječi u imenu zvuka po vrsti (redom kao GM_KINDS). */
    private val KIND_WORDS = listOf(
        Regex("kick|\\bbd\\b|bass ?drum"),
        Regex("snare|\\bsd\\b|snr"),
        Regex("clap|\\bcp\\b|rim|snap"),
        Regex("hat|\\bhh\\b|chh|\\bch\\b"),
        Regex("open|ohh|\\boh\\b"),
        Regex("tom"),
        Regex("crash|ride|cym|splash|china"),
        Regex("perc|shak|tamb|cow|conga|bongo|clave|block|agogo|cabasa|guiro|maraca|triang|timbal")
    )

    /**
     * Traka za svaku vrstu zvuka: prvo traka čije ime zvuka odgovara vrsti (npr. "Kick 808.wav"), pa podrazumijevana
     * (Kick = 1, Snare = 2, ...), pa prva slobodna. Svaka traka dobije najviše jednu vrstu.
     */
    fun lanesForKinds(names: List<String?>): IntArray {
        val out = IntArray(GM_KINDS.size) { -1 }
        val used = BooleanArray(LANES)
        // otvoreni hi-hat prije zatvorenog ("Open hat" sadrži i "hat")
        for (kind in listOf(0, 1, 2, 4, 3, 5, 6, 7)) {
            val lane = names.indices.firstOrNull { l ->
                !used[l] && names[l]?.lowercase()?.let { KIND_WORDS[kind].containsMatchIn(it) } == true
            } ?: continue
            out[kind] = lane
            used[lane] = true
        }
        for (kind in out.indices) {
            if (out[kind] >= 0) continue
            val lane = if (!used[kind]) kind else used.indexOfFirst { !it }
            out[kind] = lane
            used[lane] = true
        }
        return out
    }
}

/** Zvuk trake: kopija fajla u folderu semplova (privatni folder aplikacije). */
data class DrumSound(
    val name: String,       // ime originalnog fajla (ugrađeni: "808 Kick")
    val fileName: String,   // ime kopije u folderu semplova
    val label: String,      // kratko ime na ekranu
    val gain: Float = 1f,   // 0..1 (fader taper)
    /** Ugrađeni (sintetizovani) zvuk: podešavanja iz kojih je wav izračunat; null = fajl korisnika. */
    val synth: DrumSynthSpec? = null
)

/**
 * Vrste ugrađenih zvukova; akustične (modalna sinteza) i perkusije (perc, takođe akustične) su posebne grupe u dijalogu
 * zvuka. Nove vrste idu na kraj (ime se čuva u sesiji).
 */
enum class DrumSynthKind(val label: String, val acoustic: Boolean = false, val perc: Boolean = false) {
    KICK("Kick"), SNARE("Snare"), CLAP("Clap"), RIM("Rim"), HAT_CLOSED("Closed HH"), HAT_OPEN("Open HH"),
    TOM("Tom"), CYMBAL("Cymbal"), COWBELL("Cowbell"), ZAP("Zap"), SHAKER("Shaker"),
    AC_KICK("Kick", true), AC_SNARE("Snare", true), SIDESTICK("Side Stick", true), AC_HAT_CLOSED("Closed HH", true),
    AC_HAT_OPEN("Open HH", true), AC_TOM("Tom", true), CRASH("Crash", true), RIDE("Ride", true),
    CONGA_LO("Conga Lo", true, true), CONGA_HI("Conga Hi", true, true), BONGO_LO("Bongo Lo", true, true),
    BONGO_HI("Bongo Hi", true, true), TIMBALE("Timbale", true, true), CLAVE("Clave", true, true),
    TAMBOURINE("Tambourine", true, true), AGOGO("Agogo", true, true)
}

/** Podešavanja ugrađenog zvuka, sve 0..1: visina, dužina, boja (tamno-svijetlo), udar/šum, prljavština (lo-fi). */
data class DrumSynthSpec(
    val kind: DrumSynthKind,
    val tune: Float = 0.5f,
    val decay: Float = 0.5f,
    val tone: Float = 0.5f,
    val snap: Float = 0.5f,
    val grit: Float = 0f
)

data class DrumsState(
    val sounds: List<DrumSound?> = List(DrumLayout.LANES) { null },
    /** Upaljeni koraci (šesnaestine, 0..127) po traci. Koraci iza dužine patterna se čuvaju, ali ne sviraju. */
    val steps: List<Set<Int>> = List(DrumLayout.LANES) { emptySet() },
    /** Jačina upaljenih koraka po traci (koraka nema u mapi = 1). */
    val velocities: List<Map<Int, Float>> = List(DrumLayout.LANES) { emptyMap() },
    val mutes: List<Boolean> = List(DrumLayout.LANES) { false },
    /** Swing 0..100 % (DrumLayout.swingTick). */
    val swing: Int = 0,
    val bars: Int = 1,
    val selected: Int = 0,
    /** Takt koji se prikazuje u aplikaciji (0..bars-1). */
    val viewBar: Int = 0,
    /** Strana na Launchpadu (2 takta po strani). */
    val lpPage: Int = 0,
    /** Labela (i folder u izvozu) printa: Drums, Beat... */
    val category: String = "Drums"
) {
    val totalSteps get() = bars * DrumLayout.STEPS_PER_BAR
    val lpPages get() = (bars + 1) / 2
    fun velocity(lane: Int, step: Int): Float = velocities[lane][step] ?: 1f
}
