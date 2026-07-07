#!/usr/bin/env bash
# Install a local javac using apt download (no sudo required).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
DEB="$TOOLS/openjdk-21-jdk-headless.deb"
JDK_ROOT="$TOOLS/jdk-local"

mkdir -p "$TOOLS"

if [[ -x "$JDK_ROOT/usr/lib/jvm/java-21-openjdk-amd64/bin/javac" ]]; then
  echo "Local javac already installed under $JDK_ROOT"
  exit 0
fi

if [[ ! -f "$DEB" ]]; then
  echo "Downloading openjdk-21-jdk-headless package..."
  (cd "$TOOLS" && apt-get download openjdk-21-jdk-headless)
  mv "$TOOLS"/openjdk-21-jdk-headless_*.deb "$DEB" 2>/dev/null || true
fi

mkdir -p "$JDK_ROOT"
dpkg-deb -x "$DEB" "$JDK_ROOT"
echo "Installed javac at $JDK_ROOT/usr/lib/jvm/java-21-openjdk-amd64/bin/javac"
echo "Use system JRE: export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64"
