# PadSampler

Sampler za live nastupe, u DVIJE verzije koje se razvijaju UPOREDO: **Android** (tablet) i **Windows** (računar).
Kontroliše se Novation Launchpad Mini MK3 MIDI kontrolerom (USB, programmer mode). Pušta wav/aiff/mp3 semplove
i loopove, sa mikserom po kolonama.
Ciljni uređaji: Xiaomi Redmi Pad SE 11" (izlaz na slušalice, stereo, žičane) i Windows računar (WASAPI ili ASIO, npr. Behringer).

## Grana `linux`: PadSampler OS (USB)
- Na grani `linux` je i treća verzija: Linux sa USB-a koji odmah otvara PadSampler (`distro/`).
- **PRVO pročitaj `distro/STANJE.md`** (stanje, pravila, šta je otvoreno). Poslije SVAKE izmjene na ovoj grani dopuni
  `distro/STANJE.md` (Dnevnik + Otvoreno) i commituj ga zajedno sa izmjenom; korisnik radi sa više računara.
- Samo grana `linux`; `main`, Android i Windows se ne diraju. Bez Raspberry Pi-ja i bez instalacije na disk.

## Stek
- Kotlin + Compose (Jetpack Compose na Androidu, Compose Desktop na računaru), MVVM (ViewModel + StateFlow)
- Audio: zajednički C++ motor (`Mixer`, `SampleBank`, Link). Android: **Oboe**; računar: **RtAudio** (WASAPI/ASIO).
  NE koristiti ExoPlayer/MediaPlayer/SoundPool za pad zvukove.
- MIDI: Android `android.media.midi` (USB class-compliant, bez dodatnih dozvola); računar Java MIDI (`DesktopMidi`)
- Gradle Kotlin DSL, AGP 8.7.3, Kotlin 2.0.21, minSdk 26, compileSdk/targetSdk 36 (Google Play traži 36 od 31. 8. 2026; AGP upozorenje utišano u gradle.properties; u manifestu `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` drži landscape na Android 16 tabletima); desktop: Compose Multiplatform 1.7.1, CMake + MSVC (VS 2022 Build Tools)

## Struktura
- `app/src/main/cpp/` : ZAJEDNIČKI C++ (Android i računar, bez kopija): `mixer.*` i `decoder.*` (čisti C++, testirani na hostu), `stretcher.*` i `sample_bank.*` (time-stretch u pozadini, Rubber Band u `rubberband-lib/`, GPL), `link_sync.*` (Link u `link-lib/`), `studio.*` (Studio: pattern sequencer, drum kit, offline render; `AuxSource` miksera), `synth.*` (Minimoog-sintisajzer za Keys, `Arpeggiator`, efekti `KeysFx`), `jni_bridge.cpp` (isti most za obje platforme; `PADSAMPLER_DESKTOP` bira motor). Samo Android: `audio_engine.*` (Oboe).
- `shared/src/main/kotlin/com/example/padsampler/` : ZAJEDNIČKI KOD (Android i računar): `SamplerViewModel`, `PadSamplerScreen`, `ui/`, `model/`, `data/` (`PadStore`, `SessionStore`, `Library`, `BpmParser`, `MidiFile` = čitač .mid za IMPORT MIDI), `audio/NativeAudio` (JNI) i `SampleLoader`, `studio/DrumMachine` (Drums: kit, step sequencer; stanje u sesiji), `studio/DrumSynth` (ugrađeni bubnjevi: sinteza u wav, kitovi `DrumKits`), `studio/KeysMachine` i `studio/SynthPresets` (Keys: sintisajzer, piano roll, preseti), `studio/UndoHistory` (undo/redo Drums i Keys), `ui/StudioControls` (swing, undo, traka jačine), `data/DrumsJson`, `data/KeysJson`, `model/Keys.kt` (parametri sintisajzera, skale, raspored), `data/WavWriter` (Print to pad), `platform/Platform`, `midi/LaunchpadSurface` i `midi/MidiOut`, `midi/MidiKeyboard` (MIDI klavijatura za Keys; Android `AndroidKeyboard`, računar `DesktopKeyboard`). Uključuju ga i `app/build.gradle.kts` i `desktop-app/build.gradle.kts` kao dodatni izvorni folder. Ovdje NE smije biti `android.*` ni `java.awt`/`javax.sound`; sve platformsko ide kroz `Platform` / `UiPlatform`.
- Android omot (`app/src/main/java/com/example/padsampler/`): `platform/AndroidPlatform`, `midi/LaunchpadMidi` (implementira `MidiOut`), `audio/MediaDecoder` (mp3 preko MediaCodec), `ui/AndroidUiPlatform.kt`, `ui/AndroidFonts.kt`, `AudioService` (foreground), `MainActivity` (servis, ekran, dozvole)
- Windows motor `desktop/` (CMake): `src/desktop_audio_engine.*` (RtAudio; izbor API-ja/uređaja po imenu/bafera/rate-a), `src/desktop_jni.cpp` (JNI samo za računar: lista uređaja, primjena, `keepAwake`), `src/mp3_decoder.cpp` (dr_mp3), `src/audio_test.cpp` (probni zvuk); gradi `padsampler_core` (statička), `padsampler.dll` (JNI) i testove iz `native-tests/` (ctest). Vidi `desktop/README.md`.
- Windows aplikacija `desktop-app/` (Compose Desktop, svoj Gradle wrapper): `desktop/Main.kt` (učitava DLL iz resursa), `DesktopPlatform` (folderi = putanje, postavke u `%APPDATA%\PadSampler\settings.properties`), `DesktopUiPlatform`, `DesktopMidi` (izbor kontrolera, Auto = Launchpad), `DesktopAudio` (JNI + ključevi `DesktopSettings`), `SettingsDialog.kt` (⚙: audio, MIDI, full screen / F11), `DesktopFonts`. `UiPlatform.hasSettings` / `SettingsDialog` = ⚙ u gornjoj traci (Android zasad nema). Vidi `desktop-app/README.md`.
- `shared/.../model/Model.kt` (Layout, Fader, boje, stanja); `shared/.../ui/` : Compose ekrani (Session, Mixer, Drums, Keys, Browser, Header, Components, Theme); `UiPlatform` (logo, širina ekrana, nazad, izbor fajla/foldera) dolazi preko `LocalUiPlatform`
- `docs/SPECIFIKACIJA.md` : IZVOR ISTINE za ponašanje, raspored Launchpada, LED i mikser. Prvo čitaj to.
- `native-tests/` : `mixer_test.cpp`, `decoder_test.cpp`, `bank_test.cpp`, `link_test.cpp`, `studio_test.cpp`, `synth_test.cpp` (komanda za build je u zaglavlju svakog fajla; pokretati sa -fsanitize=address,undefined; na Windowsu preko ctest u `desktop/build`, a ASan sa MSVC: `cl /fsanitize=address` uz vcvars64)
- Dokumentacija kontrolera: `docs/` (PDF "Launchpad Mini MK3 Programmer's Reference")

## Build (obje platforme)
- Android: `./gradlew assembleDebug` (iz korijena). Java: JDK 21 (`JAVA_HOME` = `C:\Users\radov\.jdks\jbr-21.0.11`, trajno od 19. 11. 2026); JBR 25 iz novog Android Studija NE radi sa ovim Gradleom.
- Android release (pravi APK): `./gradlew assembleRelease` -> `app/build/outputs/apk/release/app-release.apk`, potpisan ključem VAN projekta (`%USERPROFILE%.padsamplerelease.jks` + `keystore.properties`; bez njih release nije potpisan). Ključ se NE smije izgubiti (bez njega nema ažuriranja preko instalirane verzije). versionName/versionCode = `appVersion` iz `desktop-app/gradle.properties`. Nativne biblioteke poravnate na 16 KB (Android 15+): linker `-z max-page-size=16384`, Oboe 1.9.3.
- Windows DLL: `cmake -S desktop -B desktop\build -G "Visual Studio 17 2022" -A x64`, pa `cmake --build desktop\build --config Release` i `ctest --test-dir desktop\build -C Release --output-on-failure`
- Windows aplikacija: `cd desktop-app; .\gradlew.bat run` (DLL mora biti izgrađen prije; `copyEngine` ga kopira u `desktop-app/resources/common`)
- Windows EXE (iz `desktop-app`): `.\gradlew.bat packageExe` (instalacija -> `build\installer\main\exe\`), `packageMsi` (`...\msi\`) ili `createDistributable` (folder sa PadSampler.exe bez instalacije -> `...\app\PadSampler\`). Prije svakog izdanja PODIĆI `appVersion` u `desktop-app/gradle.properties` (inače nova instalacija ne zamijeni staru).
- Instaler (exe/msi) ima slike PadSamplera umjesto WiX standardnih (crveni CD): `desktop-app/installer/` (`banner.bmp` 493x58, `dialog.bmp` 493x312, `main.wxs.template` = jpackage main.wxs iz JDK 21 + WixVariable). Ubacuje se u `build.gradle.kts` (refleksija na `jpackageResources`, vidi komentar tamo); provjera: `packageExe -PjpTemp`, pa `build/jp-temp-packageExe/config/main.wxs` mora imati WixVariable. Ikonica Androida: `app/src/main/res/drawable/ic_launcher_foreground.xml` (logo ~50 dp od 108).
- DLL koristi statički C++ runtime (/MT) i `_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR`, jer JVM učitava stariji `msvcp140.dll` (inače pad u `NativeAudio.start()`). Ne vraćati na /MD.

## Pravila rada (agent)
- Radi JEDAN zadatak po koraku. Prvo predloži kratak plan, pa implementiraj.
- Razvoj je UPOREDO za Android i računar: svaka nova funkcija ide u zajednički kod (`shared/`, `app/src/main/cpp/`) kad god je moguće, i mora raditi na obje platforme. Ako treba platformski dio, dodati ga kroz `Platform`/`UiPlatform` i implementirati i u `AndroidPlatform` i u `DesktopPlatform` (ili jasno reći šta na kojoj još fali).
- Poslije svake izmjene: `./gradlew assembleDebug`; ako je dirnut `shared/` ili `desktop-app/`, i build desktop aplikacije; ako je dirnut C++ (ili `jni_bridge.cpp`), i build DLL-a. Ako se C++ mikser/bank mijenja, pokreni `native-tests`.
- Novi JNI poziv: `shared/.../audio/NativeAudio.kt` + `jni_bridge.cpp`; mora raditi i sa Oboe i sa RtAudio motorom.
- Ne dodaji nove biblioteke bez pitanja. Sve ostalo u aplikaciji agent smije mijenjati bez posebnog pitanja (korisnik, 11. 11. 2026).
- Audio nit (`Mixer::render`): NIKAD alokacija memorije, mutex, logovanje, JNI pozivi ili I/O.
- Svi brojevi za Launchpad (SysEx, note, CC, boje) moraju doći iz PDF-a u `docs/`. Brojevi su provjereni; izbor MIDI porta (bez "DAW") treba potvrditi na uređaju.
- Sve radi offline. Nema mrežnih poziva u aplikaciji (osim Ableton Linka na lokalnoj mreži i, samo na Windowsu, ⚙ "Check for updates" koji pita GitHub API isključivo na klik korisnika: `desktop-app/.../UpdateCheck.kt`; NIKAD automatska provjera, korisnik 19. 11. 2026). Nema tajnih ključeva u kodu. (CMake konfiguracija desktopa jednom preuzima RtAudio, to je samo build.)
- Aplikacija mora ostati upotrebljiva na kraju svake faze. Mali koraci. Projekt je git repozitorij (od 16. 11. 2026, `main` -> GitHub `rasxxb-hash/padsampler`, javno); commit/push samo kad korisnik traži. Izdanje: podići appVersion, build, `gh release create vX.Y.Z <exe>` (gh: `C:Program FilesGitHub CLIgh.exe`).

## Domenska logika (session mod, kao Ableton)
- Kolona = track, red = scena, pad = clip. U jednoj koloni svira jedan clip odjednom.
- Launch quantization: clip kreće na sljedeći takt prema globalnom clocku.
- Tipovi clipova: loop, one-shot, "drži dok držiš".
- Mikser: fader po koloni (8 trackova), indikacija aktivnosti, upravljanje i sa Launchpada.
- Tempo (BPM 60-240) u gornjem desnom uglu, logo gore lijevo. Tonalitet je izbačen.
- slot = scena * 8 + track (6 scena x 8 trackova).
- Svaki sempl ima originalni BPM (`srcBpm`); `SampleBank` drži original i razvučenu kopiju u Mixeru. Mixer ne zna za razvlačenje, samo preuzima novu verziju na taktu.
- Ableton Link (`link_sync.*`, vendored `link-lib/`, GPL; koristi `ableton::LinkAudio`): mikser prima tempo i fazu takta kroz `ClockSource` u `render()`, a master miks daje kroz `OutputTap` (Link Audio kanal "PadSampler Master"). Start/stop sync: `requestStart/requestStop`, tuđi STOP -> `Mixer::stopAll` (callback na Link niti). UI dugme LINK, tempo se tada mijenja samo preko Linka. Test: `native-tests/link_test.cpp`.
- Browser semplova: indeks u fajlu + memoriji (`Library`, bez Room-a), preslušavanje (`Mixer::startPreview`, mimo miksera i mastera). Android: SAF dozvole trajne; računar: obične putanje.
- Sesija = folder sa JSON-om i kopijama semplova (`SessionStore`), imenovana, u privatnom folderu aplikacije. Učitava se u RAM. Izvoz/uvoz: jedan fajl `.padsession` (ZIP, `SessionStore.exportZip/importZip`), prenosiv između tableta i računara. Set lista: `SetListStore` (setlist.json) i `ui/SetListDialog`.
- Studio (vidi SPECIFIKACIJA "Studio"; S1-S3, S5-S7 gotovo, S4 preskočeno): ekrani DRUMS (CC 96) i KEYS (CC 97), pattern -> offline render u wav -> selektovani pad. C++ `studio.*` (pattern, drum kit, kasnije Minimoog-sintisajzer) je `AuxSource` miksera: svira kroz master, sinhrono sa taktom. Test: `native-tests/studio_test.cpp`. Keys snima uživo (`KeysMachine.toggleRec/tick`, odbrojavanje i metronom `studioSetClick`); Launchpad na DRUMS/KEYS: CC 19 = Play/Stop, CC 29 = REC (Keys).

## Za live nastup
- Ekran ne zaspi, bez dijaloga usred svirke.
- Android: audio u foreground servisu; Xiaomi: isključiti battery optimization; testirati na pravom uređaju (Redmi Pad SE), ne samo emulatoru.
- Računar: za nisku latenciju ASIO (`PADSAMPLER_API=asio`, `PADSAMPLER_DEVICE`, `PADSAMPLER_BUFFER`).

## Launchpad LED-ovi
- `LaunchpadSurface.kt` i `LaunchpadMidi.kt` se SMIJU mijenjati (korisnik skinuo zaključavanje 11. 11. 2026; nove ekrane i LED logiku agent radi bez posebnog pitanja). Boje Session/Mikser ekrana idu kroz `TrackColors` u `model/Model.kt`.
- `DesktopMidi` šalje iste poruke kao `LaunchpadMidi` (LED slika dolazi iz `LaunchpadSurface`); ne uvoditi drugačiju LED logiku za računar.
- Ne uvoditi RGB SysEx ni periodično ponovno slanje LED-ova (bljeskanje cijelog Launchpada). Izuzetak: jednokratno ponovno slanje slike 1,5 s i 4 s poslije spajanja (u ViewModelu).
- LED slika ide kroz `SamplerViewModel.ledPicture` (šalje samo CC-ove koji imaju LED; NIKAD CC 120-127, npr. 123 = All Notes Off gasi padove).

## Licenca i objavljivanje
- Projekt je **GPL-3.0-or-later**, © 2026 Nerd Instruments (`LICENSE`). Tuđe biblioteke i fontovi: `THIRD_PARTY_NOTICES.md` + tekstovi licenci u `licenses/`. Nova biblioteka ili font = dopuniti oba (i ekran About u aplikaciji).
- Android applicationId: `net.nerdinstruments.padsampler` (od 16. 11. 2026; NE mijenjati poslije objave na Playu). Paketi u kodu ostaju `com.example.padsampler` (JNI imena).
- Sajt `website/` (statičan HTML/CSS, bez JS-a i bez tuđih servisa): `index`, `manual` (EN), `uputstvo` (srpski, latinica), `download`, `privacy` (Play to traži), `license`, `404`, `_headers` (Cloudflare). Slike ekrana: `docs/images/` (kopija u `website/images/`, i u README.md). Nova ili promijenjena funkcija = ažurirati OBA uputstva (i README ako je bitno).
- Google Play: sve u `docs/play-store/` (`LISTING.md` = tekstovi, upitnici, koraci; ikona 512, feature graphic, slike tableta). AAB: `.gradlew.bat bundleRelease`.
- Izvorni kod: GitHub `rasxxb-hash/padsampler`; sajt: nerd-instruments.net (Cloudflare Pages, folder `website/`); Windows installer na GitHub Releases (>25 MB, ne može na Pages); Android je BESPLATAN na Google Play (iz BiH Google ne dozvoljava merchant nalog, pa nema prodaje; provjereno 17. 11. 2026). Podrška: `https://paypal.me/padsampler` (PayPal business nalog, od 18. 11. 2026) na sajtu (početna, download, footer svih stranica) i u README; PayPal "Donate" dugme iz BiH NE radi.
- `desktop/third_party/asio-sdk/` se ne objavljuje (build ga ne koristi; RtAudio nosi ASIO fajlove pod GPL-3.0 opcijom ASIO SDK-a).
