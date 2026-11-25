#!/usr/bin/env bash
# USB image PadSampler OS-a (x86_64): GPT, GRUB za BIOS i UEFI (Secure Boot preko potpisanog shim-a).
# Pokreće se kao root na Linuxu (GitHub runner): bash distro/make-image.sh <out iz build-rootfs.sh> <image.img>
# Paketi: gdisk dosfstools mtools e2fsprogs grub-pc-bin
set -euo pipefail

SRC=$(readlink -f "$1")
IMG=$2
HERE=$(cd "$(dirname "$0")" && pwd)
PERSIST_MB=1024

live_mb=$(( $(du -sm "$SRC/live" | cut -f1) ))
ESP_MB=$(( (live_mb + 300 + 99) / 100 * 100 ))     # sistem + rezerva za GRUB i buduća ažuriranja
TOTAL_MB=$(( 1 + 1 + ESP_MB + PERSIST_MB + 1 ))
echo "sistem ${live_mb} MB, particija ${ESP_MB} MB, image ${TOTAL_MB} MB"

rm -f "$IMG"
truncate -s "${TOTAL_MB}M" "$IMG"
sgdisk -Z "$IMG" >/dev/null
sgdisk -n 1:2048:+1M          -t 1:EF02 -c 1:biosboot \
       -n 2:0:+"${ESP_MB}M"   -t 2:EF00 -c 2:PADSAMPLER \
       -n 3:0:+"${PERSIST_MB}M" -t 3:8300 -c 3:persistence \
       "$IMG"
sgdisk -p "$IMG"

LOOP=$(losetup -fP --show "$IMG")
trap 'umount -R /mnt/ps-esp /mnt/ps-pers 2>/dev/null || true; losetup -d "$LOOP" 2>/dev/null || true' EXIT
udevadm settle || true
for i in 2 3; do
    [ -b "${LOOP}p$i" ] || { echo "nema ${LOOP}p$i"; exit 1; }
done

mkfs.vfat -F 32 -n PADSAMPLER "${LOOP}p2"
mkfs.ext4 -q -L persistence "${LOOP}p3"

mkdir -p /mnt/ps-esp /mnt/ps-pers
mount "${LOOP}p2" /mnt/ps-esp
mount "${LOOP}p3" /mnt/ps-pers

# persistence: cijeli /home (podešavanja i sesije aplikacije); sistem ostaje samo za čitanje
echo "/home union" > /mnt/ps-pers/persistence.conf

# sistem
mkdir -p /mnt/ps-esp/live
cp "$SRC"/live/* /mnt/ps-esp/live/

# UEFI: shim (BOOTX64.EFI) -> grubx64.efi (isti folder) -> /EFI/debian/grub.cfg
mkdir -p /mnt/ps-esp/EFI/BOOT /mnt/ps-esp/EFI/debian
cp "$SRC/efi/BOOTX64.EFI" "$SRC/efi/grubx64.efi" /mnt/ps-esp/EFI/BOOT/
[ -f "$SRC/efi/mmx64.efi" ] && cp "$SRC/efi/mmx64.efi" /mnt/ps-esp/EFI/BOOT/
cp "$HERE/grub.cfg" /mnt/ps-esp/EFI/debian/grub.cfg
cp "$HERE/grub.cfg" /mnt/ps-esp/EFI/BOOT/grub.cfg

# BIOS: GRUB u biosboot particiju, moduli i konfiguracija na FAT particiji
grub-install --target=i386-pc --boot-directory=/mnt/ps-esp/boot \
    --modules="part_gpt fat search search_label normal linux all_video" "$LOOP"
cp "$HERE/grub.cfg" /mnt/ps-esp/boot/grub/grub.cfg

df -h /mnt/ps-esp /mnt/ps-pers
find /mnt/ps-esp -maxdepth 3 -type f | sort | head -40
sync
umount /mnt/ps-esp /mnt/ps-pers
losetup -d "$LOOP"
trap - EXIT
echo "Gotovo: $IMG"
