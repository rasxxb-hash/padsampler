# TODO

Razvoj ide UPOREDO: Android (tablet) i Windows (računar). Zajednički kod: `shared/` (Kotlin) i `app/src/main/cpp/` (C++).
Oznake: [A] samo Android, [W] samo Windows, bez oznake = obje platforme.

## Urađeno
- [x] Faza 1: Oboe low-latency izlaz, C++ mixer, USB MIDI sa Launchpada, LED povratna sprega
- [x] Faza 2a: dekoder WAV/AIFF (C++), mp3 (Android MediaCodec, računar dr_mp3), edit mod, raspored se sam sprema (pads.json)
- [x] Faza 2b: session mod (6 scena x 8 trackova), launch na taktu, scene, Stop/Solo/Mute, mikser, metronom, MIDI clock
- [x] Faza 2c: tempo bez promjene visine tona (Rubber Band, paralelno razvlačenje, zajednička zamjena na taktu)
- [x] Faza 4: imenovane sesije (save/load/delete), izvoz/uvoz sesije u folder
- [x] Faza 5: browser biblioteke (indeks u fajlu, bez Room-a), pretraga, foldere, preslušavanje (original / na tempu)
- [x] Ableton Link (tempo + faza takta, offset kašnjenja)
- [x] Novi izgled (flat sa dubinom, Audiowide/Inter), foreground servis [A]
- [x] Zajednički kod izdvojen u `shared/` (`Platform`, `UiPlatform`, `MidiOut`)
- [x] Računar: jezgro + testovi kroz CMake, RtAudio (WASAPI/ASIO), `padsampler.dll` (isti JNI most), Compose Desktop aplikacija, Java MIDI za Launchpad [W]
- [x] Pad DLL-a u `msvcp140.dll` riješen (statički runtime /MT) [W]
- [x] Objavljivanje (16. 11. 2026): GPL-3.0 licenca i licence tuđih biblioteka, ekran About, applicationId `net.nerdinstruments.padsampler`, README za GitHub sa slikama, sajt `website/` (početna, uputstvo EN + srpski, download, privacy, license)
- [ ] Objavljivanje dalje: git init + push na GitHub, GitHub Release sa EXE-om, Cloudflare Pages (folder `website/`), Play Store (AAB, opis, grafike), Linux verzija

## Provjeriti na uređajima
- [ ] Android: latencija pad -> zvuk na Redmi Pad SE [A]
- [ ] Android: trajanje razvlačenja sa mnogo semplova; ako je presporo, razvlačiti samo vidljive scene ili smanjiti kvalitet [A]
- [ ] Windows: latencija i stabilnost sa ASIO (Behringer) i WASAPI [W]
- [ ] Windows: Launchpad preko Java MIDI (portovi, LED-ovi, clock), Link sa Androidom/Abletonom na istoj mreži [W]
- [ ] Sesija izvezena na tabletu učitava se na računaru i obrnuto

## Otvoreno
- [x] Tonalitet na padovima (Android bez putanje, stari padovi, Use iz browsera)
- [x] Računar: podešavanja (MIDI, audio uređaj), full screen (F11), ekran se ne gasi [W] (provjeriti sa ASIO i Launchpadom)
- [x] Export/import sesije kao jedan .padsession fajl (zamjenjuje izvoz u folder)
- [x] Set lista (prozor, redoslijed, sljedeća/prethodna)
- [x] Link start/stop sync (kao Ableton)
- [x] Link Audio: slanje mastera (kasnije možda po trackovima / primanje)
- [x] Provjereno sa Abletonom (Live Lite 12.4): start/stop, Link Audio snimanje sa tableta (11. 11. 2026)
- [ ] mp3 se dekodira pri svakom pokretanju: keširati kao wav
- [ ] Windows: instalacijski paket (MSI/EXE) isprobati na čistom računaru [W]

## Studio: Drums i Keys (vidi SPECIFIKACIJA "Studio")
- [x] Faza S1: C++ motor (`studio.*`): pattern, drum kit, sinhrono sa taktom, offline render bez šava, testovi
- [x] Faza S2: DRUMS ekran (aplikacija + Launchpad CC 96), step sequencer, preslušavanje
- [x] Faza S3: Print to pad (wav na selektovani pad) + pattern i kit u sesiji (ponovno otvaranje)
- [-] Faza S4: snimanje uživo — preskočeno (korisnik 11. 11. 2026: ritam se programira)
- [x] Faza S5: sintisajzer u stilu Minimooga (motor)
- [x] Faza S6: KEYS ekran (potenciometri, klavijatura u skali, Launchpad CC 97, preseti, piano roll, Print to pad)
- [x] Faza S7: swing, jačina po noti (traka jačine), undo/redo (Drums i Keys)
- [x] Snimanje uživo u KEYS (REC, odbrojavanje, metronom, dosnimavanje, poravnavanje 1/16 ili 1/8)
- [x] KEYS: biranje i pomjeranje nota (SELECT, ALL, ◀ ▶, to bar, DEL)
- [x] IMPORT MIDI u KEYS
- [x] IMPORT MIDI u DRUMS (GM raspored na 8 traka)
- [x] Ugrađena bubnjarska mašina 3a: kitovi 808, 909, Electro, Lo-Fi (dugme KIT, prazan Drums = 808)
- [x] Ugrađena bubnjarska mašina 3b: ugrađeni zvuk po traci + potenciometri
- [x] PAN po kanalu na mikseru
- [x] KEYS: tab ARP / FX, arpeggiator
- [x] KEYS: efekti (drive, chorus, delay, reverb); LENGTH kao padajući meni
- [x] Telefon 1: MIXER kompaktan; skrolovanje traka mišem na računaru
- [x] Telefon 2: DRUMS (bez mastera, meni ⋯, veći koraci)
- [x] Telefon 3: KEYS (panel preko piano rolla, manje redova + skrol)
- [x] MIDI klavijatura za Keys (note, velocity, pitch bend, mod), izbor MIDI IN na KEYS ekranu
- [x] MIDI LEARN: potenciometri Keys sa MIDI klavijature (pamti se po uređaju)

## Kasnije
- [ ] Favoriti, waveform u browseru
- Tonalitet je izbačen iz specifikacije
