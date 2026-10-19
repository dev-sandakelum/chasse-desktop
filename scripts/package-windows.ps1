# Build the Chasse Studio desktop installer for Windows (MSI).
#
# Requirements:
#   - JDK 14+ (17 recommended): provides jpackage
#   - Apache Maven 3.6+ on PATH
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1
# Result: target\installer\ChasseStudio-1.0.0.msi

$ErrorActionPreference = "Stop"

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: Maven (mvn) was not found on PATH. Install Apache Maven 3.6+ (https://maven.apache.org) and retry." -ForegroundColor Red
    exit 1
}
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: jpackage was not found. Install JDK 14 or newer (17 recommended) and make sure its bin directory is on PATH." -ForegroundColor Red
    exit 1
}

Write-Host "Building (bundles Windows JCEF natives)..." -ForegroundColor Cyan
mvn -q -DskipTests package -Pwindows
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Maven build failed." -ForegroundColor Red
    exit 1
}

New-Item -ItemType Directory -Force -Path target/installer | Out-Null

Write-Host "Running jpackage..." -ForegroundColor Cyan
jpackage `
  --name ChasseStudio `
  --input target/package `
  --main-jar chasse-studio-desktop-1.0.2.jar `
  --main-class com.chasse.desktop.Main `
  --type app-image `
  --dest target/installer `
  --app-version 1.0.2 `
  --vendor "Chasse Studio" `
  --description "Chasse Studio desktop app with phone remote" `
  --java-options "-Xmx512m" `
  --icon src/main/resources/Logo.ico

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: jpackage failed." -ForegroundColor Red
    exit 1
}

Write-Host "Done: target\installer\ChasseStudio-1.0.2" -ForegroundColor Green
