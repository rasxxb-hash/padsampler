# PadSampler OS (USB)

Mali Linux (Debian 13) koji se pokreće sa USB-a i odmah otvara PadSampler. Nema desktopa ni logina.

## Raspored USB-a

| Particija | Sistem | Šta je na njoj |
|---|---|---|
| 1 `biosboot` | - | GRUB za stare (BIOS) računare |
| 2 `PADSAMPLER` | FAT32 | Sistem: kernel, initrd, `live/filesystem.squashfs` (samo za čitanje), GRUB (UEFI + Secure Boot preko shim-a) |
| 3 `persistence` | ext4 | `/home/padsampler`: podešavanja, sesije, set liste (`~/.padsampler`) |
| 4 `PADSAMPLES` | exFAT | Semplovi. Pravi se pri PRVOM pokretanju i zauzme ostatak USB-a. Vidi se i na Windowsu. U PadSampleru: `~/Samples` |

Sistem (squashfs) se nikad ne mijenja, pa nestanak struje usred svirke ne može pokvariti sistem.

## Pravljenje

Sve radi GitHub Actions (`.github/workflows/linux.yml`, posao `image-x86_64`):

1. `distro/build-rootfs.sh` (u Docker kontejneru `debian:trixie`): sistem -> `out/live/{vmlinuz,initrd.img,filesystem.squashfs}` + potpisani shim/GRUB.
2. `distro/make-image.sh` (na runneru, root): GPT image sa 3 particije, GRUB za BIOS i UEFI.
3. `distro/test-qemu.sh`: pokrene image u QEMU (UEFI), snimi ekran i log (artefakt `qemu-test`).

Rezultat: `padsampler-os-x86_64.img.xz`. Upisati na USB (8 GB ili više) sa balenaEtcher ili Rufus (DD mod).

## Pokretanje

- Meni GRUB-a: drži **Shift** ili **Esc** pri pokretanju. Opcije: normalno, "safe graphics" (nomodeset), bez sačuvanih podešavanja.
- Gašenje: dugme za napajanje (kratko).
- Zvuk: ALSA direktno (bez PulseAudio/PipeWire), izbor izlaza u ⚙ u aplikaciji.
- Jačina: sistemski mikser svake kartice ide na 100% pri pokretanju i kad se uključi USB kartica
  (`padsampler-volume-init`, udev `91-padsampler-volume.rules`); jačinu reguliše master u PadSampleru.
  Fn + jačina na laptopu mijenja sistemsku jačinu (`xbindkeys`, `padsampler-volume`).
- Kiosk mod (`PADSAMPLER_KIOSK=1` u `padsampler-session`): aplikacija je uvijek full screen, u ⚙ nema izbora
  prozora ni "Check for updates". Windows verzija to nikad ne uključuje.
