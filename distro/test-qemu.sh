#!/usr/bin/env bash
# Probno pokretanje image-a u QEMU (bez monitora): UEFI i BIOS, snimci ekrana + serijski log.
#   bash distro/test-qemu.sh <image.img> <izlazni folder>
# Paketi: qemu-system-x86 ovmf socat
set -uo pipefail
IMG=$(readlink -f "$1")
OUT=$(readlink -f "$2")
mkdir -p "$OUT"
ACCEL=tcg
[ -w /dev/kvm ] && ACCEL=kvm
echo "QEMU accel: $ACCEL"

run() {   # run <ime> <dodatni qemu argumenti...>
    local name=$1; shift
    local disk="$OUT/disk-$name.img" mon="$OUT/mon-$name.sock"
    cp "$IMG" "$disk"
    truncate -s +3G "$disk"          # "USB" veći od image-a: provjera da se napravi particija PADSAMPLES
    qemu-system-x86_64 -machine q35,accel=$ACCEL -cpu max -smp 2 -m 3072 \
        "$@" \
        -drive file="$disk",format=raw,if=none,id=usb0 \
        -device qemu-xhci -device usb-storage,drive=usb0,removable=on -device usb-tablet -device usb-kbd \
        -device intel-hda -device hda-output,audiodev=snd0 -audiodev none,id=snd0 \
        -vga virtio -display none -serial file:"$OUT/serial-$name.log" \
        -monitor unix:"$mon",server,nowait &
    local pid=$!
    for t in 45 90 150; do
        sleep $(( t - ${prev:-0} )); prev=$t
        echo "screendump $OUT/$name-${t}s.png -f png" | socat - UNIX-CONNECT:"$mon" >/dev/null 2>&1
    done
    unset prev
    echo "quit" | socat - UNIX-CONNECT:"$mon" >/dev/null 2>&1
    sleep 2; kill $pid 2>/dev/null
    rm -f "$disk"
    echo "== $name: zadnje linije serijskog loga"
    tail -n 25 "$OUT/serial-$name.log" || true
}

VARS="$OUT/OVMF_VARS.fd"
cp /usr/share/OVMF/OVMF_VARS_4M.fd "$VARS"
run uefi -drive if=pflash,format=raw,readonly=on,file=/usr/share/OVMF/OVMF_CODE_4M.fd -drive if=pflash,format=raw,file="$VARS"
run bios
rm -f "$VARS" "$OUT"/mon-*.sock
ls -la "$OUT"

# Provjere u logu (UEFI)
grep -q "padsampler-samples: " "$OUT/serial-uefi.log" && grep "padsampler-samples: " "$OUT/serial-uefi.log" || echo "UPOZORENJE: nema poruke padsampler-samples u logu"
grep -qi "kernel panic" "$OUT"/serial-*.log && { echo "GREŠKA: kernel panic"; exit 1; }
exit 0
