package com.example.padsampler.data

/**
 * Traži tonalitet u putanji ili imenu fajla, npr. "Pad Am 124 BPM/bass_loop_C#m.wav" => "C#m".
 * Prepoznaje: Am, C#m, Bbm, F min, F# minor, Cmaj, G major, "key C", "Key_Dm".
 * Bemoli se prikazuju kao "b", dijezi kao "#", molski tonalitet kao "m", durski sa "maj" (ili samo slovo ako piše "key C").
 * Gleda segmente od fajla prema korijenskom folderu i uzima prvi koji sadrži tonalitet.
 * Služi samo za prikaz; aplikacija ne mijenja visinu tona.
 */
object KeyParser {
    // Nota velikim slovom + (dijez/bemol) + oznaka m / min / minor / maj / major; mora biti zaseban "token" (bez slova/cifara uz njega)
    private val withQuality = Regex(
        """(?<![A-Za-z0-9#])([A-G])([#b♯♭]?)[\s_-]?((?i:maj(?:or)?|min(?:or)?)|m)(?![A-Za-z0-9#])"""
    )
    // "key C", "Key_Am": nota se prihvata i bez oznake
    private val withKeyword = Regex(
        """(?i)(?<![A-Za-z0-9])key[\s_:-]*([A-G])([#b♯♭]?)[\s_-]?((?:maj(?:or)?|min(?:or)?|m)?)(?![A-Za-z0-9#])"""
    )

    private fun format(note: String, acc: String, quality: String): String {
        val n = note.uppercase()
        val a = when (acc) {
            "♯" -> "#"
            "♭" -> "b"
            else -> acc
        }
        val q = when {
            quality.isEmpty() -> ""
            quality.equals("m", ignoreCase = false) || quality.startsWith("min", ignoreCase = true) -> "m"
            else -> "maj"
        }
        return n + a + q
    }

    fun fromText(text: String): String? {
        withQuality.find(text)?.let { return format(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        withKeyword.find(text)?.let { return format(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        return null
    }

    /** path: sve što uri/ime nudi, razdvojeno bilo čime (kao kod BpmParser). */
    fun fromPath(vararg parts: String?): String? {
        val segments = parts.filterNotNull().flatMap { it.split('/', '\\', ':') }.filter { it.isNotBlank() }
        for (s in segments.asReversed()) fromText(s)?.let { return it }
        return null
    }
}
