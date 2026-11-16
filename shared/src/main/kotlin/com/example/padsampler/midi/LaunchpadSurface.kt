package com.example.padsampler.midi

import com.example.padsampler.audio.NativeAudio
import com.example.padsampler.model.BottomMode
import com.example.padsampler.model.ControlState
import com.example.padsampler.model.DrumLayout
import com.example.padsampler.model.DrumsState
import com.example.padsampler.model.Fader
import com.example.padsampler.model.KeysLayout
import com.example.padsampler.model.KeysState
import com.example.padsampler.model.Layout
import com.example.padsampler.model.LiveState
import com.example.padsampler.model.Screen
import com.example.padsampler.model.TrackColors
import kotlin.math.floor

/**
 * Slika LED-ova: kodirana vrijednost po LED-u = (mod shl 16) or (B shl 8) or A.
 * mod: 0 statično (boja A), 1 treperi (A <-> B), 2 pulsira (boja A).
 * Za treperenje se prvo šalje statična boja A, pa treperenje do boje B (tako radi Launchpad).
 */
class LedPicture {
    val pad = IntArray(64) { UNKNOWN }          // indeks = red*8 + kolona (red 0 dolje)
    val cc = IntArray(128) { UNKNOWN }          // indeks = CC broj

    companion object {
        const val UNKNOWN = -1
        const val OFF = 0
        fun stat(c: Int) = c
        fun flash(a: Int, b: Int) = (1 shl 16) or (b shl 8) or a
        fun pulse(c: Int) = (2 shl 16) or c
    }
}

/** Iz stanja aplikacije pravi LED sliku (čista funkcija, bez Androida). */
object LaunchpadLeds {

    private fun mutedCC(row: Int) = (row + 1) * 10 + 9      // desna kolona: red 7 = CC 89 ... red 0 = CC 19
    const val CC_SESSION = 95
    const val CC_MODE = 19
    const val CC_DRUMS = 96
    const val CC_KEYS = 97
    /** Strelica dolje (gornji red: gore 91, dolje 92, lijevo 93, desno 94) i REC u desnoj koloni (KEYS). */
    const val CC_DOWN = 92
    const val CC_REC = 29

    fun compose(
        cs: ControlState,
        live: LiveState,
        drums: DrumsState = DrumsState(),
        keys: KeysState = KeysState(),
        held: Set<Int> = emptySet()
    ): LedPicture {
        val pic = LedPicture()
        java.util.Arrays.fill(pic.pad, LedPicture.OFF)
        java.util.Arrays.fill(pic.cc, LedPicture.OFF)

        when (cs.screen) {
            Screen.SESSION -> composeSession(pic, cs, live)
            Screen.MIXER -> composeMixer(pic, cs, live)
            Screen.DRUMS -> composeDrums(pic, live, drums)
            Screen.KEYS -> composeKeys(pic, live, keys, held)
        }

        // Dugmad
        pic.cc[CC_SESSION] = LaunchpadColors.let {
            when (cs.screen) {
                Screen.SESSION -> it.WHITE
                Screen.MIXER -> it.MINT
                else -> it.GREY
            }
        }
        pic.cc[CC_DRUMS] = if (cs.screen == Screen.DRUMS) LaunchpadColors.WHITE else LaunchpadColors.GREY
        pic.cc[CC_KEYS] = if (cs.screen == Screen.KEYS) LaunchpadColors.WHITE else LaunchpadColors.GREY
        if (cs.screen == Screen.SESSION || cs.screen == Screen.MIXER) {
            pic.cc[CC_MODE] = when (cs.bottomMode) {
                BottomMode.STOP -> LaunchpadColors.RED
                BottomMode.SOLO -> LaunchpadColors.BLUE
                BottomMode.MUTE -> LaunchpadColors.YELLOW
            }
        }
        // DRUMS / KEYS: desna kolona dolje CC 19 = Play/Stop tog dijela, KEYS CC 29 = REC;
        // KEYS: strelica dolje = potenciometri prikaži/sakrij
        val transport = when (cs.screen) {
            Screen.DRUMS -> live.drumsTransport
            Screen.KEYS -> live.keysTransport
            else -> null
        }
        if (transport != null) {
            pic.cc[CC_MODE] = when (transport) {
                NativeAudio.STUDIO_PLAYING -> LaunchpadColors.GREEN
                NativeAudio.STUDIO_QUEUED -> LedPicture.flash(GREEN_DIM, LaunchpadColors.GREEN)
                else -> GREEN_DIM
            }
        }
        if (cs.screen == Screen.KEYS) {
            pic.cc[CC_DOWN] = if (keys.panelOpen) LaunchpadColors.WHITE else LaunchpadColors.GREY
            pic.cc[CC_REC] = when (keys.rec) {
                KeysState.REC_ON -> LedPicture.pulse(LaunchpadColors.RED)
                KeysState.REC_COUNT_IN -> LedPicture.flash(RED_DIM, LaunchpadColors.RED)
                else -> RED_DIM
            }
        }
        return pic
    }

    // Tamne nijanse: mint (kao TrackColors.lpDim) i tamno crvena
    private const val GREEN_DIM = LaunchpadColors.MINT_DIM
    private const val RED_DIM = LaunchpadColors.RED_DIM


    /**
     * Drums: redovi 7..4 = 32 koraka (2 takta) izabranog zvuka; red 3 taman; red 2 = zvukovi (svira + bira);
     * red 1 = mute po zvuku; red 0 = Play/Stop, dužina 1/2/4/8, (5, 6 ništa), brisanje zvuka; desna kolona = strane.
     */
    private fun composeDrums(pic: LedPicture, live: LiveState, d: DrumsState) {
        // Bez metronoma: Drums se programira, ne svira (korisnik, 11. 11. 2026). Koraci su u redovima 7..4.
        val sel = d.selected.coerceIn(0, DrumLayout.LANES - 1)
        val tc = TrackColors.all[sel]
        val steps = d.steps[sel]
        val base = d.lpPage * DrumLayout.LP_STEPS_PER_PAGE
        for (i in 0 until DrumLayout.LP_STEPS_PER_PAGE) {
            val row = 7 - i / 8
            val step = base + i
            pic.pad[row * 8 + i % 8] = when {
                step >= d.totalSteps -> LedPicture.OFF
                step == live.drumStep -> LaunchpadColors.WHITE
                step in steps -> tc.lpBright
                step % 4 == 0 -> LaunchpadColors.GREY      // početak svake četvrtine (orijentacija)
                else -> LedPicture.OFF
            }
        }
        for (lane in 0 until DrumLayout.LANES) {
            val c = TrackColors.all[lane]
            val has = d.sounds[lane] != null
            pic.pad[DrumLayout.ROW_SOUNDS * 8 + lane] = when {
                !has -> if (lane == sel) LaunchpadColors.GREY else LedPicture.OFF
                lane == sel -> LaunchpadColors.YELLOW      // izabrani zvuk žuto
                else -> c.lpDim
            }
            pic.pad[DrumLayout.ROW_MUTES * 8 + lane] = when {
                !has -> LedPicture.OFF
                d.mutes[lane] -> LaunchpadColors.YELLOW      // mute = žuto (kao M u aplikaciji)
                else -> GREEN_DIM
            }
        }
        val f = Layout.FUNCTION_ROW * 8
        pic.pad[f + DrumLayout.COL_PLAY] = when (live.drumsTransport) {
            NativeAudio.STUDIO_PLAYING -> LaunchpadColors.GREEN
            NativeAudio.STUDIO_QUEUED -> LedPicture.flash(GREEN_DIM, LaunchpadColors.GREEN)
            else -> GREEN_DIM
        }
        DrumLayout.BAR_OPTIONS.forEachIndexed { i, bars ->
            pic.pad[f + DrumLayout.COL_LENGTH + i] = if (bars == d.bars) LaunchpadColors.WHITE else LaunchpadColors.GREY
        }
        pic.pad[f + DrumLayout.COL_CLEAR] = RED_DIM
        for (p in 0 until 4) {
            pic.cc[DrumLayout.pageCc(p)] = when {
                d.lpPages < 2 || p >= d.lpPages -> LedPicture.OFF   // strane samo za 4 i 8 taktova
                p == d.lpPage -> LaunchpadColors.WHITE
                else -> LaunchpadColors.GREY
            }
        }
    }

    // Keys: tonika (izabrani tonalitet) žuta, ostale note skale tamno mint, držane bijele, nota za STEP jarko mint
    private const val KEY_ROOT = LaunchpadColors.YELLOW
    private const val KEY_SCALE = LaunchpadColors.MINT_DIM

    /**
     * Keys: redovi 1..7 = klavijatura u skali (red = 3 stepena više, hromatski 5 polutonova); red 0 = Play,
     * oktava -, oktava +. Bez metronoma (kao Drums).
     */
    private fun composeKeys(pic: LedPicture, live: LiveState, k: KeysState, held: Set<Int>) {
        val keyRows = if (k.lpStep) 1..KeysLayout.STEP_KEY_ROWS else 1..7
        val sel = k.stepNote
        for (row in keyRows) for (col in 0..7) {
            val n = KeysLayout.lpNote(k, row, col)
            pic.pad[row * 8 + col] = when {
                n == null -> LedPicture.OFF
                n in held -> LaunchpadColors.WHITE
                k.lpStep && n == sel -> LaunchpadColors.MINT       // nota čiji se koraci programiraju
                (n - k.root) % 12 == 0 -> KEY_ROOT
                else -> KEY_SCALE
            }
        }
        if (k.lpStep) {
            // redovi 7..4: 32 koraka izabrane note (početak note plavo, nastavak tamno plavo)
            val cover = HashMap<Int, Boolean>()
            for (n in k.notes) {
                if (n.pitch != sel) continue
                for (s in n.step until minOf(n.step + n.length, k.totalSteps)) cover[s] = (cover[s] == true) || s == n.step
            }
            val base = k.lpPage * DrumLayout.LP_STEPS_PER_PAGE
            for (i in 0 until DrumLayout.LP_STEPS_PER_PAGE) {
                val step = base + i
                val c = cover[step]
                pic.pad[(7 - i / 8) * 8 + i % 8] = when {
                    step >= k.totalSteps -> LedPicture.OFF
                    step == live.keysStep -> LaunchpadColors.WHITE
                    c == true -> LaunchpadColors.MINT
                    c == false -> KEY_SCALE
                    step % 4 == 0 -> LaunchpadColors.GREY
                    else -> LedPicture.OFF
                }
            }
            for (p in 0 until 4) {
                pic.cc[DrumLayout.pageCc(p)] = when {
                    k.lpPages < 2 || p >= k.lpPages -> LedPicture.OFF
                    p == k.lpPage -> LaunchpadColors.WHITE
                    else -> LaunchpadColors.GREY
                }
            }
        }
        val f = Layout.FUNCTION_ROW * 8
        pic.pad[f + KeysLayout.COL_PLAY] = when (live.keysTransport) {
            NativeAudio.STUDIO_PLAYING -> LaunchpadColors.GREEN
            NativeAudio.STUDIO_QUEUED -> LedPicture.flash(GREEN_DIM, LaunchpadColors.GREEN)
            else -> GREEN_DIM
        }
        pic.pad[f + KeysLayout.COL_OCT_DOWN] = if (k.octave > KeysLayout.MIN_OCTAVE) LaunchpadColors.GREY else LedPicture.OFF
        pic.pad[f + KeysLayout.COL_OCT_UP] = if (k.octave < KeysLayout.MAX_OCTAVE) LaunchpadColors.GREY else LedPicture.OFF
        // PLAY / STEP mod: žuto = STEP (programiranje), sivo = PLAY (sviranje)
        pic.pad[f + KeysLayout.COL_MODE] = if (k.lpStep) LaunchpadColors.YELLOW else LaunchpadColors.GREY
    }

    private fun trackPlaying(live: LiveState, track: Int): Boolean {
        for (scene in 0 until Layout.SCENES) {
            val s = live.slotStates[Layout.slot(scene, track)].toInt()
            if (s == NativeAudio.PLAYING || s == NativeAudio.QUEUED_STOP) return true
        }
        return false
    }

    private fun composeSession(pic: LedPicture, cs: ControlState, live: LiveState) {
        // Red 7: metronom (8 osmina takta, svjetlo putuje s lijeva na desno)
        val idx = floor(live.barPhase.coerceIn(0f, 0.9999f) * 8f).toInt().coerceIn(0, 7)
        pic.pad[Layout.METRONOME_ROW * 8 + idx] = LedPicture.stat(LaunchpadColors.WHITE)

        // Redovi 6..1: klipovi
        for (scene in 0 until Layout.SCENES) {
            val row = Layout.rowOfScene(scene)
            var anyClip = false
            for (track in 0 until Layout.TRACKS) {
                val tc = TrackColors.all[track]
                val v = when (live.slotStates[Layout.slot(scene, track)].toInt()) {
                    NativeAudio.IDLE -> { anyClip = true; LedPicture.stat(tc.lpDim) }
                    NativeAudio.QUEUED_START, NativeAudio.QUEUED_STOP -> { anyClip = true; LedPicture.flash(tc.lpDim, tc.lpBright) }
                    NativeAudio.PLAYING -> { anyClip = true; LedPicture.pulse(tc.lpBright) }
                    else -> LedPicture.OFF
                }
                pic.pad[row * 8 + track] = v
            }
            pic.cc[mutedCC(row)] = if (anyClip) LaunchpadColors.GREEN else LedPicture.OFF
        }

        // Red 0: Stop / Solo / Mute
        for (track in 0 until Layout.TRACKS) {
            pic.pad[Layout.FUNCTION_ROW * 8 + track] = when (cs.bottomMode) {
                BottomMode.STOP -> LaunchpadColors.RED
                BottomMode.SOLO -> if (cs.solos[track]) LaunchpadColors.BLUE else LaunchpadColors.BLUE_DIM
                BottomMode.MUTE -> if (cs.mutes[track]) LaunchpadColors.YELLOW else LaunchpadColors.YELLOW_DIM
            }
        }
    }

    private fun composeMixer(pic: LedPicture, cs: ControlState, live: LiveState) {
        for (track in 0 until Layout.TRACKS) {
            val tc = TrackColors.all[track]
            val steps = Fader.stepFor(cs.faders[track])
            val playing = trackPlaying(live, track)
            for (k in 1..steps) {
                pic.pad[k * 8 + track] = if (playing) LedPicture.pulse(tc.lpBright) else LedPicture.stat(tc.lpBright)
            }
            pic.pad[Layout.FUNCTION_ROW * 8 + track] =
                if (cs.mutes[track]) LaunchpadColors.YELLOW else GREEN_DIM
        }
    }
}

/**
 * Šalje na Launchpad samo ono što se promijenilo od zadnje slike.
 * Zove se iz jedne niti (UI/ViewModel petlja).
 */
class LaunchpadSurface(private val midi: MidiOut) {
    private val shown = LedPicture()
    private val buf = ByteArray(1500)

    /** Zaboravi šta je prikazano (npr. nakon ponovnog spajanja): sljedeći render šalje sve. */
    fun invalidate() {
        dirty = true   // može se zvati iz bilo koje niti; stvarno brisanje radi render()
    }

    @Volatile private var dirty = true

    fun render(pic: LedPicture) {
        if (!midi.isConnected) return
        if (dirty) {
            dirty = false
            java.util.Arrays.fill(shown.pad, LedPicture.UNKNOWN)
            java.util.Arrays.fill(shown.cc, LedPicture.UNKNOWN)
        }
        var n = 0

        fun put(b0: Int, b1: Int, b2: Int) {
            if (n + 3 > buf.size) {
                midi.sendRaw(buf, n); n = 0
            }
            buf[n++] = b0.toByte(); buf[n++] = b1.toByte(); buf[n++] = b2.toByte()
        }

        fun emit(isCc: Boolean, id: Int, value: Int) {
            val mode = (value shr 16) and 0xFF
            val b = (value shr 8) and 0xFF
            val a = value and 0xFF
            val s0 = if (isCc) 0xB0 else 0x90
            when (mode) {
                0 -> put(s0, id, a)
                1 -> { put(s0, id, a); put(s0 + 1, id, b) }
                else -> put(s0 + 2, id, a)
            }
        }

        for (i in 0 until 64) {
            val v = pic.pad[i]
            if (v == shown.pad[i] && v != LedPicture.UNKNOWN) continue
            val row = i / 8
            val col = i % 8
            emit(false, (row + 1) * 10 + col + 1, v)
            shown.pad[i] = v
        }
        for (cc in 0 until 128) {
            val v = pic.cc[cc]
            if (v == LedPicture.UNKNOWN || v == shown.cc[cc]) continue
            emit(true, cc, v)
            shown.cc[cc] = v
        }
        midi.sendRaw(buf, n)
    }
}
