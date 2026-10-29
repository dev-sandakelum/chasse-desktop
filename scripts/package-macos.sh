#!/usr/bin/env bash
# Build the Chasse Studio desktop installer for macOS (DMG).
#
# Requirements:
#   - JDK 14+ (17 recommended): provides jpackage
#   - Apache Maven 3.6+ on PATH
#
# Usage:  chmod +x scripts/package-macos.sh && ./scripts/package-macos.sh
# Result: target/installer/ChasseStudio-<version>.dmg
#
# The script picks the matching JCEF natives profile automatically:
#   - Apple Silicon (aarch64) → -Pmacos-arm
#   - Intel (x86_64)          → -Pmacos-intel

set -euo pipefail

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn not found on PATH. Install Apache Maven 3.6+ (https://maven.apache.org) and retry." >&2
  exit 1
fi
if ! command -v jpackage >/dev/null 2>&1; then
  echo "ERROR: jpackage not found. Install JDK 14 or newer (17 recommended) and make sure its bin directory is on PATH." >&2
  exit 1
fi

# Resolve version from pom.xml so this script never needs a manual update.
VERSION=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version 2>/dev/null)
if [ -z "$VERSION" ]; then
  echo "ERROR: Could not read version from pom.xml." >&2
  exit 1
fi
echo "Version: $VERSION"

ARCH="$(uname -m)"
PROFILE="macos-arm"
if [ "$ARCH" = "x86_64" ] || [ "$ARCH" = "amd64" ]; then
  PROFILE="macos-intel"
fi
echo "Packaging for macOS ($ARCH → profile: $PROFILE, bundles JCEF natives)..."

mvn -q -DskipTests package -P"$PROFILE"

mkdir -p target/installer

jpackage \
  --name ChasseStudio \
  --input target/package \
  --main-jar "chasse-studio-desktop-${VERSION}.jar" \
  --main-class com.chasse.desktop.Main \
  --type dmg \
  --dest target/installer \
  --app-version "$VERSION" \
  --vendor "Chasse Studio" \
  --description "Chasse Studio desktop app with phone remote" \
  --java-options "-Xms48m" \
  --java-options "-Xmx512m" \
  --java-options "-XX:+UseSerialGC" \
  --java-options "-XX:TieredStopAtLevel=1" \
  --java-options "-XX:+ExitOnOutOfMemoryError" \
  --mac-package-name "Chasse Studio"

echo "Done: target/installer/ChasseStudio-${VERSION}.dmg"
