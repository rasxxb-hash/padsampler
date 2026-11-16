package com.example.padsampler.studio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Undo/redo za Drums i Keys: čuva stanja PRIJE izmjene patterna (najviše [LIMIT]). Povlačenje (jačina, swing)
 * pravi jedan korak: izmjene istog [key] u razmaku do [MERGE_MS] spajaju se u jednu.
 * Zove se iz UI-a i MIDI niti, zato je sve pod lock-om.
 */
class UndoHistory<T> {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    private var lastKey: String? = null
    private var lastTime = 0L

    /** (ima undo, ima redo) za dugmad. */
    private val _available = MutableStateFlow(false to false)
    val available: StateFlow<Pair<Boolean, Boolean>> = _available

    @Synchronized
    fun record(before: T, key: String) {
        val now = System.currentTimeMillis()
        val merge = key == lastKey && key != EDIT && now - lastTime < MERGE_MS
        lastKey = key
        lastTime = now
        if (merge) return
        undo.addLast(before)
        while (undo.size > LIMIT) undo.removeFirst()
        redo.clear()
        publish()
    }

    /** Vraća stanje na koje treba preći (ili null); [current] ide u redo. */
    @Synchronized
    fun undo(current: T): T? {
        val s = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        lastKey = null
        publish()
        return s
    }

    @Synchronized
    fun redo(current: T): T? {
        val s = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        lastKey = null
        publish()
        return s
    }

    @Synchronized
    fun clear() {
        undo.clear()
        redo.clear()
        lastKey = null
        publish()
    }

    private fun publish() {
        _available.value = undo.isNotEmpty() to redo.isNotEmpty()
    }

    companion object {
        /** Ključ za izmjene koje se nikad ne spajaju (upali/ugasi korak, brisanje...). */
        const val EDIT = "edit"
        const val LIMIT = 100
        const val MERGE_MS = 1000L
    }
}
