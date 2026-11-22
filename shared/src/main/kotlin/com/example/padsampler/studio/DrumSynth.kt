package com.example.padsampler.studio

import com.example.padsampler.model.DrumSynthKind
import com.example.padsampler.model.DrumSynthKind.*
import com.example.padsampler.model.DrumSynthSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Ugrađeni bubnjevi: zvuk se izračuna (offline, ne na audio niti) u kratak sempl, koji Drums onda svira kao svaki
 * drugi zvuk trake. Analogni stil (808/909): sinus sa padom visine, filtrirani šum, metalni zvuk od šest kvadratnih
 * oscilatora. Šum ima fiksno sjeme, pa ista podešavanja uvijek daju isti zvuk.
 */
object DrumSynth {
    const val SAMPLE_RATE = 48000
    private const val MAX_SECONDS = 3.0

    /** Mono sempl; vrh do 0.9 (manje za tiše vrste, vidi level). */
    fun render(spec: DrumSynthSpec, sr: Int = SAMPLE_RATE): FloatArray {
        val v = Voice(spec, sr)
        val roomTail = if (spec.kind.acoustic) ROOM_TAIL else 0.0
        val len = ((v.length() + roomTail) * sr).toInt().coerceIn(sr / 50, (MAX_SECONDS * sr).toInt())
        val out = FloatArray(len) { v.next(it / sr.toDouble()) }
        if (spec.kind.acoustic) room(out, roomAmount(spec.kind), sr)
        grit(out, spec.grit, sr)
        // vrh po vrsti (hi-hat i činela tiši od kicka, kao u pravom kitu); kratak fade na kraju (bez klika)
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val g = 0.9f * level(spec.kind) / peak
        val fade = (sr * 0.004).toInt().coerceAtMost(len)
        for (i in out.indices) {
            val f = if (i >= len - fade) (len - i).toFloat() / fade else 1f
            out[i] *= g * f
        }
        return out
    }

    /** Odnos jačina u kitu (1 = kick). */
    private fun level(k: DrumSynthKind): Float = when (k) {
        KICK -> 1f; SNARE -> 0.85f; CLAP -> 0.8f; RIM -> 0.65f; HAT_CLOSED -> 0.5f; HAT_OPEN -> 0.5f
        TOM -> 0.8f; CYMBAL -> 0.45f; COWBELL -> 0.55f; ZAP -> 0.7f; SHAKER -> 0.5f
        AC_KICK -> 1f; AC_SNARE -> 0.85f; SIDESTICK -> 0.6f; AC_HAT_CLOSED -> 0.45f; AC_HAT_OPEN -> 0.45f
        AC_TOM -> 0.8f; CRASH -> 0.45f; RIDE -> 0.4f
        CONGA_LO -> 0.85f; CONGA_HI -> 0.8f; BONGO_LO -> 0.75f; BONGO_HI -> 0.7f; TIMBALE -> 0.7f
        CLAVE -> 0.55f; TAMBOURINE -> 0.45f; AGOGO -> 0.5f
    }

    // ---------------------------------------------------------------- soba (akustični zvukovi)

    private const val ROOM_TAIL = 0.09

    /** Koliko sobe: bubnjevi više, činele manje (one su ionako duge). */
    private fun roomAmount(k: DrumSynthKind): Float = when (k) {
        AC_HAT_CLOSED, AC_HAT_OPEN, CRASH, RIDE, TAMBOURINE -> 0.18f
        else -> 0.35f
    }

    /**
     * Rane refleksije male prostorije: nekoliko zakašnjelih, prigušenih kopija (zidovi, plafon). Bez dugog repa,
     * da loop ostane čist, ali bubanj više ne zvuči "suho" kao iz kutije.
     */
    private fun room(x: FloatArray, amount: Float, sr: Int) {
        val lp = OnePole(sr)
        val dark = FloatArray(x.size) { lp.lp(x[it].toDouble(), 4500.0).toFloat() }
        val taps = listOf(0.011 to 0.30, 0.017 to 0.24, 0.023 to 0.20, 0.031 to 0.15, 0.041 to 0.11, 0.053 to 0.08, 0.067 to 0.06)
        for (i in x.indices.reversed()) {
            var add = 0.0
            for ((d, g) in taps) {
                val j = i - (d * sr).toInt()
                if (j >= 0) add += dark[j] * g
            }
            x[i] += (add * amount).toFloat()
        }
    }

    /** Stereo (interleaved) za WavWriter. */
    fun renderStereo(spec: DrumSynthSpec, sr: Int = SAMPLE_RATE): FloatArray {
        val m = render(spec, sr)
        return FloatArray(m.size * 2) { m[it / 2] }
    }

    // ---------------------------------------------------------------- glas

    private class Voice(val s: DrumSynthSpec, val sr: Int) {
        private val rnd = java.util.Random(0x5EED + s.kind.ordinal.toLong())
        private var phase = 0.0
        private var phase2 = 0.0
        private val metal = DoubleArray(6)
        private val lp1 = OnePole(sr)
        private val lp2 = OnePole(sr)
        private val hp1 = OnePole(sr)
        private val hp2 = OnePole(sr)
        private val bp = Svf(sr)

        private fun noise() = rnd.nextDouble() * 2 - 1
        /** -60 dB poslije T sekundi. */
        private fun env(t: Double, T: Double) = exp(-6.9 * t / T.coerceAtLeast(0.002))
        private fun lerp(a: Double, b: Double, x: Float) = a + (b - a) * x

        // 808 metalni zvuk: šest kvadratnih oscilatora (frekvencije iz originalnog kola), visina pomjerena tune-om
        private val metalHz = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)
        private fun metallic(scale: Double): Double {
            var sum = 0.0
            for (i in metal.indices) {
                metal[i] += metalHz[i] * scale / sr
                if (metal[i] >= 1.0) metal[i] -= 1.0
                sum += if (metal[i] < 0.5) 1.0 else -1.0
            }
            return sum / 6.0
        }

        /** Dužina zvuka u sekundama (dok ovojnica ne padne ispod -60 dB). */
        fun length(): Double = when (s.kind) {
            KICK -> lerp(0.15, 1.4, s.decay)
            SNARE -> lerp(0.12, 0.55, s.decay)
            CLAP -> 0.03 + lerp(0.1, 0.5, s.decay)
            RIM -> lerp(0.03, 0.12, s.decay)
            HAT_CLOSED -> lerp(0.03, 0.15, s.decay)
            HAT_OPEN -> lerp(0.25, 1.0, s.decay)
            TOM -> lerp(0.15, 0.8, s.decay)
            CYMBAL -> lerp(0.6, 2.8, s.decay)
            COWBELL -> lerp(0.12, 0.6, s.decay)
            ZAP -> lerp(0.08, 0.45, s.decay)
            SHAKER -> lerp(0.06, 0.3, s.decay)
            AC_KICK -> lerp(0.25, 0.9, s.decay)
            AC_SNARE -> lerp(0.15, 0.6, s.decay)
            SIDESTICK -> lerp(0.04, 0.14, s.decay)
            AC_HAT_CLOSED -> lerp(0.05, 0.2, s.decay)
            AC_HAT_OPEN -> lerp(0.5, 1.6, s.decay)
            AC_TOM -> lerp(0.3, 1.2, s.decay)
            CRASH -> lerp(1.4, 2.8, s.decay)
            RIDE -> lerp(1.5, 2.8, s.decay)
            CONGA_LO -> lerp(0.35, 1.1, s.decay)
            CONGA_HI -> lerp(0.25, 0.9, s.decay)
            BONGO_LO -> lerp(0.15, 0.55, s.decay)
            BONGO_HI -> lerp(0.1, 0.45, s.decay)
            TIMBALE -> lerp(0.3, 1.4, s.decay)
            CLAVE -> lerp(0.05, 0.3, s.decay)
            TAMBOURINE -> lerp(0.15, 0.7, s.decay)
            AGOGO -> lerp(0.3, 1.2, s.decay)
        }

        // ---- modalna sinteza (akustični): parcijali sa svojom frekvencijom, jačinom i dužinom
        private val mF = DoubleArray(56)
        private val mA = DoubleArray(56)
        private val mT = DoubleArray(56)
        private val mP = DoubleArray(56)
        private var nModes = 0

        private fun addMode(f: Double, a: Double, t: Double, randomPhase: Boolean = false) {
            if (nModes >= mF.size || f >= sr * 0.45) return
            mF[nModes] = f; mA[nModes] = a; mT[nModes] = t
            mP[nModes] = if (randomPhase) rnd.nextDouble() * 2 * PI else 0.0
            nModes++
        }

        /** Zbir parcijala; bend = privremeno više (udarac zategne kožu, pa visina padne). */
        private fun modes(t: Double, bend: Double = 1.0): Double {
            var sum = 0.0
            for (i in 0 until nModes) {
                mP[i] += 2 * PI * mF[i] * bend / sr
                sum += sin(mP[i]) * mA[i] * env(t, mT[i])
            }
            return sum
        }

        /** Kružna membrana (Besselove nule): odnosi frekvencija tonova kože bubnja. */
        private val membrane = doubleArrayOf(1.0, 1.594, 2.136, 2.296, 2.653, 2.918, 3.156, 3.501)

        /** Koža bubnja: n tonova; viši tonovi kraći; tone = jači viši tonovi (udarac bliže ivici, tvrđa palica). */
        private fun drumHead(f0: Double, n: Int, T: Double, amps: DoubleArray) {
            val tilt = lerp(0.5, 1.6, s.tone)
            for (i in 0 until n) {
                val a = amps[i] * if (i == 0) 1.0 else tilt
                addMode(f0 * membrane[i], a, T / (1 + i * 0.6))
            }
        }

        /** Činela: mnogo neharmoničnih tonova (log raspodjela lo..hi), slučajne jačine i dužine, nagib boje po tone. */
        private fun cymbal(count: Int, lo: Double, hi: Double, T: Double, minDecay: Double) {
            val scale = lerp(0.8, 1.25, s.tune)
            val tilt = (s.tone - 0.5) * 2.0
            for (i in 0 until count) {
                val f = lo * (hi / lo).pow(rnd.nextDouble()) * scale
                val a = (0.3 + 0.7 * rnd.nextDouble()) * (f / 5000.0).pow(tilt)
                addMode(f, a, T * (minDecay + (1 - minDecay) * rnd.nextDouble()), randomPhase = true)
            }
        }

        init {
            val T = length()
            when (s.kind) {
                AC_KICK -> drumHead(lerp(42.0, 72.0, s.tune), 5, T, doubleArrayOf(1.0, 0.25, 0.12, 0.08, 0.05))
                AC_SNARE -> drumHead(lerp(170.0, 280.0, s.tune), 8, T * 0.4, doubleArrayOf(1.0, 0.6, 0.5, 0.4, 0.3, 0.25, 0.2, 0.15))
                AC_TOM -> drumHead(lerp(75.0, 240.0, s.tune), 6, T, doubleArrayOf(1.0, 0.45, 0.3, 0.2, 0.15, 0.1))
                AC_HAT_CLOSED, AC_HAT_OPEN -> cymbal(36, 2500.0, 13000.0, T, 0.5)
                CRASH -> cymbal(48, 400.0, 12000.0, T, 0.4)
                RIDE -> {
                    cymbal(40, 350.0, 10000.0, T, 0.3)
                    // zvono činele (jasni tonovi), jače sa snap
                    val sc = lerp(0.8, 1.25, s.tune)
                    for ((f, a) in listOf(640.0 to 1.0, 960.0 to 0.7, 1380.0 to 0.5, 1910.0 to 0.35)) {
                        addMode(f * sc, a * lerp(0.2, 2.0, s.snap), T * 0.6)
                    }
                }
                // ---- perkusije: koža ruke (conga, bongo) je skoro "štimovana": osnovni ton jak, viši tonovi slabi
                CONGA_LO -> drumHead(lerp(150.0, 240.0, s.tune), 5, T, doubleArrayOf(1.0, 0.3, 0.22, 0.12, 0.08))
                CONGA_HI -> drumHead(lerp(240.0, 360.0, s.tune), 5, T, doubleArrayOf(1.0, 0.32, 0.25, 0.14, 0.1))
                BONGO_LO -> drumHead(lerp(300.0, 460.0, s.tune), 5, T, doubleArrayOf(1.0, 0.4, 0.3, 0.18, 0.12))
                BONGO_HI -> drumHead(lerp(460.0, 720.0, s.tune), 5, T, doubleArrayOf(1.0, 0.42, 0.3, 0.2, 0.12))
                TIMBALE -> {
                    drumHead(lerp(330.0, 620.0, s.tune), 4, T * 0.6, doubleArrayOf(1.0, 0.4, 0.3, 0.2))
                    // metalni obod: neharmonični tonovi koji dugo zvone (jače sa snap = udarac u obod)
                    val sc = lerp(0.85, 1.2, s.tune)
                    for ((f, a) in listOf(1730.0 to 0.5, 2790.0 to 0.35, 3960.0 to 0.25, 5410.0 to 0.15)) {
                        addMode(f * sc, a * lerp(0.4, 1.6, s.snap), T)
                    }
                }
                CLAVE -> {
                    val f = lerp(2000.0, 3200.0, s.tune)
                    addMode(f, 1.0, T)
                    addMode(f * 2.76, 0.12 * lerp(0.5, 2.0, s.tone), T * 0.4)
                }
                TAMBOURINE -> cymbal(32, 4500.0, 13000.0, T, 0.3)
                AGOGO -> {
                    // zvono: neharmonični tonovi, osnovni najduži
                    val f = lerp(650.0, 1150.0, s.tune)
                    val tilt = lerp(0.5, 1.5, s.tone)
                    for ((r, a) in listOf(1.0 to 1.0, 2.42 to 0.45, 3.86 to 0.3, 5.21 to 0.18)) {
                        addMode(f * r, a * if (r == 1.0) 1.0 else tilt, T / r.pow(0.4))
                    }
                }
                else -> {}
            }
        }

        fun next(t: Double): Float {
            val T = length()
            val y = when (s.kind) {
                KICK -> {
                    val f0 = lerp(35.0, 85.0, s.tune)
                    val amount = lerp(1.5, 7.0, s.snap)                 // pad visine (udar)
                    val f = f0 * (1 + amount * exp(-t / 0.035))
                    phase += 2 * PI * f / sr
                    val body = sin(phase) * env(t, T)
                    val click = noise() * env(t, 0.004) * 0.6 * s.snap
                    val drive = lerp(1.0, 5.0, s.tone)
                    tanh((body + click) * drive) / tanh(drive)
                }
                SNARE -> {
                    val f = lerp(140.0, 260.0, s.tune)
                    phase += 2 * PI * f * (1 + 0.5 * exp(-t / 0.01)) / sr
                    phase2 += 2 * PI * f * 1.6 / sr
                    val body = (sin(phase) + 0.5 * sin(phase2)) * env(t, T * 0.45)
                    val n = hp1.hp(lp1.lp(noise(), lerp(3000.0, 12000.0, s.tone)), 600.0)
                    val mix = lerp(0.35, 0.85, s.snap)
                    body * (1 - mix) + n * env(t, T) * mix * 1.6
                }
                CLAP -> {
                    // tri brza udara (više ruku), pa rep
                    val bursts = listOf(0.0, 0.011, 0.022).sumOf { b -> if (t >= b) env(t - b, 0.03) else 0.0 }
                    val tail = if (t >= 0.022) env(t - 0.022, T) * 0.7 else 0.0
                    val n = bp.bp(noise(), lerp(700.0, 1800.0, s.tune) * lerp(0.8, 1.5, s.tone), lerp(0.5, 2.5, s.snap))
                    n * (bursts.coerceAtMost(1.0) + tail)
                }
                RIM -> {
                    val f = lerp(1200.0, 2400.0, s.tune)
                    phase += 2 * PI * f / sr
                    phase2 += 2 * PI * f * 0.37 / sr
                    val tone = (sin(phase) + sin(phase2)) * env(t, T)
                    val n = bp.bp(noise(), lerp(2000.0, 6000.0, s.tone), 1.2) * env(t, 0.01) * s.snap
                    tone + n * 2
                }
                HAT_CLOSED, HAT_OPEN -> {
                    val m = metallic(lerp(0.75, 1.4, s.tune))
                    val n = noise()
                    val mix = lerp(0.15, 0.7, s.snap)                    // više šuma = oštrije (909)
                    val x = m * (1 - mix) + n * mix
                    hp1.hp(bp.bp(x, lerp(6000.0, 11000.0, s.tone), 1.0), 6000.0) * env(t, T)
                }
                TOM -> {
                    val f0 = lerp(70.0, 260.0, s.tune)
                    phase += 2 * PI * f0 * (1 + 0.6 * exp(-t / 0.05)) / sr
                    val body = sin(phase) * env(t, T)
                    val n = lp1.lp(noise(), lerp(1500.0, 6000.0, s.tone)) * env(t, 0.03) * 0.5 * s.snap
                    body + n
                }
                CYMBAL -> {
                    val m = metallic(lerp(0.8, 1.5, s.tune))
                    val n = noise()
                    val mix = lerp(0.3, 0.8, s.snap)
                    val x = m * (1 - mix) + n * mix
                    // brzi udar + dug rep
                    val e = env(t, T) * 0.8 + env(t, 0.08) * 0.4
                    hp1.hp(bp.bp(x, lerp(5000.0, 10000.0, s.tone), 0.7), 4000.0) * e
                }
                COWBELL -> {
                    val sc = lerp(0.8, 1.3, s.tune)
                    phase += 540.0 * sc / sr
                    phase2 += 800.0 * sc / sr
                    val sq = (if (phase % 1.0 < 0.5) 1.0 else -1.0) + (if (phase2 % 1.0 < 0.5) 1.0 else -1.0)
                    val e = env(t, T) * 0.7 + env(t, 0.03) * 0.3 * (0.5 + s.snap)
                    bp.bp(sq, lerp(1500.0, 3200.0, s.tone), 2.0) * e
                }
                ZAP -> {
                    val hi = lerp(1200.0, 5000.0, s.tune)
                    val lo = lerp(60.0, 200.0, s.tone)
                    val f = lo + (hi - lo) * exp(-t / lerp(0.01, 0.05, s.snap))
                    phase += 2 * PI * f / sr
                    sin(phase) * env(t, T)
                }
                SHAKER -> {
                    val attack = (t / lerp(0.03, 0.005, s.snap)).coerceAtMost(1.0)
                    val n = hp1.hp(lp1.lp(noise(), lerp(7000.0, 14000.0, s.tone)), lerp(3000.0, 6000.0, s.tune))
                    n * attack * env(t, T)
                }
                // ---- akustični
                AC_KICK -> {
                    val head = modes(t, 1 + 0.25 * exp(-t / 0.03))
                    // palica (klik, jači sa snap) i "udar zraka" iz bubnja
                    val beater = bp.bp(noise(), lerp(2000.0, 5000.0, s.tone), 1.0) * env(t, 0.004) * 1.5 * s.snap
                    val thump = lp1.lp(noise(), 150.0) * env(t, 0.025) * 3.0
                    tanh((head + beater + thump) * 1.5)
                }
                AC_SNARE -> {
                    val head = modes(t, 1 + 0.08 * exp(-t / 0.02))
                    // žice ispod kože: širok šum; snap = koliko su zategnute (jače)
                    val wires = hp1.hp(lp1.lp(noise(), lerp(5000.0, 11000.0, s.tone)), 1500.0) *
                        env(t, T) * (1 - exp(-t / 0.001)) * lerp(0.4, 1.3, s.snap)
                    val stick = bp.bp(noise(), 3500.0, 1.5) * env(t, 0.003) * 0.8
                    head * 0.8 + wires * 2.2 + stick
                }
                SIDESTICK -> {
                    // palica preko oboda: kratak drveni ton i klik
                    val sc = lerp(0.8, 1.25, s.tune)
                    phase += 2 * PI * 520.0 * sc / sr
                    phase2 += 2 * PI * 1310.0 * sc / sr
                    val wood = (sin(phase) + 0.6 * sin(phase2)) * env(t, T)
                    val click = bp.bp(noise(), lerp(1800.0, 4000.0, s.tone), 2.0) * env(t, 0.006) * lerp(1.0, 3.0, s.snap)
                    wood + click
                }
                AC_TOM -> {
                    val head = modes(t, 1 + 0.12 * exp(-t / 0.06))
                    val stick = lp1.lp(noise(), lerp(2000.0, 6000.0, s.tone)) * env(t, 0.01) * 0.6 * s.snap
                    head + stick
                }
                AC_HAT_CLOSED, AC_HAT_OPEN -> {
                    val metal = modes(t) / 6.0
                    // "čik" (ivice činela se dodirnu) i oštrina: šum, jači sa snap
                    val n = hp1.hp(noise(), 7000.0) * env(t, T * 0.7) * lerp(0.2, 0.9, s.snap)
                    val stick = bp.bp(noise(), 8000.0, 1.5) * env(t, 0.002)
                    hp2.hp(metal + n + stick, 3000.0)
                }
                CRASH -> {
                    val swell = 1 - exp(-t / 0.004)
                    val metal = modes(t) / 7.0
                    val n = hp1.hp(lp1.lp(noise(), lerp(6000.0, 14000.0, s.tone)), 3000.0) * env(t, T * 0.6) * lerp(0.2, 0.8, s.snap)
                    (metal + n) * swell
                }
                RIDE -> {
                    val metal = modes(t) / 6.0
                    val ping = bp.bp(noise(), 6000.0, 1.5) * env(t, 0.003) * 1.2
                    hp1.hp(metal + ping, 250.0)
                }
                // ---- perkusije
                CONGA_LO, CONGA_HI, BONGO_LO, BONGO_HI -> {
                    val head = modes(t, 1 + 0.04 * exp(-t / 0.02))
                    // dlan: tup udar (open tone); snap = slap (oštar, visok šum, kratak)
                    val palm = lp1.lp(noise(), lerp(900.0, 2500.0, s.tone)) * env(t, 0.006) * 0.9
                    val slap = bp.bp(noise(), lerp(2500.0, 5000.0, s.tone), 1.2) * env(t, 0.012) * 2.5 * s.snap * s.snap
                    head * (1 - 0.4 * s.snap) + palm + slap
                }
                TIMBALE -> {
                    val shell = modes(t, 1 + 0.03 * exp(-t / 0.02))
                    val stick = bp.bp(noise(), lerp(2500.0, 6000.0, s.tone), 1.5) * env(t, 0.004) * 1.5
                    shell + stick
                }
                CLAVE -> {
                    val wood = modes(t)
                    val click = bp.bp(noise(), 4000.0, 2.0) * env(t, 0.002) * lerp(0.3, 1.5, s.snap)
                    wood + click
                }
                TAMBOURINE -> {
                    // praporci: udar + kratko "drmanje" (praporci se sudaraju još par puta)
                    val shakes = listOf(0.0, 0.018, 0.041).sumOf { b -> if (t >= b) env(t - b, 0.05) * if (b == 0.0) 1.0 else 0.45 else 0.0 }
                    val jingle = modes(t) / 5.0 * (0.6 + 0.4 * shakes.coerceAtMost(1.0))
                    val n = hp1.hp(lp1.lp(noise(), lerp(8000.0, 15000.0, s.tone)), 5000.0) * (shakes.coerceAtMost(1.2) * 0.25 + env(t, T * 0.5) * 0.25)
                    // koža tambure (tup udar), jača sa snap
                    phase += 2 * PI * 260.0 / sr
                    val skin = sin(phase) * env(t, 0.05) * 0.4 * s.snap
                    hp2.hp(jingle + n, 2500.0) + skin
                }
                AGOGO -> {
                    val bell = modes(t)
                    val stick = bp.bp(noise(), 3000.0, 1.5) * env(t, 0.003) * lerp(0.3, 1.5, s.snap)
                    bell + stick
                }
            }
            return y.toFloat()
        }
    }

    // ---------------------------------------------------------------- lo-fi (grit)

    /** Grit 0..1: manja "rezolucija" (sample & hold), manje bitova, tamnije i blago zasićenje. */
    private fun grit(x: FloatArray, amount: Float, sr: Int) {
        if (amount <= 0.01f) return
        val hold = 1 + (amount * 5).roundToInt()                 // do ~8 kHz pri 48 kHz
        val levels = 2.0.pow(14 - amount * 9.0).toFloat()          // 14 -> 5 bita
        val lp = OnePole(sr)
        var held = 0f
        for (i in x.indices) {
            if (i % hold == 0) held = x[i]
            val q = (held * levels).roundToInt() / levels
            val sat = tanh(q * (1 + amount * 2.0)).toFloat()
            x[i] = lp.lp(sat.toDouble(), 14000.0 - 9000.0 * amount).toFloat()
        }
    }

    // ---------------------------------------------------------------- filteri

    private class OnePole(val sr: Int) {
        private var y = 0.0
        private var yh = 0.0
        fun lp(x: Double, fc: Double): Double {
            val a = 1 - exp(-2 * PI * fc / sr)
            y += a * (x - y)
            return y
        }
        fun hp(x: Double, fc: Double): Double {
            val a = 1 - exp(-2 * PI * fc / sr)
            yh += a * (x - yh)
            return x - yh
        }
    }

    /** State-variable (Chamberlin) bandpass; q veći = uži. */
    private class Svf(val sr: Int) {
        private var low = 0.0
        private var band = 0.0
        fun bp(x: Double, fc: Double, q: Double): Double {
            val f = 2 * sin(PI * fc.coerceAtMost(sr / 6.0) / sr)
            val damp = 1.0 / q.coerceAtLeast(0.5)
            low += f * band
            val high = x - low - damp * band
            band += f * high
            return band
        }
    }
}

/** Ugrađeni kitovi: 8 zvukova u rasporedu IMPORT MIDI (Kick, Snare, Clap/Rim, Closed HH, Open HH, Tom, Cymbal, Perc). */
object DrumKits {
    data class Kit(val name: String, val sounds: List<DrumSynthSpec>)

    private fun k(kind: DrumSynthKind, tune: Float, decay: Float, tone: Float, snap: Float, grit: Float = 0f) =
        DrumSynthSpec(kind, tune, decay, tone, snap, grit)

    val all = listOf(
        Kit("808", listOf(
            k(KICK, 0.25f, 0.75f, 0.15f, 0.2f),
            k(SNARE, 0.45f, 0.45f, 0.45f, 0.55f),
            k(CLAP, 0.5f, 0.5f, 0.5f, 0.5f),
            k(HAT_CLOSED, 0.5f, 0.35f, 0.55f, 0.25f),
            k(HAT_OPEN, 0.5f, 0.55f, 0.55f, 0.25f),
            k(TOM, 0.35f, 0.6f, 0.4f, 0.15f),
            k(CYMBAL, 0.5f, 0.7f, 0.55f, 0.45f),
            k(COWBELL, 0.5f, 0.4f, 0.5f, 0.5f)
        )),
        Kit("909", listOf(
            k(KICK, 0.45f, 0.4f, 0.55f, 0.75f),
            k(SNARE, 0.6f, 0.35f, 0.75f, 0.7f),
            k(CLAP, 0.55f, 0.4f, 0.6f, 0.6f),
            k(HAT_CLOSED, 0.6f, 0.25f, 0.8f, 0.7f),
            k(HAT_OPEN, 0.6f, 0.5f, 0.8f, 0.7f),
            k(TOM, 0.5f, 0.45f, 0.5f, 0.35f),
            k(CYMBAL, 0.55f, 0.8f, 0.8f, 0.7f),
            k(RIM, 0.5f, 0.35f, 0.6f, 0.5f)
        )),
        Kit("Electro", listOf(
            k(KICK, 0.55f, 0.3f, 0.7f, 0.5f),
            k(SNARE, 0.7f, 0.25f, 0.9f, 0.8f),
            k(CLAP, 0.65f, 0.3f, 0.75f, 0.75f),
            k(HAT_CLOSED, 0.75f, 0.15f, 0.9f, 0.5f),
            k(HAT_OPEN, 0.75f, 0.35f, 0.9f, 0.5f),
            k(TOM, 0.7f, 0.3f, 0.6f, 0.2f),
            k(CYMBAL, 0.7f, 0.5f, 0.9f, 0.6f),
            k(ZAP, 0.5f, 0.4f, 0.5f, 0.5f)
        )),
        Kit("Lo-Fi", listOf(
            k(KICK, 0.3f, 0.5f, 0.3f, 0.3f, 0.55f),
            k(SNARE, 0.4f, 0.4f, 0.3f, 0.5f, 0.6f),
            k(CLAP, 0.45f, 0.45f, 0.35f, 0.5f, 0.6f),
            k(HAT_CLOSED, 0.45f, 0.3f, 0.3f, 0.5f, 0.6f),
            k(HAT_OPEN, 0.45f, 0.45f, 0.3f, 0.5f, 0.6f),
            k(TOM, 0.4f, 0.5f, 0.3f, 0.2f, 0.55f),
            k(CYMBAL, 0.45f, 0.55f, 0.35f, 0.5f, 0.6f),
            k(SHAKER, 0.5f, 0.4f, 0.4f, 0.5f, 0.5f)
        )),
        // akustični (modalna sinteza + soba); traka 6 = crash, traka 7 = ride
        Kit("Acoustic", listOf(
            k(AC_KICK, 0.4f, 0.45f, 0.5f, 0.55f),
            k(AC_SNARE, 0.45f, 0.45f, 0.55f, 0.6f),
            k(SIDESTICK, 0.5f, 0.4f, 0.5f, 0.5f),
            k(AC_HAT_CLOSED, 0.5f, 0.4f, 0.5f, 0.5f),
            k(AC_HAT_OPEN, 0.5f, 0.5f, 0.5f, 0.5f),
            k(AC_TOM, 0.4f, 0.5f, 0.5f, 0.5f),
            k(CRASH, 0.5f, 0.6f, 0.55f, 0.5f),
            k(RIDE, 0.5f, 0.6f, 0.5f, 0.5f)
        )),
        Kit("Rock", listOf(
            k(AC_KICK, 0.3f, 0.55f, 0.6f, 0.75f),
            k(AC_SNARE, 0.35f, 0.55f, 0.7f, 0.8f),
            k(SIDESTICK, 0.45f, 0.4f, 0.55f, 0.6f),
            k(AC_HAT_CLOSED, 0.45f, 0.5f, 0.65f, 0.6f),
            k(AC_HAT_OPEN, 0.45f, 0.65f, 0.65f, 0.6f),
            k(AC_TOM, 0.25f, 0.7f, 0.55f, 0.6f),
            k(CRASH, 0.45f, 0.8f, 0.7f, 0.6f),
            k(RIDE, 0.45f, 0.7f, 0.6f, 0.7f)
        )),
        Kit("Jazz", listOf(
            k(AC_KICK, 0.7f, 0.6f, 0.35f, 0.25f),
            k(AC_SNARE, 0.7f, 0.35f, 0.45f, 0.35f),
            k(SIDESTICK, 0.55f, 0.45f, 0.45f, 0.45f),
            k(AC_HAT_CLOSED, 0.6f, 0.35f, 0.4f, 0.35f),
            k(AC_HAT_OPEN, 0.6f, 0.45f, 0.4f, 0.35f),
            k(AC_TOM, 0.6f, 0.6f, 0.4f, 0.35f),
            k(CRASH, 0.55f, 0.7f, 0.45f, 0.4f),
            k(RIDE, 0.55f, 0.85f, 0.45f, 0.6f)
        )),
        // perkusije (ruke, drvo, metal): uz bilo koji kit na drugom tracku ili same za latino / afro groove
        Kit("Percussion", listOf(
            k(CONGA_LO, 0.45f, 0.5f, 0.5f, 0.3f),
            k(CONGA_HI, 0.45f, 0.45f, 0.5f, 0.5f),
            k(CLAVE, 0.5f, 0.35f, 0.5f, 0.5f),
            k(TAMBOURINE, 0.5f, 0.4f, 0.5f, 0.4f),
            k(AGOGO, 0.5f, 0.45f, 0.5f, 0.5f),
            k(BONGO_LO, 0.45f, 0.45f, 0.5f, 0.4f),
            k(TIMBALE, 0.45f, 0.45f, 0.5f, 0.5f),
            k(BONGO_HI, 0.5f, 0.4f, 0.5f, 0.5f)
        ))
    )
}
