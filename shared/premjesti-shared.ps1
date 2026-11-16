# Korak 5b: premjesti zajednički kod u shared\ i obriši stare kopije.
# Pokreni iz foldera projekta (PadSampler), NAKON što raspakuješ zip preko projekta:
#   powershell -ExecutionPolicy Bypass -File shared\premjesti-shared.ps1
$j  = "app\src\main\java\com\example\padsampler"
$sh = "shared\src\main\kotlin\com\example\padsampler"
New-Item -ItemType Directory -Force "$sh\model" | Out-Null
# Model.kt je tvoja verzija (boje): premjesti je, ne prepisuj
if (Test-Path "$j\model\Model.kt") { Move-Item -Force "$j\model\Model.kt" "$sh\model\Model.kt"; Write-Host "premješten: model\Model.kt" }
# ostalo je već u shared\ iz zipa; obriši stare kopije
$old = @(
  "SamplerViewModel.kt",
  "audio\NativeAudio.kt", "audio\SampleLoader.kt",
  "data\BpmParser.kt", "data\KeyParser.kt", "data\PadStore.kt", "data\SessionStore.kt", "data\Library.kt",
  "platform\Platform.kt",
  "midi\LaunchpadSurface.kt"
)
foreach ($f in $old) {
  if (Test-Path "$j\$f") { Remove-Item -Force "$j\$f"; Write-Host "obrisan stari: $f" }
}
Write-Host "Gotovo. Sad dodaj sourceSets blok u app\build.gradle.kts (vidi upute) i uradi Sync + Rebuild."
