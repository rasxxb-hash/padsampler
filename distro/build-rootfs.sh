#!/usr/bin/env bash
# Sistem za PadSampler OS: Debian 13 (trixie) -> squashfs + kernel + initrd (live-boot) + potpisani shim/GRUB.
# Pokreće se kao root u kontejneru debian:trixie:
#   bash distro/build-rootfs.sh <PadSampler-linux-ARCH.tar.gz> <izlazni folder>
set -euo pipefail

APP_TAR=$(readlink -f "$1")
OUT=$(readlink -f "$2")
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=/tmp/padsampler-rootfs
MIRROR=http://deb.debian.org/debian
SUITE=trixie

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends mmdebstrap squashfs-tools ca-certificates

PKGS=(
  # osnova + live-boot (squashfs sistem, persistence particija)
  systemd-sysv udev kmod dbus libpam-systemd login passwd
  initramfs-tools live-boot live-boot-initramfs-tools linux-image-amd64
  util-linux gdisk exfatprogs dosfstools e2fsprogs procps less nano
  # grafika: Xorg bez desktopa (modesetting je u xserver-xorg-core)
  xserver-xorg-core xserver-xorg-input-libinput xserver-xorg-video-fbdev xinit x11-xserver-utils
  libgl1 libegl1 libgl1-mesa-dri
  # Java (AWT) + Compose (Skia)
  libx11-6 libxext6 libxrender1 libxtst6 libxi6 libxrandr2 libxcursor1 libfreetype6 fontconfig fonts-dejavu-core
  # zvuk i MIDI (ALSA), biblioteke koje traži libpadsampler.so
  alsa-utils libasound2t64 libjack-jackd2-0 libpulse0
  # firmware: grafika, zvuk (Intel SOF na novijim laptopovima), mreža
  firmware-linux-free firmware-linux-nonfree firmware-sof-signed firmware-realtek
  intel-microcode amd64-microcode
  # mreža (Ableton Link preko kabla)
  network-manager iproute2
)

rm -rf "$ROOT"
mmdebstrap --variant=minbase --components="main contrib non-free non-free-firmware" \
  --include="$(IFS=,; echo "${PKGS[*]}")" \
  --dpkgopt='path-exclude=/usr/share/doc/*' --dpkgopt='path-exclude=/usr/share/man/*' \
  --dpkgopt='path-exclude=/usr/share/locale/*' --dpkgopt='path-include=/usr/share/locale/locale.alias' \
  "$SUITE" "$ROOT" "$MIRROR"

# ---- aplikacija
mkdir -p "$ROOT/opt"
tar -xzf "$APP_TAR" -C "$ROOT/opt"
mv "$ROOT/opt/PadSampler" "$ROOT/opt/padsampler"
test -x "$ROOT/opt/padsampler/bin/PadSampler"
ls "$ROOT/opt/padsampler/lib/app/resources/"

# ---- naši fajlovi (autologin, X, zvuk, prva particija za semplove)
cp -a "$HERE/rootfs/." "$ROOT/"
chmod +x "$ROOT/usr/local/sbin/"* "$ROOT/usr/local/bin/"*

for m in proc sys dev; do mount --rbind /$m "$ROOT/$m"; done
chroot "$ROOT" /bin/bash -euxo pipefail <<'EOF'
echo padsampler > /etc/hostname
printf '127.0.0.1 localhost\n127.0.1.1 padsampler\n' > /etc/hosts
useradd -m -u 1000 -s /bin/bash -G audio,video,input padsampler
for g in render netdev plugdev; do getent group $g && usermod -aG $g padsampler; done
passwd -d padsampler        # autologin, bez lozinke (nema SSH-a ni mreže prema unutra)
passwd -l root
mkdir -p /home/padsampler/.padsampler /home/padsampler/Samples
cp /etc/padsampler/settings.properties /home/padsampler/.padsampler/settings.properties
cp /etc/padsampler/bash_profile /home/padsampler/.bash_profile
chown -R padsampler:padsampler /home/padsampler
systemctl enable padsampler-samples.service
systemctl mask apt-daily.timer apt-daily-upgrade.timer e2scrub_all.timer fstrim.timer || true
systemctl set-default multi-user.target
update-initramfs -u -k all
EOF
umount -R "$ROOT/proc" "$ROOT/sys" "$ROOT/dev"

# ---- izlaz
mkdir -p "$OUT/live" "$OUT/efi"
cp -L "$ROOT"/boot/vmlinuz-* "$OUT/live/vmlinuz"
cp -L "$ROOT"/boot/initrd.img-* "$OUT/live/initrd.img"
rm -f "$ROOT"/boot/initrd.img-*      # već je u izlazu, ne treba i u squashfs-u
mksquashfs "$ROOT" "$OUT/live/filesystem.squashfs" -comp xz -Xbcj x86 -b 1M -noappend

# Potpisani shim + GRUB (Secure Boot) iz Debiana
cd /tmp
apt-get download shim-signed grub-efi-amd64-signed
for d in shim-signed_*.deb grub-efi-amd64-signed_*.deb; do dpkg -x "$d" /tmp/efi-x; done
cp /tmp/efi-x/usr/lib/shim/shimx64.efi.signed "$OUT/efi/BOOTX64.EFI"
cp /tmp/efi-x/usr/lib/shim/mmx64.efi.signed "$OUT/efi/mmx64.efi" || true
cp /tmp/efi-x/usr/lib/grub/x86_64-efi-signed/grubx64.efi.signed "$OUT/efi/grubx64.efi"

du -h "$OUT"/live/* "$OUT"/efi/*
