#!/usr/bin/env bash
# Build the Chasse Studio desktop installer for Linux (DEB).
#
# Requirements:
#   - JDK 14+ (17 recommended): provides jpackage
#   - Apache Maven 3.6+ on PATH
#   - dpkg-deb available (usually part of the base system on Debian/Ubuntu)
#
# Usage:  chmod +x scripts/package-linux.sh && ./scripts/package-linux.sh
# Result: target/installer/chassestudio_<version>-1_amd64.deb

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

echo "Packaging for Linux (bundles Linux JCEF natives)..."
mvn -q -DskipTests package -Plinux

mkdir -p target/installer

jpackage \
  --name ChasseStudio \
  --input target/package \
  --main-jar "chasse-studio-desktop-${VERSION}.jar" \
  --main-class com.chasse.desktop.Main \
  --type deb \
  --dest target/installer \
  --app-version "$VERSION" \
  --vendor "Chasse Studio" \
  --description "Chasse Studio desktop app with phone remote" \
  --java-options "-Xms48m" \n  --java-options "-Xmx512m" \n  --java-options "-XX:+UseSerialGC" \n  --java-options "-XX:TieredStopAtLevel=1" \n  --java-options "-XX:+ExitOnOutOfMemoryError"

echo "Done: target/installer/"
