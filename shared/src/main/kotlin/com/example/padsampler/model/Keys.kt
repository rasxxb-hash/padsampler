package com.example.padsampler.model

import kotlin.math.ln
import kotlin.math.exp

/** Keys (Studio): Minimoog-sintisajzer + piano roll u šesnaestinama, 1-8 taktova. Vidi SPECIFIKACIJA "Studio". */

/** Parametar sintisajzera. id = redni broj u SynthParam (synth.h); redoslijed MORA biti isti. */
class SynthParamDef(
    val id: Int,
    val label: String,
    val min: Float,
    val max: Float,
    val def: Float,
    val kind: Kind,
    /** CHOICE: natpisi vrijednosti 0, 1, 2... */
    val choices: List<String> = emptyList()
) {
    enum class Kind { LINEAR, LOG, INT, WAVE, SECONDS, PERCENT, HZ, CHOICE }

    /** Vrijednost -> položaj potenciometra 0..1 (logaritamski za Hz i vrijeme). */
    fun toKnob(v: Float): Float = when (kind) {
        Kind.LOG, Kind.HZ, Kind.SECONDS -> ((ln(v.coerceIn(min, max)) - ln(min)) / (ln(max) - ln(min)))
        else -> (v - min) / (max - min)
    }.coerceIn(0f, 1f)

    fun fromKnob(k: Float): Float {
        val x = k.coerceIn(0f, 1f)
        return when (kind) {
            Kind.LOG, Kind.HZ, Kind.SECONDS -> exp(ln(min) + x * (ln(max) - ln(min)))
            Kind.INT, Kind.WAVE, Kind.CHOICE -> Math.round(min + x * (max - min)).toFloat()
            else -> min + x * (max - min)
        }
    }

    fun format(v: Float): String = when (kind) {
        Kind.HZ -> if (v >= 1000f) "%.1f k".format(v / 1000f) else "%.0f".format(v)
        Kind.SECONDS -> if (v < 1f) "%.0f ms".format(v * 1000f) else "%.2f s".format(v)
        Kind.PERCENT -> "%.0f".format(v * 100f)
        Kind.INT -> when (id) {
            SynthParams.VOICES -> if (v < 1.5f) "mono" else "%.0f".format(v)
            SynthParams.ARP_OCT -> "%d oct".format(Math.round(v))
            else -> "%+d".format(Math.round(v))
        }
        Kind.CHOICE -> choices.getOrElse(Math.round(v - min)) { "?" }
        Kind.WAVE -> SynthParams.WAVES.getOrElse(Math.round(v)) { "?" }
        Kind.LINEAR -> if (id == SynthParams.GLIDE) (if (v <= 0.0005f) "off" else "%.0f ms".format(v * 1000f)) else "%+.2f".format(v)
        Kind.LOG -> "%.2f".format(v)
    }
}

object SynthParams {
    const val OSC1_WAVE = 0; const val OSC1_OCT = 1; const val OSC1_LEVEL = 2
    const val OSC2_WAVE = 3; const val OSC2_OCT = 4; const val OSC2_DETUNE = 5; const val OSC2_LEVEL = 6
    const val OSC3_WAVE = 7; const val OSC3_OCT = 8; const val OSC3_DETUNE = 9; const val OSC3_LEVEL = 10
    const val NOISE = 11
    const val CUTOFF = 12; const val RESONANCE = 13; const val FILTER_ENV = 14; const val KEY_TRACK = 15
    const val F_ATTACK = 16; const val F_DECAY = 17; const val F_SUSTAIN = 18; const val F_RELEASE = 19
    const val A_ATTACK = 20; const val A_DECAY = 21; const val A_SUSTAIN = 22; const val A_RELEASE = 23
    const val GLIDE = 24; const val VOICES = 25; const val VOLUME = 26
    const val ARP_ON = 27; const val ARP_MODE = 28; const val ARP_RATE = 29; const val ARP_OCT = 30
    const val ARP_GATE = 31; const val ARP_HOLD = 32
    const val FX_DRIVE = 33; const val FX_CHORUS = 34; const val FX_DELAY_TIME = 35; const val FX_DELAY_FB = 36
    const val FX_DELAY_MIX = 37; const val FX_REVERB_SIZE = 38; const val FX_REVERB_MIX = 39
    /** Izraz (22. 11. 2026): vibrato sa zakašnjenjem i jačina udarca -> boja (filter). Dio zvuka (preset). */
    const val VIBRATO = 40; const val VEL_TONE = 41
    const val COUNT = 42

    /** Arp i efekti ostaju kad se učita preset (preset = zvuk sintisajzera; arp i efekti = postavka sviranja). */
    val KEEP_ON_PRESET = ARP_ON..FX_REVERB_MIX

    val WAVES = listOf("TRI", "SAW", "SQR", "PULSE", "NARROW")

    val all: List<SynthParamDef> = listOf(
        SynthParamDef(OSC1_WAVE, "WAVE", 0f, 4f, 1f, SynthParamDef.Kind.WAVE),
        SynthParamDef(OSC1_OCT, "OCT", -2f, 2f, 0f, SynthParamDef.Kind.INT),
        SynthParamDef(OSC1_LEVEL, "OSC 1", 0f, 1f, 1f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(OSC2_WAVE, "WAVE", 0f, 4f, 1f, SynthParamDef.Kind.WAVE),
        SynthParamDef(OSC2_OCT, "OCT", -2f, 2f, 0f, SynthParamDef.Kind.INT),
        SynthParamDef(OSC2_DETUNE, "DETUNE", -12f, 12f, 0.07f, SynthParamDef.Kind.LINEAR),
        SynthParamDef(OSC2_LEVEL, "OSC 2", 0f, 1f, 0.8f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(OSC3_WAVE, "WAVE", 0f, 4f, 2f, SynthParamDef.Kind.WAVE),
        SynthParamDef(OSC3_OCT, "OCT", -2f, 2f, -1f, SynthParamDef.Kind.INT),
        SynthParamDef(OSC3_DETUNE, "DETUNE", -12f, 12f, -0.05f, SynthParamDef.Kind.LINEAR),
        SynthParamDef(OSC3_LEVEL, "OSC 3", 0f, 1f, 0f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(NOISE, "NOISE", 0f, 1f, 0f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(CUTOFF, "CUTOFF", 20f, 18000f, 1200f, SynthParamDef.Kind.HZ),
        SynthParamDef(RESONANCE, "EMPHASIS", 0f, 1f, 0.3f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(FILTER_ENV, "CONTOUR", 0f, 1f, 0.4f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(KEY_TRACK, "KEY TRK", 0f, 1f, 0.5f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(F_ATTACK, "ATTACK", 0.001f, 10f, 0.005f, SynthParamDef.Kind.SECONDS),
        SynthParamDef(F_DECAY, "DECAY", 0.001f, 10f, 0.4f, SynthParamDef.Kind.SECONDS),
        SynthParamDef(F_SUSTAIN, "SUSTAIN", 0f, 1f, 0.3f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(F_RELEASE, "RELEASE", 0.001f, 10f, 0.25f, SynthParamDef.Kind.SECONDS),
        SynthParamDef(A_ATTACK, "ATTACK", 0.001f, 10f, 0.003f, SynthParamDef.Kind.SECONDS),
        SynthParamDef(A_DECAY, "DECAY", 0.001f, 10f, 0.3f, SynthParamDef.Kind.SECONDS),
        SynthParamDef(A_SUSTAIN, "SUSTAIN", 0f, 1f, 0.8f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(A_RELEASE, "RELEASE", 0.001f, 10f, 0.2f, SynthParamDef.Kind.SECONDS),
        SynthParamDef(GLIDE, "GLIDE", 0f, 2f, 0f, SynthParamDef.Kind.LINEAR),
        SynthParamDef(VOICES, "VOICES", 1f, 8f, 1f, SynthParamDef.Kind.INT),
        SynthParamDef(VOLUME, "VOLUME", 0f, 1f, 0.7f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(ARP_ON, "ARP", 0f, 1f, 0f, SynthParamDef.Kind.CHOICE, listOf("OFF", "ON")),
        SynthParamDef(ARP_MODE, "MODE", 0f, 4f, 0f, SynthParamDef.Kind.CHOICE, listOf("UP", "DOWN", "UP/DN", "RANDOM", "PLAYED")),
        SynthParamDef(ARP_RATE, "RATE", 0f, 5f, 3f, SynthParamDef.Kind.CHOICE, listOf("1/4", "1/8", "1/8T", "1/16", "1/16T", "1/32")),
        SynthParamDef(ARP_OCT, "OCTAVES", 1f, 4f, 1f, SynthParamDef.Kind.INT),
        SynthParamDef(ARP_GATE, "GATE", 0.05f, 1f, 0.5f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(ARP_HOLD, "HOLD", 0f, 1f, 0f, SynthParamDef.Kind.CHOICE, listOf("OFF", "ON")),
        SynthParamDef(FX_DRIVE, "DRIVE", 0f, 1f, 0f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(FX_CHORUS, "CHORUS", 0f, 1f, 0f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(FX_DELAY_TIME, "TIME", 0f, 5f, 2f, SynthParamDef.Kind.CHOICE, listOf("1/16", "1/8", "1/8.", "1/4", "1/4.", "1/2")),
        SynthParamDef(FX_DELAY_FB, "FEEDBACK", 0f, 0.9f, 0.35f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(FX_DELAY_MIX, "MIX", 0f, 1f, 0f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(FX_REVERB_SIZE, "SIZE", 0f, 1f, 0.5f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(FX_REVERB_MIX, "MIX", 0f, 1f, 0f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(VIBRATO, "VIBRATO", 0f, 1f, 0f, SynthParamDef.Kind.PERCENT),
        SynthParamDef(VEL_TONE, "VEL>TONE", 0f, 1f, 0f, SynthParamDef.Kind.PERCENT)
    )

    fun defaults(): List<Float> = all.map { it.def }
}

enum class Scale(val label: String, val steps: IntArray) {
    MAJOR("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("Minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    DORIAN("Dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN("Phrygian", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    MIXOLYDIAN("Mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    HARMONIC_MINOR("Harm. minor", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    PENTA_MAJOR("Penta major", intArrayOf(0, 2, 4, 7, 9)),
    PENTA_MINOR("Penta minor", intArrayOf(0, 3, 5, 7, 10)),
    BLUES("Blues", intArrayOf(0, 3, 5, 6, 7, 10)),
    CHROMATIC("Chromatic", IntArray(12) { it });

    fun contains(note: Int, root: Int) = ((note - root) % 12 + 12) % 12 in steps
}

object KeysLayout {
    val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    /** Ime note kao u Abletonu (C3 = MIDI 60). */
    fun noteName(n: Int) = NOTE_NAMES[((n % 12) + 12) % 12] + (n / 12 - 2)
    fun isBlack(n: Int) = (((n % 12) + 12) % 12) in intArrayOf(1, 3, 6, 8, 10)

    const val MIN_OCTAVE = -1
    const val MAX_OCTAVE = 6
    /** Dužine nota za unos (u koracima-šesnaestinama): 1/16, 1/8, 1/4, 1/2, 1 takt. */
    val NOTE_LENGTHS = listOf(1, 2, 4, 8, 16)
    /** Imena za Print to pad (labela pada i folder u izvozu sesije). */
    val CATEGORIES = listOf("Bass", "Chords", "Melodic", "Lead", "Keys", "Pads", "Arp", "Brass", "Synth", "FX")

    // Launchpad: redovi 1..7 klavijatura u skali, red 0: Play, oktava -, oktava +
    const val COL_PLAY = 0
    const val COL_OCT_DOWN = 1
    const val COL_OCT_UP = 2
    /** Red 0, kolona 7: prebacuje PLAY / STEP mod. */
    const val COL_MODE = 7
    /** STEP mod: redovi 7..4 = 32 koraka izabrane note, redovi 3..1 = klavijatura. */
    const val STEP_KEY_ROWS = 3

    /** Najniža nota prikaza (aplikacija i Launchpad): tonika u izabranoj oktavi (Ableton imena). */
    fun baseNote(s: KeysState) = (12 * (s.octave + 2) + s.root).coerceIn(0, 127)

    /**
     * Nota pada na Launchpadu (red 1..7, kolona 0..7): u skali, svaki red je 3 stepena skale više (kvarta,
     * kao Launchpad/Push "in key"); hromatski: red = 5 polutonova. null = van opsega.
     */
    fun lpNote(s: KeysState, row: Int, col: Int): Int? {
        if (row !in 1..7 || col !in 0..7) return null
        val st = s.scale.steps
        val rowOffset = if (s.scale == Scale.CHROMATIC) 5 else 3
        val degree = (row - 1) * rowOffset + col
        val n = baseNote(s) + 12 * (degree / st.size) + st[degree % st.size]
        return n.takeIf { it in 0..127 }
    }

    /** Note koje piano roll prikazuje (od najviše): dvije oktave od tonike, samo note skale. */
    fun rollNotes(s: KeysState): List<Int> {
        val base = baseNote(s)
        return (base..(base + 24).coerceAtMost(127)).filter { s.scale.contains(it, s.root) }.reversed()
    }

    /**
     * Pomjera izabrane note za [dStep] koraka i [dRow] redova piano rolla ([rows] = visine redova, odozgo).
     * Pomak se ograniči da sve note ostanu u patternu i u prikazanim redovima; nota van prikaza zadrži visinu.
     * Neizabrana nota iste visine na istom koraku kao pomjerena se briše. Vraća (sve note, pomjerene note).
     */
    fun moveNotes(
        notes: List<KeyNote>, sel: Set<KeyNote>, dStep: Int, dRow: Int, rows: List<Int>, totalSteps: Int
    ): Pair<List<KeyNote>, Set<KeyNote>> {
        val picked = notes.filter { it in sel }
        if (picked.isEmpty() || (dStep == 0 && dRow == 0)) return notes to picked.toSet()
        val ds = dStep.coerceIn(-picked.minOf { it.step }, totalSteps - 1 - picked.maxOf { it.step })
        val idx = picked.map { rows.indexOf(it.pitch) }.filter { it >= 0 }
        val dr = if (idx.isEmpty()) 0 else dRow.coerceIn(-idx.min(), rows.size - 1 - idx.max())
        val moved = picked.map { n ->
            val i = rows.indexOf(n.pitch)
            n.copy(step = n.step + ds, pitch = if (i >= 0) rows[i + dr] else n.pitch)
        }
        val taken = moved.map { it.pitch.toLong() * 1000 + it.step }.toHashSet()
        val rest = notes.filter { it !in sel && (it.pitch.toLong() * 1000 + it.step) !in taken }
        return (rest + moved) to moved.toSet()
    }
}

/** Nota u piano rollu: početak i dužina u koracima (šesnaestine), visina = MIDI nota. */
data class KeyNote(val step: Int, val length: Int, val pitch: Int, val velocity: Float = 1f)

data class KeysState(
    val params: List<Float> = SynthParams.defaults(),
    val presetName: String = "Init",
    val notes: List<KeyNote> = emptyList(),
    val bars: Int = 1,
    val viewBar: Int = 0,
    val root: Int = 0,               // 0 = C
    val scale: Scale = Scale.MINOR,
    val octave: Int = 1,             // prikaz od C1 (Ableton)
    val noteLength: Int = 2,         // dužina nove note u koracima
    val category: String = "Bass",   // labela (i folder u izvozu) printa
    val swing: Int = 0,              // 0..100 % (DrumLayout.swingTick)
    // Launchpad (ne sprema se): STEP mod (koraci izabrane note), izabrana nota (-1 = tonika), strana koraka
    val lpStep: Boolean = false,
    val selectedNote: Int = -1,
    val lpPage: Int = 0,
    // Panel potenciometara otvoren (ne sprema se; mijenja se i strelicom dolje na Launchpadu)
    val panelOpen: Boolean = true,
    // Snimanje uživo (ne sprema se): REC_* i poravnavanje snimljenih nota (u koracima: 1 = 1/16, 2 = 1/8)
    val rec: Int = REC_OFF,
    val recQuantize: Int = 1
) {
    companion object {
        const val REC_OFF = 0
        const val REC_COUNT_IN = 1
        const val REC_ON = 2
    }

    val totalSteps get() = bars * DrumLayout.STEPS_PER_BAR
    val lpPages get() = (bars + 1) / 2
    /** Nota čiji se koraci programiraju na Launchpadu (STEP mod). */
    val stepNote get() = if (selectedNote in 0..127) selectedNote else KeysLayout.baseNote(this)
}
