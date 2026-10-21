# Build the Chasse Studio desktop app-image for Windows.
#
# Requirements:
#   - JDK 14+ (17 recommended): provides jpackage
#   - Apache Maven 3.6+ on PATH
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1
# Result: target\installer\ChasseStudio\  (app-image folder)

$ErrorActionPreference = "Stop"

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: Maven (mvn) was not found on PATH. Install Apache Maven 3.6+ (https://maven.apache.org) and retry." -ForegroundColor Red
    exit 1
}
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: jpackage was not found. Install JDK 14 or newer (17 recommended) and make sure its bin directory is on PATH." -ForegroundColor Red
    exit 1
}

# Resolve version from pom.xml so this script never needs a manual update.
$VERSION = (Select-Xml -Path pom.xml -XPath "/*[local-name()='project']/*[local-name()='version']").Node.InnerText
if (-not $VERSION) {
    Write-Host "ERROR: Could not read version from pom.xml." -ForegroundColor Red
    exit 1
}
Write-Host "Version: $VERSION" -ForegroundColor Cyan

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
  --main-jar "chasse-studio-desktop-$VERSION.jar" `
  --main-class com.chasse.desktop.Main `
  --type app-image `
  --dest target/installer `
  --app-version $VERSION `
  --vendor "Chasse Studio" `
  --description "Chasse Studio desktop app with phone remote" `
  --java-options "-Xmx512m" `
  --icon src/main/resources/Logo.ico

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: jpackage failed." -ForegroundColor Red
    exit 1
}

Write-Host "Done: target\installer\ChasseStudio\" -ForegroundColor Green
