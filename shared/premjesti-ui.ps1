# Korak 5c: obriši stare kopije ekrana (sada su u shared\...\ui). Pokreni iz foldera projekta, NAKON raspakivanja zipa:
#   powershell -ExecutionPolicy Bypass -File shared\premjesti-ui.ps1
$j = "app\src\main\java\com\example\padsampler\ui"
foreach ($f in @("BrowserScreen.kt","Components.kt","Header.kt","MixerScreen.kt","SessionScreen.kt","Theme.kt")) {
  if (Test-Path "$j\$f") { Remove-Item -Force "$j\$f"; Write-Host "obrisan stari: ui\$f" }
}
Write-Host "Gotovo. Sad Sync Project + Rebuild."
