package com.example.padsampler.audio

import com.example.padsampler.platform.Platform
import java.io.File

class SampleLoader(private val platform: Platform) {
    /**
     * Učita fajl u slot. WAV/AIFF dekodira naš C++ dekoder, ostalo (mp3...) dekoder platforme
     * (Android: MediaCodec). Vraća broj frameova ili 0 ako nije uspjelo.
     */
    fun load(slot: Int, file: File, srcBpm: Double, loopMode: Int): Int {
        try {
            val bytes = file.readBytes()
            val frames = NativeAudio.loadFromBytes(slot, bytes, srcBpm, loopMode)
            if (frames > 0) return frames
        } catch (e: Exception) {
            return 0
        } catch (e: OutOfMemoryError) {
            return 0
        }

        val decoded = platform.decodeAudio(file.absolutePath) ?: return 0
        return if (NativeAudio.loadSample(slot, decoded.data, decoded.frames, decoded.sampleRate, srcBpm, loopMode)) {
            decoded.frames
        } else {
            0
        }
    }
}
