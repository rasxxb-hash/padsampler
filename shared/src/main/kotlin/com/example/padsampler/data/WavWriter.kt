package com.example.padsampler.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Piše stereo WAV, 24-bit PCM (čita ga naš dekoder na obje platforme i svaki DAW). */
object WavWriter {
    fun write24(file: File, interleavedStereo: FloatArray, sampleRate: Int) {
        val frames = interleavedStereo.size / 2
        val dataBytes = frames * 2 * 3
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
            fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.write("RIFF".toByteArray(Charsets.US_ASCII)); i32(36 + dataBytes)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII)); i32(16)
            i16(1)                       // PCM
            i16(2)                       // stereo
            i32(sampleRate)
            i32(sampleRate * 2 * 3)      // bajtova u sekundi
            i16(2 * 3)                   // block align
            i16(24)
            out.write("data".toByteArray(Charsets.US_ASCII)); i32(dataBytes)
            val buf = ByteArray(3 * 4096)
            var n = 0
            for (x in interleavedStereo) {
                val v = (x.coerceIn(-1f, 1f) * 8388607f).roundToInt()
                buf[n++] = (v and 0xFF).toByte()
                buf[n++] = ((v shr 8) and 0xFF).toByte()
                buf[n++] = ((v shr 16) and 0xFF).toByte()
                if (n == buf.size) { out.write(buf, 0, n); n = 0 }
            }
            if (n > 0) out.write(buf, 0, n)
        }
    }
}
