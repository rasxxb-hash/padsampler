# Third-party notices

PadSampler is free software, licensed under the **GNU General Public License v3.0 or later**
(see [`LICENSE`](LICENSE)). Copyright © 2026 Nerd Instruments.

PadSampler includes or uses the following third-party software and fonts. Their full license texts are in
the [`licenses/`](licenses) folder (and in the original source folders listed below). The same list is shown
in the app under **☰ → About**.

## Bundled in the app (Android and Windows)

| Component | Used for | Copyright | License |
|---|---|---|---|
| [Rubber Band Library](https://breakfastquay.com/rubberband/) | Time-stretching samples to the tempo | © 2007–2024 Particular Programs Ltd. | GPL-2.0-or-later — [`licenses/GPL-2.0-RubberBand-COPYING.txt`](licenses/GPL-2.0-RubberBand-COPYING.txt) (source: `app/src/main/cpp/rubberband-lib/`) |
| [Ableton Link](https://github.com/Ableton/link) | Tempo / beat sync and Link Audio over the local network | © 2016 Ableton AG, Berlin | GPL-2.0-or-later — [`licenses/Ableton-Link-LICENSE.md`](licenses/Ableton-Link-LICENSE.md) (source: `app/src/main/cpp/link-lib/`) |
| [Asio (standalone)](https://think-async.com/Asio/) | Networking used by Ableton Link | © Christopher M. Kohlhoff | Boost Software License 1.0 — [`licenses/BSL-1.0-asio.txt`](licenses/BSL-1.0-asio.txt) |
| Audiowide font | Logo and headings | © 2012 Brian J. Bonislawsky DBA Astigmatic (AOETI). Reserved Font Name "Audiowide" | SIL Open Font License 1.1 — [`licenses/OFL-1.1-Audiowide.txt`](licenses/OFL-1.1-Audiowide.txt) |
| Inter font | User interface text | © 2020 The Inter Project Authors | SIL Open Font License 1.1 — [`licenses/OFL-1.1-Inter.txt`](licenses/OFL-1.1-Inter.txt) |
| Kotlin standard library, kotlinx.coroutines | App code | © JetBrains s.r.o. and contributors | Apache-2.0 — [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |

## Android only

| Component | Used for | Copyright | License |
|---|---|---|---|
| [Oboe](https://github.com/google/oboe) | Low-latency audio output | © The Android Open Source Project | Apache-2.0 |
| Jetpack Compose, AndroidX (activity, lifecycle, core) | User interface | © The Android Open Source Project | Apache-2.0 |

## Windows only

| Component | Used for | Copyright | License |
|---|---|---|---|
| [RtAudio](https://github.com/thestk/rtaudio) 6.0.1 | Audio output (WASAPI, ASIO) | © 2001–2023 Gary P. Scavone | RtAudio license (MIT-style) — [`licenses/RtAudio.txt`](licenses/RtAudio.txt). Downloaded by CMake at build time. |
| Steinberg ASIO SDK host files (included in RtAudio) | ASIO driver support | © Steinberg Media Technologies GmbH | Used under the GPL-3.0 option of the ASIO SDK license |
| [dr_mp3](https://github.com/mackron/dr_libs) | MP3 decoding | David Reid | Public domain (Unlicense) or MIT-0 — [`licenses/dr_mp3.txt`](licenses/dr_mp3.txt) (source: `desktop/third_party/dr_mp3/`) |
| Compose Multiplatform (Desktop) | User interface | © JetBrains s.r.o. and contributors | Apache-2.0 |
| JetBrains Runtime (OpenJDK) | Java runtime bundled with the Windows installer | © Oracle and/or its affiliates, JetBrains s.r.o. | GPL-2.0 with Classpath Exception (license files are inside the installed `runtime/legal` folder) |
| JNI headers (`desktop/third_party/jni/`) | Build only | © Oracle and/or its affiliates | GPL-2.0 with Classpath Exception |

## Trademarks

PadSampler is an independent project and is not affiliated with, endorsed by or sponsored by any of the
companies below. Names are used only to describe compatibility.

- Ableton and Link are trademarks of Ableton AG.
- ASIO is a trademark and software of Steinberg Media Technologies GmbH.
- Novation and Launchpad are trademarks of Focusrite Audio Engineering Ltd.
- Android and Google Play are trademarks of Google LLC. Windows is a trademark of Microsoft Corporation.

## Source code

The complete source code of every released version is available at
<https://github.com/rasxxb-hash/padsampler>. If you received PadSampler as a binary (for example from Google
Play or as the Windows installer), you can always get the corresponding source code from that address.
