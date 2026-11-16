# PadSampler: specifikacija (dogovoreno)

Ovo je izvor istine za ponašanje aplikacije. Sve ovdje je dogovoreno sa korisnikom.
Što nije ovdje, nije dogovoreno: pitati prije implementacije.

## Cilj
Sampler za live nastup, kontrolisan Launchpadom Mini MK3 (programmer mode) i tabletom.
Semplovi: wav, aiff, mp3. Svi semplovi jedne pjesme imaju isti BPM. Takt je uvijek 4/4.

## Raspored padova na Launchpadu (Session mod)
Red 0 je dolje, red 7 gore (kao u MIDI notama 11..88).

| Red | Šta |
|---|---|
| 7 (gore) | metronom: svjetlost putuje s lijeva na desno, 8 koraka = 8 osmina jednog takta, prvi pad (početak takta) jače obojen |
| 6 do 1 | klipovi: 6 scena x 8 trackova (kolona = track). Scena 0 je red 6, scena 5 je red 1 |
| 0 (dolje) | stop / solo / mute po kolonama (zavisi od moda) |

- Desna dugmad (CC 79, 69, 59, 49, 39, 29) pokreću scene 0 do 5 na sljedećem taktu. CC 89 ne radi ništa.
- Desno dolje (CC 19) "Stop Solo Mute": krug crveno (STOP), plavo (SOLO), žuto (MUTE), pa ponovo crveno.
  Cijeli donji red padova ima boju moda. U STOP modu pad zaustavlja kolonu (na sljedećem taktu),
  u SOLO modu uključuje/isključuje solo kolone, u MUTE modu uključuje/isključuje mute kolone.
- Gornji red dugmadi: ▲ tempo +10, ▼ tempo -10, ▶ tempo +1, ◀ tempo -1 (CC 91, 92, 94, 93),
  Session (CC 95) prebacuje Session <-> Mikser. Drums (CC 96) i Keys (CC 97): ekrani Studija (vidi "Studio"). User, logo: ništa.

## Mikser na Launchpadu
- Metronoma nema. Gornjih 7 padova (redovi 7 do 1) u koloni je slajder te kolone (7 koraka),
  donji red (red 0) je mute po kolonama.
- Slajder je stubić u boji kolone. Ako track svira, stubić pulsira (indikacija da kanal radi).
- Taper: dB = -60 * (1 - v)^2, v = korak/7. Koraci: -44, -31, -20, -11, -5, -1.2, 0 dB.
- Isto se kontroliše i na tabletu.

## Klipovi (ponašanje)
- Svaki klip počinje na početku sljedećeg takta (4/4, razlučivost 1 takt).
- Jedan klip po koloni: novi pad gasi stari tačno na taktu na kojem novi kreće (stari ima fade out prije granice takta).
- Ponovni pritisak na klip koji svira: zaustavlja se na sljedećem taktu (fade out). Pritisak na klip koji čeka start poništava start.
- Loop: ponavlja se, sinhronizovan na takt (tačno). One-shot (nije loop): svira do kraja.
- Dužina loopa u taktovima = round(trajanje originala * originalni BPM / 240). Automatski loop ako je dužina blizu cijelog broja taktova ili ime sadrži "loop"; može se mijenjati u edit modu.
- STOP SVE (tablet): odmah, kratak fade.

## LED stanja klipa (Launchpad i tablet)
| Stanje | Prikaz |
|---|---|
| prazan | ugašeno |
| ima sempl, miruje | boja kolone, prigušena |
| čeka start ili stop (do takta) | treperi (svijetla boja kolone) |
| svira | pulsira (svijetla boja kolone). Na tabletu i kružni indikator napretka klipa |

Boje kolona (automatske, uvijek iste): crvena, narandžasta, žuta, zelena, cijan, plava, ljubičasta, magenta.
Treperenje i pulsiranje na Launchpadu prate MIDI clock, koji aplikacija šalje Launchpadu u tempu BPM-a.

## Tempo (faza 2b+, time-stretch)
- Raspon 60 do 240 BPM. Tablet: - / broj (dodir za unos) / +. Launchpad: strelice (gore).
- Svaki sempl ima ORIGINALNI BPM. Tempo (trenutni) mijenja brzinu svih semplova BEZ promjene visine tona
  (Rubber Band, offline razvlačenje u pozadini). Tempo se može mijenjati u svakom trenutku, i dok semplovi sviraju.
- Originalni BPM se čita iz putanje/imena foldera ili fajla ("... 124 BPM ..."). Ako ga nema, uzima se tempo u trenutku
  učitavanja. Može se ručno promijeniti u dijalogu pada (za taj pad ili za sve padove).
- Prvi sempl sesije sa prepoznatim BPM-om postavlja i tempo sesije.
- Loop čija je dužina (blizu, do 3%) cijelog broja taktova razvlači se tako da traje TAČNO taj broj taktova.
  One-shot se razvlači omjerom (originalni BPM / tempo) i svira do kraja.
- Pri promjeni tempa takt (sat) se mijenja odmah, a semplovi se razvuku u pozadini (prvo oni koji sviraju) i svaki
  svirajući klip preuzima novu verziju na sljedećem taktu. Na tabletu piše koliko semplova još čeka.
- **Tonalitet je izbačen** (možda u dalekoj budućnosti).

## Računar (Windows)
- Ista aplikacija kao na tabletu (zajednički kod u `shared/`), isto ponašanje, ekrani i Launchpad raspored. Sve u ovoj specifikaciji važi za obje verzije, osim gdje piše drugačije.
- Zvuk: WASAPI ili ASIO. Folderi su obične putanje (bez SAF-a), postavke u `%APPDATA%\PadSampler`.
- ASIO: sve RtAudio operacije idu kroz jednu kontrolnu nit (COM STA). Ako drajver zatvori stream (npr. ne prihvati traženu frekvenciju: UMC drži 44.1 kHz), motor ga sam ponovo otvori, prvo na istoj pa na drugoj frekvenciji; ako ni to ne ide, prelazi na podrazumijevani izlaz (WASAPI). Napomena se vidi u podešavanjima.
- Full screen (F11 ili podešavanja): prozor bez okvira preko cijelog monitora na kojem je prozor.

## Tablet
- Session ekran (po skici): gore logo, tempo i naziv ekrana; lijevo "učitaj semplove", "sačuvaj sesiju", "učitaj sesiju";
  u sredini replika Launchpada istog rasporeda; desno glavna jačina (samo tablet) i mute.
- Mikser ekran: 8 slajdera u boji kolone, dB prikaz, mute ispod svakog. Bez metronoma.
- Prebacivanje ekrana: dugme Session na Launchpadu, ili dodir na naziv ekrana na tabletu.

## Ostalo (iz ranijih dogovora)
- Izlaz: slušalice, stereo. Ekran ne smije zaspati. Sve offline.
- Raspored se sam sprema. Spremanje cijele sesije (folder) i browser semplova dolaze kasnije.
- Metronom bez zvuka (samo vizuelno).

## Sesije (dogovoreno)
- Imenovane sesije u privatnom folderu aplikacije (sessions/<ime>/: pads.json + kopije semplova). Svaka je samostalna.
- "save session": traži ime (isto ime = prepisuje). "load session": lista (ime, broj semplova, datum), Load / Delete (sa potvrdom).
  Učitavanje zaustavlja sve i zamjenjuje semplove, tempo i miks. Ime trenutne sesije piše u gornjoj traci.
- Kasnije: izvoz/uvoz sesije u folder (za kopiju na računar).

## Lijeva traka i ☰ meni (12. 11. 2026)
- Lijevo je uska traka: ☰ (meni), "edit" / "done" (dodjela semplova padovima, žuto dok je uključeno) i dole STOP ALL (uvijek pri ruci).
- ☰ otvara meni s lijeve strane preko ekrana (dodir pored menija ili ✕ ga zatvara). Stavke su običan tekst: SAMPLES (Load samples / Finish editing), SESSION (Save, Export, Load, New, Set list), ABLETON LINK (samo dok je Link uključen: Link audio, Link offset), STATUS (MIDI i audio izlaz).
- Poruke aplikacije (snimljeno, printano, greške) se pojave kao kratka obavijest dolje na sredini i nestanu poslije 5 s. Dok je edit mod uključen, iznad stoji uputstvo.

## Browser biblioteke (dogovoreno)
- Edit mod -> dodir na pad -> "Browse library". Glavni folder(i) sa semplovima se biraju jednom (SAF, dozvola ostaje),
  aplikacija ih skenira i pravi indeks (sprema se u fajl, drži u memoriji), "Rescan" ponovo skenira.
- Raspored (12. 11. 2026, kao Ableton): lijevo stablo foldera (strelica otvara/zatvara, dodir na ime bira folder; "All samples" = sve; broj = semplovi sa podfolderima), desno semplovi izabranog foldera SA podfolderima (ispod imena podfolder). Uzak ekran (< 1000 dp): stablo se otvara dugmetom "Folders". Izabrani folder i otvoreni folderi se pamte dok aplikacija radi. Uklanjanje glavnog foldera: "Remove a library folder…" ispod stabla.
- Pretraga (svi pojmovi u putanji ili imenu) traži u izabranom folderu. Filteri: All / Loops (ima BPM ili "loop" u imenu) / One-shots; "Near N BPM" (±8 od tempa sesije, samo semplovi sa BPM); kategorija iz imena (Drums, Bass, Keys, Vocals, FX). Najviše 1500 redova. "Fill column" za folder sa semplovima (prvih 6 direktno u folderu).
- Indeks: unosi foldera uklonjenog dok je skeniranje trajalo se odbacuju (i pri pokretanju), da nema duplih "mrtvih" semplova.
- Preslušavanje: dodir na red svira, ponovni dodir staje; loop-ovi se ponavljaju. Prekidač: original ili na tempu sesije.
  Preslušavanje ne ovisi o masteru/mute-u i ne ide preko kvantizacije. "Use" dodjeljuje sempl padu.



## Ableton Link (dogovoreno)
- Dugme "LINK" u gornjoj traci tableta (lijevo od TEMPO). Klik = uključeno (zeleno, pokazuje broj drugih učesnika), drugi klik = isključeno. Nije vezano za Launchpad. Pri pokretanju aplikacije uvijek isključeno.
- Link sinhronizuje tempo i fazu takta (quantum = 4 doba = jedan takt) sa drugim aplikacijama/uređajima na istoj Wi-Fi mreži (npr. Ableton Live na računaru). Nema mastera: promjena tempa bilo gdje važi svuda.
- Dok je Link uključen: tempo i faza takta miksera dolaze od Linka. Promjena tempa u aplikaciji (+/- i dijalog, strelice Launchpada) šalje se u Link. Učitavanje sesije i prvi sempl NE mijenjaju tempo (da se ne mijenja tempo drugima). Tempo u Linku može biti decimalan; na ekranu je zaokružen, a semplovi se razvlače na tačan tempo.
- Faza: prvi put i pri razlici > 20 ms mikser skoči na fazu Linka (petlje koje sviraju se pomjeraju za isto toliko); manje razlike se izravnavaju laganim klizanjem (najviše 0.05% brzine). Klipovi i dalje kreću na početku takta, ali takta Linka.
- Kašnjenje izlaza (Oboe `calculateLatencyMillis`) se uračunava automatski. Ručni "offset" (-200..+200 ms, korak 5 ms) u ☰ meniju (ABLETON LINK) dok je Link uključen; pamti se.
- Potrebno: ista Wi-Fi mreža (ruter ili hotspot), dozvole INTERNET + CHANGE_WIFI_MULTICAST_STATE (Android multicast lock se uzima dok je Link uključen).
- Licenca Linka: GPL v2+ (kao Rubber Band), lična upotreba. Kod u `app/src/main/cpp/link-lib/`, omotač `link_sync.*`.
- **Start/stop (kao Ableton)**: STOP kod drugog učesnika = svi klipovi ovdje odmah staju. PLAY kod drugog = takt kreće od 1 (faza Linka). Klip pokrenut ovdje (tablet ili Launchpad, i scena) pokreće transport kod svih na početku sljedećeg takta. STOP ALL ovdje zaustavlja transport kod svih. Učitavanje sesije ne dira transport. Pridruživanje sesiji koja stoji ne zaustavlja klipove.
- **Link Audio**: dok je Link uključen, u ☰ meniju "Link audio: on/off" (pamti se). Uključeno: master miks (bez preslušavanja iz browsera) ide u Link sesiju kao kanal "PadSampler Master" (stereo, 16 bit, poravnato na takt); u Abletonu se bira kao ulaz tracka. Šalje se samo dok neko taj kanal sluša.
- Iskustvo (11. 11. 2026): preko kućnog Wi-Fi-ja Link Audio je imao dropove pri snimanju; preko hotspota na telefonu (računar i tablet na njemu) radi bez problema. Za nastup/snimanje preporuka: hotspot ili zaseban ruter.
- U ☰ meniju dok je Link uključen: i "Link offset" (− / ms / +), ručni pomak kašnjenja.


## Loopovi koji nisu cijeli broj taktova / nepoznat BPM
- Loop čija dužina (pri originalnom BPM-u) nije blizu cijelog broja taktova je "slobodan": razvuče se omjerom BPM-ova, svira do prirodnog kraja pa ispočetka i NE reže se na granici takta (ranije se rezao na zaokruženom broju taktova, pa bi se čuo prekid).
- Ako BPM nije u imenu foldera/fajla, procijeni se iz dužine: 1, 2, 4, 8 ili 16 taktova, 70-180 BPM, najbliže tempu sesije. Za loop (ime sadrži "loop" ili ručno) važi uvijek; za automatski način samo ako je BPM skoro cio broj i najmanje 2 takta. Stari BPM pada se više ne nasljeđuje pri zamjeni sempla. Procijenjena vrijednost se vidi u dijalogu pada i može se ručno ispraviti.


## Izvoz / uvoz sesija
- Load session dijalog: "Export" uz svaku sesiju pravi JEDAN fajl `Ime.padsession` (ZIP: pads.json + samples/; "Sačuvaj kao"). "Import session file..." ga otvara na bilo kom uređaju (tablet ili računar). "Import old folder..." uvozi stare izvoze (folder sa pads.json + samples/, ili folder sa više takvih).
- Uvoz nikad ne prepisuje postojeće: ako ime već postoji dodaje se " (2)", " (3)"... Iz ZIP-a se uzimaju samo pads.json i samples/<fajl>.

## Set lista
- "Set list…" u ☰ meniju otvara prozor: pjesme (sačuvane sesije) po redu, ▲▼ pomjeranje, ✕ uklanjanje, "+" dodaje sesiju (može i više puta).
- ◀ Prev / Next ▶ i dodir na pjesmu je učitavaju (kao load session: sve staje). Trenutna pjesma je istaknuta. Samo ekran, ne Launchpad.
- Pamti se u setlist.json; obrisana sesija ispada iz liste.


## Izgled (dogovoreno, prema dizajn prijedlogu)
- Flat sa dubinom: tamna pozadina, paneli s tankom svijetlom ivicom i mekom sjenom. Od 12. 11. 2026 sve u jednoj boji: mint (vidi "Boje Launchpada"); ranije 8 boja kolona za tabove, tempo, LINK i LED-ove. Minimalan glow (samo pad koji svira).
- Gornja traka ISTA na svim ekranima: logo "PadSampler" (Audiowide), tabovi SESSION/MIXER, ime sesije, 8 osminki takta, LINK, TEMPO (- broj +). Na uskom ekranu (< 1000 dp) kompaktna varijanta bez imena sesije.
- Pad prikazuje ime foldera (običan zapis, skraćeno trotačkom) + LOOP/SHOT oznaku. Stanja: prazan (udubljen), miruje, čeka takt (trepće okvir), svira (puna boja + traka napretka), zaustavlja se (pulsira).
- Fontovi: Audiowide (logo) i Inter (sve ostalo), TTF u `app/src/main/assets/fonts/` (vidi README.txt tamo).
- Dubina bez prelivâ (korisnik 12. 11. 2026, `ui/Components.kt`): prazno (pad, korak, polje piano rolla) = UDUBLJENO (`inset`, unutrašnja sjenka gore); pad sa semplom, dugmad, tipke, kanali miksera = IZDIGNUTO (`raised`, meka sjenka ispod, ravna boja); upaljeno (korak, nota, M/S, pritisnuta tipka) = mali sjaj oko ivice (`glow`). Pritisnuto dugme se utisne. Pad koji svira ostaje sa prelivom i sjajem iznutra (`bevel`, sviđa se korisniku). Potenciometri: sjenka + tijelo osvijetljeno odozgo-lijevo. NE vraćati prelive na prazne padove, korake i mikser. Samo crtanje, bez slika. Svjetlo je SA PLAFONA (uređaj tačno ispod): sve sjenke (izdignuto, udubljeno, pritisnuto, fader, potenciometri) su JEDNAKE sa svih strana, nikad pomjerene na jednu stranu. Logo i natpis PadSampler imaju blagi sjaj. Keys panel: sekcije iste visine, LOUDNESS ispod FILTER (bez skrolanja lijevo-desno na tabletu). BEZ unutrašnje sjenke (inner shadow izbačen): prazna polja (koraci, piano roll, prazni padovi) su ravna sa tankom ivicom; pritisnuto dugme samo malo potamni. Drop shadow skoro neprimjetan, razliven. Uglovi: svuda blaži (rShape ~55 % zadate vrijednosti); polja za note/korake (Keys i Drums) 4 dp (`stepShape`).

## Boje Launchpada i LED logika
- Launchpad koristi Launchpadovu paletu (indeksi u `TrackColors` u `model/Model.kt`: svijetla = svira/treperi/mikser, tamna = miruje). Treperenje i pulsiranje radi sam Launchpad uz MIDI clock.
- Vrijedi verzija `midi/LaunchpadSurface.kt` iz faze 2c (u radnom folderu je sada ista kao na korisnikovom računaru): šalje samo promjene, bez ponovnog slanja i bez čekanja na `isReady`. Zaključavanje je skinuto 11. 11. 2026 (korisnik: problem je bio paljenje padova pri spajanju, sad riješen); fajlovi se smiju mijenjati, ali ostaje: bez RGB SysEx-a i bez periodičnog slanja.
- Pokušaji koji NISU dobri: pravi RGB (SysEx) i ponovno slanje svih LED-ova svake sekunde. Oba izazivaju bljeskanje cijelog Launchpada.
- Pri spajanju (USB) cijela slika se pošalje odmah i još dva puta (1,5 s i 4 s kasnije, `SamplerViewModel.onConnectionChanged`), jer Launchpad koji se tek upalio izgubi dio poruka. To je jednokratno, nije periodično slanje.
- Šalju se SAMO CC brojevi koji imaju LED (91-99, 19/29/.../89; `SamplerViewModel.ledPicture`). Ranije je puno slanje išlo na svih 128 CC-ova, a CC 120-127 su MIDI "channel mode" poruke (123 = All Notes Off): Launchpad je ugasio padove odmah pošto ih je dobio (padovi se nisu palili poslije spajanja dok se ne prebaci Session -> Mixer -> Session).
- Promjena boja se radi samo u `TrackColors` (i `LaunchpadColors` za dugmad). Za isprobavanje boja bez aplikacije postoji zasebna stranica `launchpad-boje.html` (Web MIDI u Chromeu).
- JEDNA zaštitna boja: MINT (12. 11. 2026, korisnik). Aplikacija: sve kolone, Drums, Keys i metar u mint (= Accent 86B9A0). Launchpad: padovi, koraci, Keys tipke, Play = mint 33 (svira / uključeno), 34 (miruje / tamno); bijela za isticanje (playhead, držane note, aktivni ekran). STOP / SOLO / MUTE = tamno crvena 120 / plava 41 (tamna 42) / zlatno žuta 109 (tamna 14); u aplikaciji StopRed A34848 (tamna), SoloBlue, MuteYellow (M žuto, S plavo). Keys na Launchpadu: tonika (izabrani tonalitet) = žuta 109, ostale note skale tamna mint, držane bijele, nota za STEP mod jarka mint. Pastelni red palete NE (isprano na LED-u). Boje se mijenjaju samo u `TrackColors` (model/Model.kt) i `LaunchpadColors` (midi/MidiOut.kt).

## Promjena tempa i razvlačenje (SampleBank)
- Razvlačenje ide paralelno (2 do 4 pozadinske niti, ovisno o broju jezgri). Prvo se obrađuju semplovi koji sviraju ili čekaju start.
- Razvučeni semplovi koji sviraju (ili čekaju start/kraj) zamjenjuju se u Mixeru ZAJEDNO, tek kad su svi spremni, da nikad ne sviraju dva različita tempa istovremeno. Semplovi koji ne sviraju zamjenjuju se čim budu gotovi.
- Izlazni sample rate i veličina buffera se ne forsiraju (Oboe koristi nativni rate uređaja i 2 burst-a); sempl na drugom rateu (npr. 44100 Hz) Mixer pretvara linearnom interpolacijom.

## Dogovoreno 11. 11. 2026
- **Tonalitet na padu** (samo prikaz): iz putanje u biblioteci (folder + ime), inače iz putanje dokumenta i imena fajla; za stare padove bez tonaliteta iz labele (ime foldera) i imena fajla.
- **Link start/stop i Link Audio (slanje mastera)**: urađeno, vidi "Ableton Link".
- **Računar, podešavanja**: dugme za podešavanja (izbor MIDI kontrolera, audio API/uređaj/buffer/rate, pamti se), full screen (F11), ekran se ne gasi dok aplikacija radi.
- **Sesija kao jedan fajl** i **set lista**: urađeno, vidi "Izvoz / uvoz sesija" i "Set lista".

## Studio: Drums i Keys (dogovoreno 11. 11. 2026, radi se u fazama)
- Dva nova ekrana uz Session i Mikser: **DRUMS** (Launchpad dugme Drums, CC 96) i **KEYS** (dugme Keys, CC 97). Gornja traka u aplikaciji ostaje ista na svim ekranima. Metronom (red 7) na Launchpadu se na Drums i Keys ekranu NE prikazuje (11. 11. 2026).
- Snima se **pattern** (note sa pozicijom u taktu), ne zvuk. Dužina 1, 2, 4 ili 8 taktova, 4/4, svira sinhrono sa satom miksera (i Linkom).
- **Print to pad**: pattern se renderuje offline u wav (tačno N taktova na trenutnom BPM-u, rep zadnjih nota se "zamota" na početak, pa je loop bez šava) i stavlja na **selektovani pad** u Session modu kao loop sa `srcBpm` = trenutni BPM. Pattern i zvuk (kit / preset) se čuvaju uz pad u sesiji, da se pad može ponovo otvoriti, izmijeniti i ponovo renderovati.
- **Drums**: kit od 8 zvukova (one-shot semplovi iz biblioteke). Oba načina: step sequencer (1/16) i sviranje uživo sa snimanjem (count-in, kvantizacija, overdub).
- **Keys**: ugrađeni sintisajzer u stilu Minimooga: 3 oscilatora + šum, ladder filter 24 dB (cutoff, rezonanca, envelope, key tracking), 2 ADSR (filter, jačina), glide, mono/poly; potenciometri u aplikaciji, klavijatura (aplikacija i Launchpad u skali) i preseti. Snimanje uživo kao kod Drums.
- Mini MK3 padovi nemaju velocity: jačina se podešava u aplikaciji (po noti / zvuku) ili je fiksna.
- Motor: zajednički C++ (`studio.*`), ide kroz master (i Link Audio); offline render koristi isti kod glasova.
- Launchpad u Studiju ostaje jednostavan (korisnik, 11. 11. 2026): bez dodatnih funkcija (npr. Print sa Launchpada); Print to pad samo u aplikaciji.
- Faze: (1) motor: pattern, drum glasovi, offline render, testovi; (2) Drums ekran + Launchpad (step); (3) Print to pad + čuvanje u sesiji; (4) snimanje uživo; (5) sintisajzer (motor); (6) Keys ekran, preseti, snimanje; (7) dorada (swing, undo...).

### Drums (faza S2, urađeno)
- **Launchpad** (dugme Drums CC 96 otvara, Session CC 95 vraća u Session):
  - bez metronoma (korisnik: Drums se programira, ne svira); redovi 7..4: 32 koraka (2 takta) izabranog zvuka, red 3 prazan, strana bira desna kolona (CC 79, 69, 59, 49 = strane 1..4; svijetle samo za 4 i 8 taktova). Upaljen korak = svijetla boja zvuka, bijelo = korak koji svira, sivo = početak četvrtine.
  - red 2: 8 zvukova (pritisak svira i bira zvuk); red 1: mute po zvuku (zeleno / crveno).
  - red 0: kolona 1 Play/Stop (treperi dok čeka takt), kolone 2..5 dužina 1/2/4/8 taktova (bijelo = izabrano), kolona 8 briše korake izabranog zvuka.
  - Strelice (tempo) rade kao u Sessionu; dugme Stop Solo Mute (CC 19) na Drums ekranu ne radi ništa.
- **Aplikacija** (tab DRUMS): 8 zvukova x 16 koraka (jedan takt, duži pattern ima izbor takta, tačka = takt koji svira). Dodir na ime svira i bira zvuk, "…" učitava zvuk (biblioteka ili fajl) / briše korake / uklanja zvuk, M = mute, traka ispod imena = jačina. Play/Stop, dužina, "clear all".
- Produžavanje patterna (npr. 1 -> 2 takta) ponavlja postojeće taktove ako je novi dio prazan (kao Ableton). Koraci iza dužine se čuvaju.
- Play kreće na sljedećem taktu; STOP ALL zaustavlja i Drums. Sa Linkom Play pokreće i transport kod drugih.
- Kit i pattern su dio sesije (vidi "Print to pad").

### Print to pad i Drums u sesiji (faza S3, urađeno)
- DRUMS ekran, dugme **PRINT TO PAD**: mreža padova (6 x 8 kao Session), izabere se pad (predložen zadnji printani ili prvi prazan; zauzet pad se zamjenjuje, piše šta). Pattern se renderuje offline: tačno N taktova na trenutnom tempu (sa Linkom tačan tempo Linka), rep zadnjih udaraca je na početku (loop bez šava), 24-bit wav na izlaznoj frekvenciji, utišani zvukovi se ne čuju. Ako bi zbir prešao 0 dB, cijeli loop se utiša taman ispod (bez klipovanja).
- Pad dobije loop sa `srcBpm` = tempo printa, labelu "Drums", i pamti pattern + kit (`PadConfig.studio`). U edit modu dijalog tog pada ima **Edit in Drums**: pattern i kit idu u Drums (zamjenjuju trenutni), pa se može izmijeniti i ponovo printati (predložen je isti pad).
- Drums (kit + pattern) su dio sesije: `pads.json` polje "drums", zvukovi su u `samples/` sa semplovima (pa ih nose i spremanje sesije, `.padsession` izvoz i uvoz). Učitavanje sesije učitava i njene Drums; New session ih briše.
- Fajlovi u `samples/` koje ništa ne koristi (zamijenjeni zvukovi, stari printovi) brišu se sami pri spremanju (samo stariji od 2 minute).
- Stari `drums.json` + `drums/` (faza S2) se jednom automatski prebace u sesiju.

### Sintisajzer (faza S5, motor urađen; ekran u S6)
- `app/src/main/cpp/synth.*`, instrument dijela Keys u Studiju (svira kroz master, Print to pad radi isto kao Drums).
- 3 oscilatora: trougao, saw, kvadrat, širok puls (30 %), uzak puls (12 %), PolyBLEP; oktava -2..+2 (32'..2'), detune osc 2 i 3 (-12..+12 polutonova, decimalno); šum.
- Filter: Moog ladder 24 dB/okt, ZDF/TPT (Zavalishin), 2x oversampling, tanh na ulazu; cutoff 20 Hz..18 kHz, rezonanca 0..1 (pri 1 samooscilira), envelope amount do +5 oktava, key tracking 0..1. Stabilan za sve kombinacije (test).
- 2 ADSR (filter, jačina), glide 0..2 s, glasovi: 1 = mono legato (zadnja nota ima prednost, kao Minimoog), 2..8 = poly (krađa: prvo glas koji se gasi, inače najstariji), volume.
- Parametri: `SynthParam` u synth.h (JNI `synthSetParam/synthParam`, redoslijed isti u Kotlinu).
- Snimanje uživo (faza S4) je preskočeno: ritam i melodija se programiraju.

### Odvojen transport (12. 11. 2026)
- Drums i Keys imaju svaki svoj Play/Stop (C++ `Studio::play(part)` / `stop(part)` / `transport(part)`, part -1 = svi). Play na DRUMS svira samo bubanj, na KEYS samo Keys.
- Promjena ekrana NE zaustavlja pattern (korisnik, 12. 11. 2026): Drums i Keys sviraju dok ih korisnik sam ne zaustavi (Play/Stop na njihovom ekranu ili STOP ALL); mogu svirati i zajedno. Test: `studio_test` "odvojen transport".

### Keys ekran (faza S6)
- Aplikacija (tab KEYS): Play/Stop (samo Keys; Drums ima svoj Play, 12. 11. 2026), dužina 1/2/4/8 taktova i izbor takta, clear all, PRINT TO PAD (izbor pada + kategorija: Bass, Lead, Keys, Pad, Brass, Synth, FX = labela pada i folder u izvozu).
- Panel sintisajzera: PRESET (ime, ◀ ▶, … = lista ugrađenih i svojih, snimanje i brisanje svojih), GLIDE, VOICES, VOLUME; OSCILLATORS 1-3 (WAVE, OCT, DETUNE); MIXER (nivoi osc 1-3, NOISE); FILTER (CUTOFF, EMPHASIS, CONTOUR, KEY TRK + ADSR filtera); LOUDNESS (ADSR). Potenciometar: povuci gore/dolje, dupli dodir = početna vrijednost, dodir na WAVE = sljedeći talas.
- Ugrađeni preseti: Init, Classic Bass, Sub Bass, Acid Bass, Fat Lead, Brass, Warm Pad, Pluck Keys, Noise Sweep FX. Svoji preseti: `synth_presets.json` u folderu aplikacije (važe za sve sesije, ne idu u izvoz sesije).
- KEY (tonika), skala (Major, Minor, Dorian, Phrygian, Mixolydian, Harm. minor, Penta major/minor, Blues, Chromatic), OCTAVE −/+ (prikaz od tonike, Ableton imena C3 = 60), NOTE = dužina nove note (1/16, 1/8, 1/4, 1/2, 1 takt).
- Piano roll: dvije oktave od tonike, samo note skale (od najviše); lijevo klavijatura (svira dok se drži), desno 16 koraka izabranog takta. Dodir: nova nota izabrane dužine (ne preklapa sljedeću notu iste visine) ili briše notu koja je tu. Note van prikaza ostaju (piše koliko ih je).
- Launchpad (CC 97 bijelo na Keys ekranu): redovi 1..7 klavijatura u skali (svaki red 3 stepena skale više, hromatski 5 polutonova; tonika plava 45, ostale note tamno plave 43, nota koja svira bijela); svira dok se drži. Red 0: kolona 0 Play/Stop (kao Drums), 1 oktava −, 2 oktava + (sivo, ugašeno na granici). Bez metronoma, desna kolona i ostalo ugašeno.
- Launchpad STEP mod (red 0, kolona 7: žuto = STEP, sivo = PLAY): redovi 7..4 = 32 koraka izabrane note (kao Drums; početak note plavo 45, nastavak 43, playhead bijelo, četvrtine sivo), pritisak dodaje notu dužine NOTE iz aplikacije ili briše postojeću; redovi 3..1 = klavijatura u skali (svira i bira notu; izabrana zelena 21; bira se i klavijaturom u aplikaciji, tamo je zeleno uokvirena). Desna kolona = strane za 4 i 8 taktova. Mod i izabrana nota se ne spremaju.
- Aplikacija: panel potenciometara se sklapa (hide / show knobs, ostaje traka sa presetom); piano roll ima red najmanje 30 dp i skroluje se kad ne stane; panel i red sa skalom se skroluju vodoravno u uskom prozoru.

### MIDI klavijatura (11. 11. 2026)
- Svaki MIDI ulaz koji nije Launchpad (Novation Launchkey/FLkey JESU klavijature) svira Keys sintisajzer, na SVIM ekranima. Izbor na KEYS ekranu: MIDI IN = Auto (svi osim Launchpada), jedan uređaj ili Off; pamti se (postavka "midiKeyboard"). Na računaru se ne otvara uređaj izabran kao Launchpad kontroler (Windows dozvoljava jedno otvaranje).
- Svi kanali. Note On/Off -> Keys (velocity = jačina, linearno), u Launchpad STEP modu nota postaje izabrana nota. Pitch bend ±2 polutona, CC 1 (mod točak) otvara filter do +3 oktave, CC 120/123 gase note. Pitch bend i mod se ne spremaju i ne utiču na Print.
- Android: `AndroidKeyboard` (android.media.midi, samo USB class compliant, bez "DAW" portova); računar: `DesktopKeyboard` (Java MIDI, provjera uređaja svake sekunde). Zajedničko: `midi/MidiKeyboard.kt` (interfejs, `KeyboardParser` sa running status), `Platform.createKeyboard`, `Platform.getString/putString`.
- MIDI LEARN (KEYS panel, dugme LEARN pored VOLUME): žuta traka iznad panela; dodir na potenciometar ga označi (žuto), prvi CC sa klavijature se veže za njega (jedan CC = jedan potenciometar, i obrnuto). Ispod potenciometra piše "CC n". "clear knob", "clear all" (za trenutni uređaj), "done". Mapiranje je po uređaju (ime), u `midi_map.json` u folderu aplikacije (sve sesije), `data/MidiMapStore`. CC 0..127 ide na položaj potenciometra (log za Hz i vrijeme). Mapiran CC ima prednost nad podrazumijevanim (npr. CC 1 = mod). Sklapanje panela gasi LEARN.
- Print: kao Drums (tačno N taktova, loop bez šava, 24-bit wav), ime "<Kategorija> S1-T3 2 bars 120 BPM (broj).wav". Pad pamti zvuk i note ("Edit in Keys" u dijalogu pada). Stanje Keys je dio sesije (pads.json "keys").

### Fajlovi printova i izvoz po folderima (11. 11. 2026)
- Printovi su u radnom folderu `samples/` samo dok ih neki pad (ili kit) koristi; nekorišteni se brišu sami (~2 min). Veličina: 24-bit stereo, ~0,6 MB po taktu na 120 BPM.
- Ime printa je čitljivo: "Drums S1-T3 4 bars 120 BPM (broj).wav".
- `.padsession` izvoz: `samples/<labela pada>/<fajl>` (Drums, Bass, Vocal...), zvukovi Drums kitova u `samples/Drums/Kit/`. Uvoz prihvata i stari ravni oblik (`samples/<fajl>`); u aplikaciji su svi fajlovi u jednom folderu.
- "Export session…" u ☰ meniju: ime (trenutno ime sesije), snimi sesiju pod tim imenom, pa izbor foldera i izvoz kao ispod.
- Export (Load session -> Export) pita za FOLDER i pravi `<folder>/<ime sesije>/` sa `<ime sesije>.padsession` (projekt za uvoz) i semplovima kao običnim fajlovima po folderima labela (`Drums/`, `Drums/Kit/`, `Bass/`, `Lead/`...). Ponovni izvoz u isti folder prepisuje fajlove istog imena. Uvoz: "Import session file..." -> `.padsession`.
- Keys printovi dobijaju kategoriju (npr. Bass, Lead, Pad) kao labelu, pa i svoj folder u izvozu.

### Swing, jačina, undo (faza S7, 12. 11. 2026)
- SWING 0-100 % (korak 5 %), posebno za Drums i Keys: svaka druga šesnaestina kasni, 100 % = pola šesnaestine (12 od 24 tickova). Računa se u Kotlinu pri slanju patterna (`DrumLayout.swingTick`), pa važi i za Print to pad. Keys: pomjera se i kraj note koji pada na neparnu šesnaestinu.
- Jačina po koraku/noti 0,05-1 (nova nota = 1). Traka jačine ispod mreže (Drums: izabrani zvuk, prikazani takt; Keys: sve note koje počinju na koraku). Dodir ili povlačenje gore/dolje. Tiši korak/nota je blijeđi u mreži. Launchpad se ne mijenja.
- JSON (unazad kompatibilno): Drums `swing` i `vel` (po traci `[[korak, jačina]]`, samo jačine < 1); Keys `swing` i opcioni 4. broj u noti `[korak, dužina, nota, jačina]`.
- Undo/redo (↶ ↷; računar i Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z na DRUMS i KEYS ekranu): koraci/note, jačine, swing, dužina, mute (Drums), brisanje, i izmjene sa Launchpada. Ne ulaze: zvukovi kita i jačina trake, potenciometri sintisajzera, skala/oktava. Povlačenje (jačina, swing) je jedan korak; istorija do 100 koraka, briše se kad se učita sesija ili "Edit in Drums/Keys".
- Mjesto: Drums ispod mreže lijevo (undo, swing) + traka jačine; Keys u redu sa skalom (početak) + traka jačine ispod piano rolla.
- Ugrađeni preseti Keys (12. 11. 2026): 27, po grupama INIT, BASS, LEAD, PAD, KEYS, FX (`SynthPreset.category`); dijalog ih prikazuje pod naslovima grupa, ◀ ▶ ide redom.

### Launchpad dugmad u DRUMS / KEYS (12. 11. 2026)
- Desna kolona dolje CC 19 = Play/Stop dijela na kojem si (Drums ili Keys); LED: mint svira, treperi čeka takt, tamna mint stoji. Pad Play u donjem redu ostaje. (Do 1.8.2 je Play bio na strelici gore; sad je strelica gore opet tempo +10.)
- KEYS: CC 29 (iznad Play) = REC; LED tamno crvena, treperi tokom odbrojavanja, pulsira dok snima.
- Strelica DOLJE (CC 92) na KEYS = potenciometri prikaži/sakrij (bijela = otvoreno, siva = sakriveno; zatvaranje gasi MIDI LEARN kao "hide"). Na DRUMS ne radi ništa.
- Gore/lijevo/desno (91/93/94) su tempo +10/-1/+1 na svim ekranima; dolje (92) je tempo -10 osim na DRUMS (ništa) i KEYS (potenciometri).
- DRUMS na Launchpadu: izabrani zvuk (red 2) žut, ostali tamna mint. Dugme PRINT TO PAD u aplikaciji je mint.

### Snimanje uživo u KEYS (12. 11. 2026)
- Dugme ● REC (pored PLAY) i Launchpad CC 29. Ako Keys ne svira: metronom + odbrojavanje (do početka sljedećeg takta, pa jedan puni takt), pa pattern krene i snima. Ako svira: snima odmah. Ponovni REC završava snimanje (pattern svira dalje); Stop Keysa ili STOP ALL ga takođe završava.
- Snima sa svih izvora (MIDI klavijatura, Launchpad, klavijatura u aplikaciji): visina, jačina (velocity), dužina (držanje). Dosnimavanje: pattern se vrti i svaki prolaz dodaje note; ista visina na istom koraku se zamijeni, nota koja prekriva početak nove se skrati.
- REC GRID 1/16 ili 1/8: početak note se poravna; dužina u šesnaestinama (najmanje 1). Piano roll ostaje u šesnaestinama (bez finije mreže).
- Kašnjenje izlaza se oduzima (pozicija motora minus bafer izlaza), da nota padne gdje je čuta.
- Jedan prolaz snimanja (od REC do kraja) = jedan korak za undo.
- Metronom (C++ `Studio::setClick`, tik na četvrtine, viši na prvoj) svira tokom odbrojavanja i snimanja, samo u master (nema ga u Print to pad).
- Zamjena patterna dok svira (svaka snimljena nota) pušta samo note koje je pattern upalio (`Part::patNotes`), ne i note koje korisnik drži. Test: `studio_test` (metronom, nota uživo preživi zamjenu).

### Biranje i pomjeranje nota u KEYS (12. 11. 2026, samo u aplikaciji)
- Dugme SELECT (red sa undo/swing) uključi mod biranja; dok je uključen, tap po polju ne dodaje/briše note. Tipke lijevo i dalje sviraju.
- Prevuci po praznom polju = pravougaonik izbora (note čiji je početak unutra). Tap na notu = dodaj/izbaci iz izbora. Tap na prazno = poništi izbor.
- Prevuci notu = pomjeri cijeli izbor (lijevo/desno po koracima, gore/dolje po redovima skale); note se upišu kad se pusti. Nota istog tona na istom koraku koju pomjerena prekrije se briše.
- ALL (sve note), ◀ ▶ (korak), |◀ to bar (izbor se poravna na najbližu liniju takta), DEL (briše izbor). Svako pomjeranje je korak za undo (◀ ▶ uzastopno u 1 s = jedan).
- Izabrane note imaju žuti okvir. Logika pomjeranja: `KeysLayout.moveNotes` (ista za prikaz tokom vučenja i za upis).

### IMPORT MIDI u KEYS (13. 11. 2026)
- Dugme IMPORT MIDI (gornji red KEYS) otvara izbor .mid fajla (Android: SAF, svi fajlovi; računar: filter .mid/.midi). Čitač je vlastiti (`data/MidiFile.kt`, SMF format 0/1, bez biblioteke): note po (traka, kanal); kanal 10 je označen "(drums)".
- Više instrumenata u fajlu -> dijalog za izbor dijela (ime, broj nota, raspon). Jedan dio -> uvozi se odmah.
- Uvoz (`KeysMachine.importMidi`) ZAMJENJUJE note patterna (jedan korak za undo): poravnanje na šesnaestine, prazni taktovi na početku se preskoče, dužina = najmanje od 1/2/4/8 taktova koja stane (duže se odsiječe na 8). Jačina iz MIDI-ja. Tempo i takt iz fajla se ne koriste (4/4, tempo sesije).
- Nota van izabrane skale -> skala postaje Chromatic; oktava piano rolla se izabere da se vidi što više nota. Zvuk (preset) se ne mijenja.
- Poruka: "Imported N notes, X bars" (+ "rest cut off" ako je dio odsječen).
- Poravnanje i dužina su zajednički za Keys i Drums (`MidiFile.toGrid`). Sljedeće: ugrađena bubnjarska mašina.

### IMPORT MIDI u DRUMS (13. 11. 2026)
- Dugme IMPORT MIDI u gornjem redu DRUMS (red LENGTH/BAR se sad skroluje kao u Keys). Isti čitač i izbor fajla kao u Keys.
- Izbor dijela: dijelovi na kanalu 10 ("drums") su prvi; ako je tačno jedan takav, uzme se odmah bez dijaloga. Dio sa drugog kanala se takođe čita po GM rasporedu (AI često piše bubnjeve na kanal 1).
- General MIDI -> 8 vrsta (`DrumLayout.gmKind`): Kick (35, 36), Snare (38, 40), Clap/Rim (37, 39), Closed HH (42, 44), Open HH (46), Tom (41, 43, 45, 47, 48, 50), Cymbal (49, 51, 52, 53, 55, 57, 59), Perc (sve ostalo).
- Vrsta -> traka (`DrumLayout.lanesForKinds`): prvo traka čije ime zvuka odgovara (kick/bd, snare/sd, clap/rim, open/oh, hat/hh, tom, crash/ride/cym, perc/shaker/conga...), pa podrazumijevana (Kick = 1 ... Perc = 8), pa prva slobodna. Svaka traka najviše jedna vrsta.
- Uvoz ZAMJENJUJE korake i jačine svih traka (jedan undo); zvukovi, mute i jačine traka ostaju. Dva udarca na istom mjestu u traci -> jači.
- Poruka: "Imported N hits, X bars" + "No sound yet: Kick → 1, ..." za vrste koje su pale na traku bez zvuka.

### Ugrađena bubnjarska mašina, korak 3a: kitovi (13. 11. 2026)
- Zvukovi se SINTETIZUJU u Kotlinu (`studio/DrumSynth.kt`, offline, ne na audio niti) u kratak wav (48 kHz, 24-bit) u folderu semplova, pa ih Drums svira kao svaki sempl. Audio motor nije mijenjan; sesija, `.padsession` i Print to pad rade bez izmjena.
- Vrste (`DrumSynthKind`): Kick, Snare, Clap, Rim, Closed HH, Open HH, Tom, Cymbal, Cowbell, Zap, Shaker. Podešavanja (`DrumSynthSpec`, sve 0..1): tune, decay, tone, snap, grit (lo-fi). Šum ima fiksno sjeme (isti zvuk za ista podešavanja). Jačine po vrsti (hi-hat/činela tiši od kicka).
- `DrumSound.synth` pamti podešavanja (pads.json: "synth" uz zvuk trake); null = fajl korisnika.
- Kitovi (`DrumKits`): 808, 909, Electro, Lo-Fi; 8 zvukova u rasporedu IMPORT MIDI (Kick, Snare, Clap/Rim, Closed HH, Open HH, Tom, Cymbal, Perc = Cowbell/Rim/Zap/Shaker).
- Dugme KIT ▾ u gornjem redu DRUMS (natpis "KIT: 808" kad su sve trake iz tog kita). Učitavanje kita zamijeni zvukove svih 8 traka; koraci i jačine traka ostaju.
- Prazan Drums (nova sesija, prvo pokretanje, sesija bez ijednog zvuka) automatski dobije 808.


### Ugrađena bubnjarska mašina, korak 3b: zvuk po traci (13. 11. 2026)
- Dijalog zvuka trake (tap na ime trake): BUILT-IN SOUND = izbor vrste (11 vrsta) + potenciometri TUNE, DECAY, TONE, SNAP, GRIT (0-100; dupli dodir = početno: 50, GRIT 0) i ▶ (preslušaj). Ispod: YOUR OWN SAMPLE (biblioteka, fajl), Clear steps, Remove sound.
- Dok se potenciometar vuče, zvuk se računa i ide SAMO u motor (`DrumMachine.previewSynth`, bez fajla); kad se pusti (ili se izabere vrsta), upiše se wav i stanje (`setSynth`) i zvuk se jednom odsvira. ViewModel (`editDrumSynth`) radi u jednoj pozadinskoj korutini: zastarjeli pregledi se preskaču, svaki upis se izvrši.
- Izmijenjen zvuk dobije ime vrste (npr. "Kick"); KIT dugme tada više ne piše ime kita.
- Potenciometar je zajednički (`ui/KnobView.kt`: `drawKnob`, `SimpleKnob`); Keys koristi isto crtanje.

### PAN na mikseru (13. 11. 2026)
- Svaki kanal miksera ima potenciometar PAN ispod imena (isti dizajn kao Keys; luk od sredine). Prikaz: C, L1-L100, R1-R100. Dupli dodir = sredina; vrijednost blizu sredine (< 2) se zaokruži na sredinu.
- Zakon: balance (kao Ableton za stereo izvore): sredina = oba kanala nepromijenjena; pan u stranu stiša suprotni kanal (kosinus), skroz = suprotni kanal 0. Izglađeno po uzorku (bez klikova). C++ `Mixer::setTrackPan` (komanda kroz red), JNI `setTrackPan`. Test: `mixer_test` 9b.
- Pan se pamti u sesiji (pads.json "pans", -1..1); New session = sve na sredinu. Pan nije na Launchpadu.

### Telefon i mali prozor (13. 11. 2026)
- Uski ekran (širina < 1000 dp: telefon u landscape-u, mali prozor na računaru): kanal miksera je kompaktan (bez PAN-a: pan podešen na tabletu/računaru i sačuvan u sesiji i dalje važi; tanji metar, slajder zauzme ostatak širine kanala, manji M/S), master uži (76 dp). Tablet i full screen nepromijenjeni.
- Redovi koji se skroluju lijevo-desno (`Modifier.hScroll`, ui/HScroll.kt): prstom, a mišem točkićem (gore/dolje = lijevo/desno) i povlačenjem; povlačenje ne okine dugme.
- Telefon: MASTER kanal se prikazuje samo na SESSION i MIXER (DRUMS i KEYS trebaju širinu).
- Telefon: gornja traka DRUMS i KEYS (PLAY, REC, LENGTH...) je niža (`LocalDense`: manji vertikalni padding dugmadi), pa mreža i piano roll dobiju visinu.
- DRUMS na telefonu: gore PLAY, LENGTH/BAR, KIT ▾, VEL, PRINT i meni ⋯ (IMPORT MIDI, clear all). Trake nisu niže od 30 dp i skroluju se gore/dolje. VEL otvara panel PREKO donjeg dijela mreže (mreža se ne skuplja; dno se može izvući iznad panela): swing, jačina udarca, bez undo/redo i traku jačine izabranog zvuka (u redovima je sakrivena). Ime zvuka uže (100 dp), pa su koraci veći.
- KEYS na telefonu: gore PLAY, REC, LENGTH/BAR, VEL, PRINT i meni ⋯ (IMPORT MIDI, clear all). VEL otvara VELOCITY preko donjeg dijela piano rolla. Red sa zvukom (kraći: bez "SOUND", "knobs ▾") i traka swing/SELECT/KEY... (niži, bez undo/redo) skroluju se ZAJEDNO sa piano rollom (jedan vertikalni skrol); otvoren panel zauzme sav prostor ispod i skroluje se gore/dolje, piano roll se vrati sa "hide". Tipke klavijature uže (48 dp); redovi piano rolla najmanje 30 dp, ostali se skroluju.


### KEYS: tab ARP / FX i arpeggiator (13. 11. 2026)
- Panel potenciometara ima tabove desno: SYNTH (sve kao prije) i ARP / FX (tačka na tabu = arp uključen); ispod "hide". Visina panela ostaje ista pri promjeni taba. Sklopljen panel ima dugme ARP (brzo uključi/isključi).
- Arpeggiator (C++ `Arpeggiator` u synth.*): između ulaza (uživo: MIDI klavijatura, Launchpad, klavijatura u aplikaciji; i note patterna) i sintisajzera. Studio ga prije svakog dijela zvuka sinhronizuje sa globalnim taktom (apsolutna pozicija u dobama), pa su note na mreži; offline render (Print to pad) isto (neprekidna pozicija kroz oba prolaza).
- Parametri (dio parametara sintisajzera, id 27-32; MIDI LEARN radi): ARP OFF/ON, MODE (UP, DOWN, UP/DN, RANDOM, PLAYED), RATE (1/4, 1/8, 1/8T, 1/16, 1/16T, 1/32), OCTAVES 1-4, GATE 5-100 %, HOLD OFF/ON. Izbori se mijenjaju tapom na potenciometar ili povlačenjem.
- Start: nova nota kad arp ne svira kreće na sljedećem koraku; ako je pritisnuta do 1/4 koraka poslije koraka, kreće odmah (sviranje uživo malo kasni). HOLD: svira i kad se tipke puste; novi akord (sve tipke bile puštene) zamijeni stari. Stop Keysa / STOP ALL pušta sve.
- Učitavanje preseta NE mijenja arp ni efekte (`SynthParams.KEEP_ON_PRESET`, id 27-39). Arp i efekti se pamte u sesiji i u korisničkom presetu (parametri).
- REC snima note koje su svirane (akorde), ne note arpa: arp ih svira i iz patterna, pa se RATE/MODE mogu mijenjati poslije.
- Test: `synth_test` testArp (mreža, gate, down + oktave, start odmah, hold, offline).

### KEYS: efekti (13. 11. 2026)
- C++ `KeysFx` (synth.*) poslije arpa i sintisajzera, redom: DRIVE (tanh, kompenzacija glasnoće) -> CHORUS (modulisano kašnjenje 7-17 ms, 0,45 Hz, L/R suprotna faza) -> DELAY (vezan za tempo: 1/16, 1/8, 1/8., 1/4, 1/4., 1/2; ping-pong, low-pass 4,5 kHz u povratnoj vezi; vrijeme klizi ~80 ms, bez klikova) -> REVERB (Freeverb: 8 comb + 4 allpass po kanalu; SIZE = veličina i prigušenje).
- Parametri (id 33-39, dio parametara sintisajzera; MIDI LEARN radi): DRIVE, CHORUS, TIME, FEEDBACK (0-90 %), delay MIX, SIZE, reverb MIX. Sve izglađeno (~20 ms). Efekat sa MIX 0 se ne računa (štedi procesor), a kad se uključi kreće od tišine.
- Baferi se alociraju u konstruktoru (do 96 kHz; delay do 2,1 s); audio nit ne alocira. Rep teče i kad ništa ne svira (Studio renderuje Keys uvijek).
- Print to pad: efekti se renderuju offline (vlastita kopija), rep iz prvog prolaza se čuje na početku drugog (loop bez šava).
- UI: tab ARP / FX: sekcije ARPEGGIATOR, COLOR (DRIVE, CHORUS), DELAY, REVERB, po dva reda potenciometara. Tačka na tabu = arp ili neki efekat uključen. Dugmad SYNTH, ARP / FX i hide su iste veličine.
- LENGTH (Drums i Keys) je padajući meni "LENGTH 2 bars ▾".
- Test: `synth_test` testFx (bypass bez promjene, delay tačno na tempu, sve uključeno stabilno, rep reverba, offline).

### About i licence (16. 11. 2026)
- ☰ -> ABOUT -> "About PadSampler": verzija (appVersion), © 2026 Nerd Instruments, GPL-3.0-or-later napomena (bez garancije), linkovi na izvorni kod (github.com/rasxxb-hash/padsampler) i sajt (nerd-instruments.net; otvara browser preko `LocalUriHandler`), puni tekst GPL v3, spisak tuđih biblioteka i fontova (dodir = tekst licence) i napomena o zaštićenim imenima.
- Tekstovi dolaze iz korijena projekta (`LICENSE`, `THIRD_PARTY_NOTICES.md`, `licenses/`) i kopiraju se pri buildu u Java resurse `/legal/` (`copyLegal` u app i desktop-app build.gradle.kts); spisak u `data/Legal.kt` mora odgovarati `THIRD_PARTY_NOTICES.md`.

### Google Play: Android 16 i baterija (16. 11. 2026)
- `targetSdk` 36 (Play traži od 31. 8. 2026). Manifest: `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` drži landscape na Android 16 tabletima (privremeni izuzetak; za targetSdk 37 raspored mora raditi i uspravno).
- Bez dozvole `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play je ne dozvoljava muzičkim aplikacijama; foreground servis je dovoljan). Umjesto direktnog zahtjeva: ☰ -> STATUS pokazuje "Battery: unrestricted" ili žuto "Battery saver is on" (dodir otvara stranicu aplikacije u podešavanjima: Xiaomi "Battery saver" -> No restrictions). Dok je štednja uključena, 4 s poslije pokretanja kratka poruka. `UiPlatform.batteryRestricted/openBatterySettings` (računar: null, nema stavke).
