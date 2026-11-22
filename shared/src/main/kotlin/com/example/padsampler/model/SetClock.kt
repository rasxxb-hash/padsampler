package com.example.padsampler.model

import com.example.padsampler.platform.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Sat seta (gornja traka): proteklo vrijeme od PRVOG pokrenutog clipa / Drums / Keys, ne od otvaranja aplikacije.
 * Mjeri se po stvarnom satu (monotonom), pa ga promjena tempa ili Link ne pomjeraju. Teče dok se ne resetuje
 * (pauza između pjesama je dio seta). Ciljno trajanje i način prikaza se pamte (postavke aplikacije).
 */
data class SetClockState(
    /** Sekunde od starta; -1 = set još nije počeo. */
    val elapsed: Int = -1,
    /** Ciljno trajanje u minutama; 0 = isključeno (prikazuje se samo proteklo). */
    val targetMin: Int = 0,
    /** true = prikazuje se preostalo vrijeme (samo kad postoji cilj). */
    val showRemaining: Boolean = false
) {
    val started get() = elapsed >= 0
    /** Preostale sekunde (negativno = prekoračeno); null bez cilja. */
    val remaining: Int? get() = if (targetMin > 0) targetMin * 60 - elapsed.coerceAtLeast(0) else null
    /** Zadnjih 5 minuta. */
    val warning get() = remaining?.let { it in 0..WARN_SECONDS } == true && started
    val over get() = remaining?.let { it < 0 } == true

    companion object {
        const val WARN_SECONDS = 5 * 60
        val TARGETS = listOf(0, 15, 20, 30, 45, 60, 75, 90, 120)

        fun format(seconds: Int): String {
            val s = kotlin.math.abs(seconds)
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}

class SetClock(private val platform: Platform) {
    private val _state = MutableStateFlow(
        SetClockState(
            targetMin = platform.getInt(KEY_TARGET, 0).coerceIn(0, 600),
            showRemaining = platform.getInt(KEY_REMAINING, 0) == 1
        )
    )
    val state: StateFlow<SetClockState> = _state

    @Volatile private var startNanos = 0L

    /** Iz petlje stanja (svakih 25 ms); stanje se mijenja samo jednom u sekundi. */
    fun tick(playing: Boolean) {
        // update{} je atomski: reset/podešavanje iz UI-a se ne pregaze
        _state.update { s ->
            if (!s.started) {
                if (!playing) return@update s
                startNanos = System.nanoTime()
                return@update s.copy(elapsed = 0)
            }
            val sec = ((System.nanoTime() - startNanos) / 1_000_000_000L).toInt()
            if (sec != s.elapsed) s.copy(elapsed = sec) else s
        }
    }

    /** Sat ponovo čeka prvi clip. */
    fun reset() {
        _state.update { it.copy(elapsed = -1) }
    }

    fun setTarget(minutes: Int) {
        val m = minutes.coerceIn(0, 600)
        platform.putInt(KEY_TARGET, m)
        _state.update { it.copy(targetMin = m) }
    }

    fun setShowRemaining(on: Boolean) {
        platform.putInt(KEY_REMAINING, if (on) 1 else 0)
        _state.update { it.copy(showRemaining = on) }
    }

    private companion object {
        const val KEY_TARGET = "set_target_min"
        const val KEY_REMAINING = "set_show_remaining"
    }
}
