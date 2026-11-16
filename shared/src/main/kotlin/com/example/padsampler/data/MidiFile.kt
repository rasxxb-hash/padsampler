package com.example.padsampler.data

/**
 * Čitač standardnog MIDI fajla (.mid, format 0 i 1), bez biblioteke. Uzima samo note; tempo i takt se ne koriste
 * (pattern svira na tempu sesije, takt 4/4). Note su grupisane po (traka, kanal): svaki dio je jedan instrument.
 */
object MidiFile {

    /** Nota u MIDI tickovima ([ppq] tickova po četvrtini). */
    data class Note(val start: Long, val end: Long, val pitch: Int, val velocity: Int)

    /** Jedan instrument iz fajla; [channel] 0-15 (9 = bubnjevi po General MIDI). */
    data class Part(val name: String, val channel: Int, val notes: List<Note>) {
        val isDrums get() = channel == 9
    }

    data class Song(val ppq: Int, val parts: List<Part>)

    class FormatException(msg: String) : Exception(msg)

    /** Nota poravnata na šesnaestine: korak, dužina (koraci, >= 1), visina, jačina 0..1. */
    data class GridNote(val step: Int, val length: Int, val pitch: Int, val velocity: Float)

    /** Dio poravnat za pattern: [bars] iz [barOptions], [cut] = koliko nota je odsječeno (iza zadnjeg takta). */
    data class Grid(val bars: Int, val notes: List<GridNote>, val cut: Int)

    /**
     * Poravna note na šesnaestine; prazni taktovi na početku se preskoče; dužina = najmanja opcija iz [barOptions]
     * koja stane (inače najveća, a ostatak se odsiječe). Dužina note se skrati na kraj patterna.
     */
    fun toGrid(part: Part, ppq: Int, barOptions: List<Int>, stepsPerBar: Int = 16): Grid {
        val q = ppq * 4.0 / stepsPerBar          // tickova po koraku (16 koraka = 4 četvrtine)
        val raw = part.notes.map { n ->
            val st = Math.round(n.start / q).toInt()
            val len = Math.round((n.end - n.start) / q).toInt().coerceAtLeast(1)
            GridNote(st, len, n.pitch, n.velocity / 127f)
        }
        if (raw.isEmpty()) return Grid(barOptions.first(), emptyList(), 0)
        val shift = raw.minOf { it.step } / stepsPerBar * stepsPerBar
        val lastEnd = raw.maxOf { it.step + it.length } - shift
        val need = (lastEnd + stepsPerBar - 1) / stepsPerBar
        val bars = barOptions.firstOrNull { it >= need } ?: barOptions.last()
        val total = bars * stepsPerBar
        val out = raw.mapNotNull { n ->
            val st = n.step - shift
            if (st >= total) null else n.copy(step = st, length = n.length.coerceAtMost(total - st))
        }
        return Grid(bars, out, raw.size - out.size)
    }

    fun parse(bytes: ByteArray): Song {
        val r = Reader(bytes)
        if (r.ascii4() != "MThd") throw FormatException("not a MIDI file")
        val hLen = r.u32()
        r.u16() // format (0 ili 1; 2 je rijedak i čita se isto)
        val nTracks = r.u16()
        val division = r.u16()
        if (division and 0x8000 != 0) throw FormatException("SMPTE timing is not supported")
        val ppq = division.coerceAtLeast(1)
        r.pos += (hLen - 6).toInt().coerceAtLeast(0)

        val parts = ArrayList<Part>()
        var t = 0
        while (t < nTracks && r.pos + 8 <= bytes.size) {
            val id = r.ascii4()
            val len = r.u32().toInt()
            val end = (r.pos + len).coerceAtMost(bytes.size)
            if (id == "MTrk") parts += readTrack(Reader(bytes, r.pos, end), t)
            r.pos = end
            if (id == "MTrk") t++
        }
        return Song(ppq, parts.filter { it.notes.isNotEmpty() })
    }

    private fun readTrack(r: Reader, index: Int): List<Part> {
        var name = ""
        var tick = 0L
        var status = 0
        // otvorene note: (kanal*128 + visina) -> početci (FIFO, ista nota može biti uključena više puta)
        val open = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()
        val byChannel = HashMap<Int, ArrayList<Note>>()
        fun close(ch: Int, pitch: Int, at: Long) {
            val q = open[ch * 128 + pitch] ?: return
            val (st, vel) = q.removeFirstOrNull() ?: return
            byChannel.getOrPut(ch) { ArrayList() } += Note(st, maxOf(at, st + 1), pitch, vel)
        }
        while (r.pos < r.end) {
            tick += r.vlq()
            if (r.pos >= r.end) break
            var b = r.u8()
            if (b < 0x80) {             // running status: bajt je već prvi podatak
                if (status == 0) break
                r.pos--
                b = status
            }
            when {
                b == 0xFF -> {
                    val type = r.u8()
                    val len = r.vlq().toInt()
                    if (type == 0x03 && name.isEmpty()) name = r.text(len) else r.pos += len
                    if (type == 0x2F) break
                }
                b == 0xF0 || b == 0xF7 -> r.pos += r.vlq().toInt()
                else -> {
                    status = b
                    val ch = b and 0x0F
                    when (b and 0xF0) {
                        0x80 -> { val p = r.u8(); r.u8(); close(ch, p and 0x7F, tick) }
                        0x90 -> {
                            val p = r.u8() and 0x7F
                            val v = r.u8() and 0x7F
                            if (v == 0) close(ch, p, tick)
                            else open.getOrPut(ch * 128 + p) { ArrayDeque() }.addLast(tick to v)
                        }
                        0xC0, 0xD0 -> r.pos += 1
                        else -> r.pos += 2   // 0xA0, 0xB0, 0xE0
                    }
                }
            }
        }
        // nota bez note off završi na kraju trake
        for ((key, q) in open) for ((st, vel) in q) {
            byChannel.getOrPut(key / 128) { ArrayList() } += Note(st, maxOf(tick, st + 1), key % 128, vel)
        }
        val many = byChannel.size > 1
        return byChannel.keys.sorted().map { ch ->
            val base = name.ifBlank { "Track ${index + 1}" }
            val label = when {
                ch == 9 -> "$base (drums)"
                many -> "$base (ch ${ch + 1})"
                else -> base
            }
            Part(label, ch, byChannel.getValue(ch).sortedBy { it.start })
        }
    }

    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun u8(): Int {
            if (pos >= end) throw FormatException("unexpected end of file")
            return b[pos++].toInt() and 0xFF
        }
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16().toLong() shl 16) or u16().toLong()
        fun ascii4() = String(CharArray(4) { u8().toChar() })
        fun text(len: Int): String {
            val n = len.coerceIn(0, end - pos)
            val s = String(b, pos, n, Charsets.ISO_8859_1).trim()
            pos += len
            return s
        }
        fun vlq(): Long {
            var v = 0L
            repeat(4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return v
            }
            return v
        }
    }
}
