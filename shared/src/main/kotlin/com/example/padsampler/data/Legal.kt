package com.example.padsampler.data

/**
 * Licence za ekran About. Tekstovi se pri buildu kopiraju iz korijena projekta (LICENSE, THIRD_PARTY_NOTICES.md,
 * licenses/) u Java resurse "/legal/..." (Android: app/build.gradle.kts, računar: desktop-app/build.gradle.kts),
 * zajedno sa version.txt (appVersion). Spisak ispod mora odgovarati THIRD_PARTY_NOTICES.md.
 */
object Legal {
    const val COPYRIGHT = "© 2026 Nerd Instruments"
    const val SOURCE_URL = "https://github.com/rasxxb-hash/padsampler"
    const val WEBSITE_URL = "https://nerd-instruments.net"

    /** Jedan tuđi dio: [file] = fajl u /legal/ (null = tekst je u [note]). */
    class Component(
        val name: String,
        val purpose: String,
        val copyright: String,
        val license: String,
        val file: String?,
        val note: String? = null
    )

    val components = listOf(
        Component("Rubber Band Library", "Time-stretching", "© 2007–2024 Particular Programs Ltd.",
            "GPL-2.0-or-later", "GPL-2.0-RubberBand-COPYING.txt"),
        Component("Ableton Link", "Tempo sync, Link Audio", "© 2016 Ableton AG, Berlin",
            "GPL-2.0-or-later", "Ableton-Link-LICENSE.md"),
        Component("Asio (standalone)", "Networking for Link", "© Christopher M. Kohlhoff",
            "Boost Software License 1.0", "BSL-1.0-asio.txt"),
        Component("Audiowide font", "Logo, headings",
            "© 2012 Brian J. Bonislawsky DBA Astigmatic (AOETI)", "SIL Open Font License 1.1", "OFL-1.1-Audiowide.txt"),
        Component("Inter font", "Interface text", "© 2020 The Inter Project Authors",
            "SIL Open Font License 1.1", "OFL-1.1-Inter.txt"),
        Component("Kotlin, kotlinx.coroutines", "App code", "© JetBrains s.r.o. and contributors",
            "Apache-2.0", "Apache-2.0.txt"),
        Component("Oboe (Android)", "Low-latency audio", "© The Android Open Source Project",
            "Apache-2.0", "Apache-2.0.txt"),
        Component("Jetpack Compose, AndroidX (Android)", "User interface", "© The Android Open Source Project",
            "Apache-2.0", "Apache-2.0.txt"),
        Component("Compose Multiplatform (Windows)", "User interface", "© JetBrains s.r.o. and contributors",
            "Apache-2.0", "Apache-2.0.txt"),
        Component("RtAudio (Windows)", "Audio output (WASAPI, ASIO)", "© 2001–2023 Gary P. Scavone",
            "RtAudio license (MIT-style)", "RtAudio.txt"),
        Component("Steinberg ASIO SDK host files (Windows)", "ASIO driver support",
            "© Steinberg Media Technologies GmbH", "GPL-3.0 (option of the ASIO SDK license)", "LICENSE"),
        Component("dr_mp3 (Windows)", "MP3 decoding", "David Reid",
            "Public domain (Unlicense) or MIT-0", "dr_mp3.txt"),
        Component("JetBrains Runtime (Windows)", "Bundled Java runtime", "© Oracle and/or its affiliates, JetBrains s.r.o.",
            "GPL-2.0 with Classpath Exception", null,
            "The JetBrains Runtime (OpenJDK) is distributed under the GNU General Public License, version 2, " +
                "with the Classpath Exception. Its full license texts are installed with PadSampler in the " +
                "folder runtime\\legal (inside the PadSampler installation folder).")
    )

    const val TRADEMARKS = "PadSampler is an independent project and is not affiliated with, endorsed or sponsored by " +
        "the companies below; names are used only to describe compatibility. Ableton and Link are trademarks of " +
        "Ableton AG. ASIO is a trademark and software of Steinberg Media Technologies GmbH. Novation and Launchpad " +
        "are trademarks of Focusrite Audio Engineering Ltd. Android and Google Play are trademarks of Google LLC. " +
        "Windows is a trademark of Microsoft Corporation."

    /** Tekst iz /legal/ (null ako ga nema u buildu). */
    fun read(name: String): String? =
        Legal::class.java.getResourceAsStream("/legal/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }

    val version: String by lazy { read("version.txt")?.trim()?.ifEmpty { null } ?: "?" }
}
