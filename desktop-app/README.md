# PadSampler za Windows (Compose Desktop)

Koristi isti `shared/` kod kao Android. Zvuk ide kroz `padsampler.dll` (CMake, folder `desktop/`).

## Priprema (jednom)
1. Izgradi DLL: `cmake --build desktop/build --config Release` (nastaje `desktop\build\Release\padsampler.dll`).
2. Wrapper (gradlew, gradlew.bat, gradle\wrapper) je već u ovom folderu.
3. Fontovi (opcionalno): Inter*.ttf i Audiowide*.ttf u `app\src\main\assets\fonts\`.

## Pokretanje
```
cd desktop-app
.\gradlew.bat run
```
Postavke i biblioteka su u `%APPDATA%\PadSampler`. Opcije motora: PADSAMPLER_API / DEVICE / RATE / BUFFER.

## EXE / instalacija
Prvo izgradi DLL (vidi gore) i podigni `appVersion` u `gradle.properties` (npr. 1.1.0 -> 1.2.0),
inače nova instalacija ne zamijeni staru. Zatim iz `desktop-app`:
```
.\gradlew.bat packageExe            # instalacioni .exe -> build\installer\main\exe\
.\gradlew.bat packageMsi            # .msi              -> build\installer\main\msi\
.\gradlew.bat createDistributable   # folder sa PadSampler.exe, bez instalacije -> build\installer\main\app\PadSampler\
```
