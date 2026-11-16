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
        val len = (v.length() * sr).toInt().coerceIn(sr / 50, (MAX_SECONDS * sr).toInt())
        val out = FloatArray(len) { v.next(it / sr.toDouble()) }
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
        ))
    )
}
