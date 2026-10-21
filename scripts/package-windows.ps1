# Build the Chasse Studio desktop app-image for Windows.
#
# Requirements:
#   - JDK 17 (provides jpackage)
#   - Apache Maven 3.6+
#   Either both on PATH, or installed in the default locations below.
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1
# Result: target\installer\ChasseStudio\  (app-image folder)

$ErrorActionPreference = "Stop"

# ── Locate mvn ──────────────────────────────────────────────────────────────
$MVN = Get-Command mvn -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $MVN) {
    # IntelliJ bundles Maven — use it if present
    $candidate = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd"
    if (Test-Path $candidate) { $MVN = $candidate }
}
if (-not $MVN) {
    Write-Error "Maven (mvn) was not found. Install Apache Maven 3.6+ or open a terminal from IntelliJ IDEA."
    exit 1
}
Write-Host "Using Maven: $MVN" -ForegroundColor DarkGray

# ── Locate jpackage ──────────────────────────────────────────────────────────
$JPACKAGE = Get-Command jpackage -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $JPACKAGE) {
    $candidate = "C:\Program Files\Java\jdk-17\bin\jpackage.exe"
    if (Test-Path $candidate) { $JPACKAGE = $candidate }
}
if (-not $JPACKAGE) {
    Write-Error "jpackage was not found. Make sure JDK 17 is installed at C:\Program Files\Java\jdk-17 or on PATH."
    exit 1
}
Write-Host "Using jpackage: $JPACKAGE" -ForegroundColor DarkGray

# ── Resolve version from pom.xml ─────────────────────────────────────────────
$VERSION = (& $MVN -q -DforceStdout help:evaluate "-Dexpression=project.version" 2>$null).Trim()
if (-not $VERSION -or $VERSION -like "*ERROR*") {
    # Fallback: parse pom.xml directly
    [xml]$pom = Get-Content pom.xml -Raw
    $VERSION = $pom.project.version
}
if (-not $VERSION) {
    Write-Error "Could not determine project version from pom.xml."
    exit 1
}
Write-Host "Version: $VERSION" -ForegroundColor Cyan

# ── Maven build ───────────────────────────────────────────────────────────────
Write-Host "Building (bundles Windows JCEF natives)..." -ForegroundColor Cyan
& $MVN -DskipTests package -Pwindows
if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven build failed."
    exit 1
}

# ── jpackage ──────────────────────────────────────────────────────────────────
# jpackage refuses to overwrite an existing app-image folder — remove it first.
if (Test-Path target\installer\ChasseStudio) {
    Remove-Item -Recurse -Force target\installer\ChasseStudio
}
New-Item -ItemType Directory -Force -Path target\installer | Out-Null

Write-Host "Running jpackage..." -ForegroundColor Cyan
& $JPACKAGE `
  --name            ChasseStudio `
  --input           target\package `
  --main-jar        "chasse-studio-desktop-$VERSION.jar" `
  --main-class      com.chasse.desktop.Main `
  --type            app-image `
  --dest            target\installer `
  --app-version     $VERSION `
  --vendor          "Chasse Studio" `
  --description     "Chasse Studio desktop app with phone remote" `
  --java-options    "-Xmx512m" `
  --icon            src\main\resources\Logo.ico

if ($LASTEXITCODE -ne 0) {
    Write-Error "jpackage failed."
    exit 1
}

Write-Host "Done: target\installer\ChasseStudio\" -ForegroundColor Green
