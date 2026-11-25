#!/usr/bin/env bash
# PadSampler za Linux (x86_64 ili arm64): motor (libpadsampler.so) + testovi + aplikacija bez instalacije.
# Pokretati iz korijena repoa:  bash desktop/build-linux.sh
# Paketi (Debian 12/13, Ubuntu 22.04+):
#   sudo apt install build-essential cmake git pkg-config openjdk-17-jdk \
#        libasound2-dev libjack-jackd2-dev libpulse-dev
# Rezultat: desktop-app/build/installer/main/app/PadSampler/bin/PadSampler
set -euo pipefail
cd "$(dirname "$0")/.."

cmake -S desktop -B desktop/build -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build -j"$(nproc)"
ctest --test-dir desktop/build --output-on-failure

cd desktop-app
chmod +x gradlew
./gradlew --no-daemon createDistributable
echo
echo "Gotovo: desktop-app/build/installer/main/app/PadSampler/bin/PadSampler"
