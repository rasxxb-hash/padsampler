# Kako pokrenuti (faza 1)

1. Android Studio (novija verzija): **File > Open** i izaberi folder `PadSampler`.
2. Pričekaj Gradle sync. Ako traži, klikni **Install** za: Android SDK 35, **NDK**, **CMake 3.22.1**.
   Ako ponudi "Upgrade Android Gradle Plugin", možeš ga preskočiti.
3. Na tabletu: Settings > About > 7x dodir na broj verzije > **Developer options > USB debugging**.
4. Spoji tablet na računar, pritisni Run. (Launchpad se kasnije spaja na tablet preko USB-C, pa
   za debug koristi bežični ADB: Developer options > Wireless debugging.)
5. Pošalji PDF "Launchpad Mini MK3 Programmer's Reference Manual" u `docs/` folder.

## Šta bi trebalo vidjeti
- Tablet: mreža padova 8x8. Dodir na plavi pad = zvuk. Gornji (narandžasti) red = loop, tap = start/stop.
- Lijevo: sample rate, buffer i procijenjena latencija izlaza.
- Spojen Launchpad: status "povezan", padovi svijetle plavo/narandžasto, svirajući pad svijetli bijelo/zeleno.
- Gornje lijevo okruglo dugme na Launchpadu = STOP SVE.

## Ako ne radi
- Nema zvuka: pogledaj Logcat, filter `PadSampler`.
- Launchpad "nije povezan": provjeri USB-C kabl/OTG, pa pošalji tačan naziv uređaja iz Logcata.
- LED ne svijetle a padovi rade: vjerovatno SysEx/portovi, provjeri naspram PDF-a.
- Pucketanje u zvuku: u `audio_engine.cpp` povećaj `getFramesPerBurst() * 2` na `* 3`.

# Faza 2a: vlastiti semplovi
1. Pritisni **UREDI SEMPLOVE** (lijevo). Padovi dobiju žuti okvir i ne sviraju dok je ovo uključeno.
2. Dodirni pad, pa **Izaberi sempl** i nađi wav, aiff ili mp3 na tabletu.
3. Prekidač **Loop** određuje je li pad loop (tap = start/stop) ili one-shot. Ako se ime fajla sadrži "loop", automatski je loop.
4. Pritisni **ZAVRŠI UREĐIVANJE**. Raspored se sam sprema i vraća pri sljedećem pokretanju.
5. Tamni padovi su demo zvukovi, svijetli su tvoji semplovi.

Semplovi se kopiraju u privatni folder aplikacije, pa ne ovise o originalnim fajlovima.
Ograničenje: najviše oko 5 minuta po sempleru. AIFF-C kompresirani formati (ima4, ulaw) nisu podržani.

# Faza 2b: session mod i mikser
**Raspored Launchpada (session):** gornji red = metronom (svjetlo putuje kroz 8 osmina takta); 6 redova ispod = klipovi
(kolona = track, red = scena); donji red = Stop / Solo / Mute (dugme dolje desno mijenja mod: crveno, plavo, žuto).
Desna kolona: zelena dugmad lansiraju scenu. Gornje dugme **Session** (CC 95) prebacuje session <-> mikser.
Strelice: gore/dolje = tempo ±10, lijevo/desno = tempo ±1.

**Stanja klipa:** ugašen = prazan, prigušena boja = spreman, treperi = čeka početak/kraj takta, pulsira = svira.
Klip kreće i staje tek na početku sljedećeg takta. U koloni svira samo jedan klip.

**Mikser na Launchpadu:** svaka kolona je slajder (visina = jačina, boja kolone), donji red = mute (zeleno/crveno).
Na tabletu: 8 slajdera + MASTER desno, dodir na broj tempa za unos BPM-a.

**Tempo i BPM semplova:** svaki sempl ima originalni BPM. Aplikacija ga čita iz putanje ili imena foldera/fajla
(npr. "Deep House Pack - 124 BPM - Am/kick_loop.wav"). Prvi sempl sesije sa prepoznatim BPM-om postavlja i tempo sesije.
Ako BPM nije pronađen, uzima se trenutni tempo; u dijalogu pada (edit mod) možeš ga ručno upisati ("Set" za taj pad, "Set for all samples" za sve).
Kad promijeniš tempo, semplovi se razvlače bez promjene visine tona. To traje nekoliko sekundi u pozadini (na vrhu piše "adjusting samples to tempo"),
a svaki klip koji svira prelazi na novu brzinu na sljedećem taktu.
Napomena: preko "Downloads" izbornika Android ponekad ne pokaže putanju foldera; tada upiši BPM ručno.
Stari raspored padova iz faze 2a se ne prenosi (novi raspored 6x8), semplove dodijeli ponovo.

Ako Launchpad ne reagira ili LED ne svijetle: u Logcatu (filter `LaunchpadMidi`) vidi se koji su portovi otvoreni; pošalji mi taj ispis.
Zadrži liniju `ndkVersion` u `app/build.gradle.kts` (ona je tvoja, nemoj je prepisati).
