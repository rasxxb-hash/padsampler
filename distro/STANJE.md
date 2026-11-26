# PadSampler OS: stanje projekta

> **Za Claude (i za mene):** ovo je dnevnik Linux/USB verzije. Pročitaj ga PRIJE rada na grani `linux`.
> Poslije SVAKE izmjene (kod, build, test na hardveru, odluka) dopuni ga i commituj zajedno sa izmjenom,
> da se može nastaviti na bilo kom računaru (posao, kuća...). Najnovije ide gore u "Dnevnik".

## Šta je ovo

Mali Linux (Debian 13) koji se pokreće sa USB-a i odmah otvara PadSampler preko cijelog ekrana, bez desktopa.
Samo za PC (x86_64): laptop, stoni, mini PC. Detalji rasporeda USB-a i kako radi: [README.md](README.md).

## Pravila (dogovoreno)

- Radi se SAMO na grani **`linux`**. `main`, Android i Windows verzija se NE diraju (glavni projekat je na drugom računaru).
- Izmjene u zajedničkom desktop kodu (`desktop-app/`) samo ako ne mijenjaju Windows ponašanje
  (npr. kiosk mod je uključen samo preko `PADSAMPLER_KIOSK=1`, koji postavlja samo USB sistem).
- **Nema Raspberry Pi / arm64** (Pi 3B+ preslab; odluka 26. 11. 2026).
- Samo pokretanje sa USB-a. **Nema instalacije na disk računara** (odluka 25. 11. 2026).
- Push na `linux` je dozvoljen; to pokreće build na GitHub Actions (izmjene samo u `.md` fajlovima ne pokreću build).

## Kako se pravi i preuzima image

- Build: GitHub Actions, workflow `.github/workflows/linux.yml` (posao `build` = aplikacija, `image-x86_64` = USB image + test u QEMU).
  Repo je javan, Actions je besplatan.
- Spisak buildova: https://github.com/rasxxb-hash/padsampler/actions
- Artefakt `padsampler-os-x86_64` = `.img.xz` (oko 420 MB). Upis: balenaEtcher ili Rufus (DD mod), USB od 4 GB+.
  **Upis briše sve na USB-u** (i semplove i sesije): prije toga prekopirati particiju `PADSAMPLES`.
- `gh` CLI: iza proxyja (posao) `gh` mora vidjeti varijablu `HTTPS_PROXY` u prozoru u kojem se pokreće (kod kuće ne treba).
  Stari artefakti se brišu sa `gh api -X DELETE repos/rasxxb-hash/padsampler/actions/artifacts/<id>`.

## Šta radi (provjereno)

- Na laptopu (prvi image, 25. 11. 2026): pokretanje sa USB-a, aplikacija, **Launchpad Mini MK3** i sve funkcije aplikacije.
- QEMU test (UEFI + BIOS) prolazi na svakom buildu.

## Otvoreno / sljedeće

- [ ] Probati image iz builda [45810362442](https://github.com/rasxxb-hash/padsampler/actions/runs/45810362442) na laptopu:
      - kiosk mod (uvijek full screen, F11 ne radi, u ⚙ nema izbora prozora ni "Check for updates")
      - jačina: sistem na 100%, reguliše se master faderom; Fn + jačina/mute na laptopu
      - slušalice: prelazak sa zvučnika i da nije preglasno (ako jeste: 90% umjesto 100% u `padsampler-volume-init`)
- [ ] MIDI klavijatura (osim Launchpada) još nije probana na USB sistemu.
- [ ] USB zvučna kartica još nije probana na USB sistemu.

## Dnevnik

### 26. 11. 2026
- Kiosk mod (`PADSAMPLER_KIOSK=1` u `padsampler-session`): uvijek full screen, bez F11, bez izbora prozora i "Check for updates" u ⚙.
- Jačina: `padsampler-volume-init` + udev `91-padsampler-volume.rules` (svi izlazi svake ALSA kartice na 100%, i USB kartica uključena kasnije; ulazi se ne diraju).
  `snd_hda_intel power_save=0`. Fn tasteri preko `xbindkeys` + `padsampler-volume`.
- Izbačen arm64 iz builda (bez Raspberry Pi-ja).
- Obrisani artefakti starih buildova; ostao samo image iz 45810362442.
- Dodat ovaj fajl.

### 25. 11. 2026
- Grana `linux`: aplikacija za Linux (RtAudio ALSA/JACK/Pulse, `libpadsampler.so`, ALSA imena MIDI portova `portName()`, baterija iz `/sys`).
- `distro/`: Debian 13 rootfs (`build-rootfs.sh`), USB image GPT BIOS+UEFI sa potpisanim shim-om (`make-image.sh`),
  exFAT particija `PADSAMPLES` pri prvom pokretanju, QEMU test (`test-qemu.sh`).
- Popravka: `chown $USER:$USER` prije `xz` (xz nije mogao postaviti grupu root).
- Test na laptopu: radi, Launchpad radi; tražene izmjene: uvijek full screen, bez update dugmeta, tiho (jačina).
