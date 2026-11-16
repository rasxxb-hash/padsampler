# PadSampler za računar (Windows)

Korak 1: jezgro (mikser, razvlačenje tempa, Ableton Link) kao biblioteka koja se gradi na računaru, s istim testovima kao na Androidu.
Koristi iste izvorne fajlove iz `app/src/main/cpp` (nema kopija). Zvuk, MIDI i ekran dolaze u sljedećim koracima.

## Šta treba instalirati (jednom)
1. **Visual Studio Build Tools 2022** (besplatno): u instalatoru označi "Desktop development with C++".
2. **CMake** (cmake.org, ili dolazi uz Visual Studio Build Tools / Android Studio SDK).

## Gradnja i testovi
U "x64 Native Tools Command Prompt for VS 2022" (ili običnom PowerShellu ako je CMake u PATH-u), iz foldera projekta:

    cmake -S desktop -B desktop\build -G "Visual Studio 17 2022" -A x64
    cmake --build desktop\build --config Release
    ctest --test-dir desktop\build -C Release --output-on-failure

Očekivano: `100% tests passed, 0 tests failed out of 4`.

Ako se pojavi greška pri gradnji (crveni tekst), pošalji mi cijeli ispis. Provjereno je na Linuxu; Windows-specifični dio (MSVC, Link/Winsock) još nije isproban na pravom Windowsu.

## Korak 2: probni zvuk (audio_test)

Ponovi konfiguraciju (preuzima RtAudio, treba internet) i build:

    cmake -S desktop -B desktop\build -G "Visual Studio 17 2022" -A x64
    cmake --build desktop\build --config Release --target audio_test

Pokretanje (iz foldera projekta):

    desktop\build\Release\audio_test.exe --list
    desktop\build\Release\audio_test.exe
    desktop\build\Release\audio_test.exe --device ID --buffer 128 --seconds 30

Parametri: `--api wasapi`, `--device ID` (iz liste), `--rate 48000`, `--buffer 256`, `--seconds 20`.
Čuje se petlja: kick na svakoj dobi, hi-hat između. Ispis prikazuje kašnjenje, pucketanja i opterećenje.

### ASIO
RtAudio ima svoja ASIO zaglavlja, pa Steinbergov SDK nije potreban za build (ne stavljati ga u repozitorij).
Test: `audio_test.exe --list` pokazuje i ASIO uređaje; `audio_test.exe --api asio --device ID --buffer 128`.
Za Behringer treba instaliran njegov ASIO drajver.

## Korak 4: padsampler.dll + JNI proba

`padsampler.dll` je isti JNI most kao na Androidu (`jni_bridge.cpp`), ali sa RtAudio motorom
(`desktop/src/desktop_audio_engine.*`). Treba JDK (npr. onaj iz Android Studia: `...\Android Studio\jbr`); JNI zaglavlja su već u `desktop/third_party/jni`.
Java traži `padsampler.dll`, pa mora postojati `desktop\build\Release\padsampler.dll` nakon builda.

    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    cmake -S desktop -B desktop\build -G "Visual Studio 17 2022" -A x64
    cmake --build desktop\build --config Release --target padsampler
    & "$env:JAVA_HOME\bin\javac" -d desktop\build\jvm desktop\jvm-test\com\example\padsampler\audio\NativeAudio.java desktop\jvm-test\com\example\padsampler\audio\SmokeTest.java
    & "$env:JAVA_HOME\bin\java" "-Djava.library.path=desktop\build\Release" -cp desktop\build\jvm com.example.padsampler.audio.SmokeTest 15

Podešavanje zvuka (varijable okruženja, prije pokretanja): `PADSAMPLER_API` (wasapi|asio|null),
`PADSAMPLER_DEVICE` (ID), `PADSAMPLER_BUFFER`, `PADSAMPLER_RATE`.
Primjer: `$env:PADSAMPLER_API="asio"; $env:PADSAMPLER_DEVICE="129"; $env:PADSAMPLER_BUFFER="128"`.
