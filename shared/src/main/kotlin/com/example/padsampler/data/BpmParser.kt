package com.example.padsampler.data

/**
 * Traži BPM u putanji ili imenu fajla, npr. "Deep House Pack - 124 BPM - Am/kick_loop.wav" => 124.
 * Gleda segmente od fajla prema korijenskom folderu i uzima prvi koji sadrži BPM.
 */
object BpmParser {
    private val before = Regex("""(?<![\d.])(\d{2,3}(?:[.,]\d{1,2})?)(?!\d)\s*[-_ ]?\s*bpm""", RegexOption.IGNORE_CASE)
    private val after = Regex("""bpm\s*[-_ :]?\s*(\d{2,3}(?:[.,]\d{1,2})?)(?!\d)""", RegexOption.IGNORE_CASE)

    fun fromText(text: String): Double? {
        val m = before.find(text) ?: after.find(text) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return if (v in 50.0..300.0) v else null
    }

    /** path: npr. "primary:Samples/Pack 124 BPM Am/kick.wav" (sve što uri/ime nudi, razdvojeno bilo čime). */
    fun fromPath(vararg parts: String?): Double? {
        val segments = parts.filterNotNull().flatMap { it.split('/', '\\', ':') }.filter { it.isNotBlank() }
        for (s in segments.asReversed()) fromText(s)?.let { return it }
        return null
    }
}
