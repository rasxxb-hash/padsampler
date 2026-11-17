# Google Play: PadSampler

Sve za Play Console na jednom mjestu. Tekstovi za prodavnicu su na engleskom (kopiraj ih kako jesu);
napomene za tebe su na bosanskom.

## Fajlovi

| Šta | Fajl | Play zahtjev |
|---|---|---|
| App bundle | `app/build/outputs/bundle/release/app-release.aab` (build: `.\gradlew.bat bundleRelease`) | AAB, potpisan upload ključem |
| Ikona | `icon-512.png` | 512 × 512 PNG |
| Feature graphic | `feature-1024x500.png` | 1024 × 500 PNG/JPG |
| Slike tableta (7" i 10") | `tablet-*.png` (1920 × 1200) | 2–8 slika, odnos stranica do 2:1 |
| Slike telefona | `phone-*.png` (2400 × 1200: ekran 2400 × 1080 + traka gore i dolje u boji ivice) | 2–8 slika, odnos stranica do 2:1 |

Grafike se prave iz `icon.html` i `feature.html` (Edge headless, vidi istoriju), slike ekrana su iz `docs/images/`.

---

## Store listing

**App name** (max 30):
```
PadSampler: Live Sampler
```

**Short description** (max 80):
```
Launch loops, program drums and synth, sync with Ableton Link. Built for stage.
```

**Full description** (max 4000):
```
PadSampler is a live sampler for musicians. Launch loops and one-shots from a clip grid, mix them, program drums and synth parts, and stay in time with Ableton Live and other apps over Ableton Link. It works completely offline and plays with a Novation Launchpad Mini MK3.

SESSION
• 6 scenes × 8 tracks clip grid. One clip per track plays at a time.
• Every clip starts on the next bar, so you never miss the beat.
• Loads WAV, AIFF and MP3. Change the tempo any time: every sample is time-stretched to it without changing pitch.
• Sample library with search, filters and preview.

MIXER
• Fader, level meter, mute, solo and pan for every track, plus a master channel.

DRUMS
• 8-lane step sequencer, 1 to 8 bars, swing and velocity per step, undo/redo.
• Built-in drum machine with 808, 909, Electro and Lo-Fi kits. Tune and shape every sound, or use your own samples.
• Import drum parts from MIDI files.

KEYS
• A classic three-oscillator synth with a 24 dB ladder filter, two envelopes, glide, mono and poly modes.
• 27 built-in presets, save your own.
• Arpeggiator, drive, chorus, tempo-synced delay and reverb.
• Piano roll in your scale. Draw notes or record live with count-in.
• Import melodies from MIDI files.

PRINT TO PAD
• Turn any Drums or Keys pattern into a seamless loop on a pad, ready to launch.

SYNC AND CONTROL
• Ableton Link: shared tempo, beat and start/stop with Ableton Live and other Link apps on the same Wi-Fi.
• Link Audio: send the master output straight into Ableton Live.
• Novation Launchpad Mini MK3 over USB: clip grid, scenes, mixer, drum steps and a keyboard in your scale, with LED feedback.
• Any class-compliant USB MIDI keyboard plays the synth, with MIDI learn for every knob.

SESSIONS AND SET LIST
• Save each song as a session with its own samples.
• Export a session as one file and open it on another tablet or on the Windows version.
• Set list to step through your show.

MADE FOR THE STAGE
• No account, no internet, no ads, no tracking.
• Low-latency audio, the screen stays on, audio keeps running in the background.
• Works on tablets and phones (landscape).

Wired headphones, speakers or a USB audio interface are recommended. Bluetooth audio adds too much delay for live playing.

OPEN SOURCE
PadSampler is free software under the GNU GPL v3. The source code is at github.com/rasxxb-hash/padsampler. The Windows version is also free, at nerd-instruments.net.

Manual: nerd-instruments.net/manual

Ableton and Link are trademarks of Ableton AG. Launchpad is a trademark of Focusrite Audio Engineering Ltd. PadSampler is not affiliated with these companies.
```

**Release notes** (prvo izdanje):
```
First release of PadSampler for Android.
```

## Kategorija i kontakt
- App or game: **App**; Free or paid: **Free**. Prodaja nije moguća: Bosna i Hercegovina nema merchant registraciju na Googleovoj listi (provjereno 17. 11. 2026, support.google.com/googleplay/android-developer/answer/9306917). Besplatna aplikacija se poslije ne može prebaciti na plaćenu.
- Category: **Music & Audio**
- Tags (do 5): Music production, Music & audio, DJ, Drums, Synthesizer (bira se sa ponuđene liste).
- Website: `https://nerd-instruments.net`
- Email: obavezan i JAVAN (tvoja odluka koji).
- Privacy policy: `https://nerd-instruments.net/privacy`

---

## App content (upitnici)

**Privacy policy**: `https://nerd-instruments.net/privacy`

**Ads**: No, my app does not contain ads.

**App access**: All functionality is available without special access (nema prijave).

**Content rating** (IARC upitnik):
- Category: **Utility, Productivity, Communication, or Other** (nije igra).
- Sva pitanja (nasilje, seks, jezik, droge, kocka, korisnici međusobno komuniciraju, dijeljenje lokacije, kupovine u aplikaciji): **No**.
- Očekivana ocjena: Everyone / PEGI 3.

**Target audience**: **13–15, 16–17, 18+** (ne birati mlađe od 13: tada važe posebna pravila "Families").
- Appeals to children: No.

**Data safety**:
- Does your app collect or share any of the required user data types? **No**.
  (Link razmjenjuje tempo i zvuk samo na lokalnoj mreži, na zahtjev korisnika, sa drugim uređajima korisnika; ništa ne ide programeru ni trećim stranama, pa to nije "collection".)
- Rezultat u prodavnici: "No data collected", "No data shared with third parties".

**Government apps**: No. **Financial features**: None. **Health**: None. **News**: No.

**Foreground service permissions** (Play traži jer targetSdk ≥ 34):
- Type: **Media playback** (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`).
- Description:
  ```
  PadSampler is a live music instrument. While the user plays a set, the foreground service keeps audio playback (loops, drums and synth) running when the screen turns off or another app is opened, so the music does not stop during a performance. Playback starts only when the user launches clips and stops when the user stops it.
  ```
- Video: Play traži link na kratak video (npr. YouTube "unlisted") koji pokazuje: pokreni loop, ugasi ekran ili otvori drugu aplikaciju, zvuk svira dalje, notifikacija PadSampler. Snimi telefonom ~30 s.

---

## Prije objave (novi lični nalog)
1. **Closed testing**: najmanje **12 testera** koji imaju aplikaciju instaliranu **14 dana zaredom**; tek onda "Apply for production".
   Testing → Closed testing → napravi track, dodaj email liste testera (Gmail adrese), uploaduj AAB, pošalji im link.
2. **Cijena: Free** (Monetize with Play → Products → App pricing). Payments/merchant profil se NE pravi (BiH nije podržana).
3. **Play App Signing**: pri prvom uploadu prihvati da Google drži ključ za potpis; naš `release.jks` postaje *upload key*. Ključ i dalje čuvaj (bez njega nema novih uploada, osim preko resetovanja upload ključa kod Googlea).
4. Svako novo izdanje: podići `appVersion` u `desktop-app/gradle.properties` (versionCode raste), `.\gradlew.bat bundleRelease`.
