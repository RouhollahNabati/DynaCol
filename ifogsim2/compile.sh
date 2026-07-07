#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src"
OUT="$ROOT/out/production/iFogSim"

build_classpath() {
  local cp="$OUT"
  for jar in "$ROOT"/jars/*.jar "$ROOT"/jars/commons-math3-3.5/*.jar; do
    [[ -f "$jar" ]] && cp="$cp:$jar"
  done
  echo "$cp"
}

find_javac() {
  if command -v javac >/dev/null 2>&1; then
    command -v javac
    return
  fi
  local local_javac="$ROOT/../.tools/jdk-local/usr/lib/jvm/java-21-openjdk-amd64/bin/javac"
  if [[ -x "$local_javac" ]]; then
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
    export LD_LIBRARY_PATH="$JAVA_HOME/lib:$JAVA_HOME/lib/server:${LD_LIBRARY_PATH:-}"
    export PATH="$(dirname "$local_javac"):$JAVA_HOME/bin:$PATH"
    echo "$local_javac"
    return
  fi
  local tool_jdk
  tool_jdk="$(find "$ROOT/../.tools/jdk-21" -maxdepth 2 -type f -name javac 2>/dev/null | head -1 || true)"
  if [[ -n "$tool_jdk" ]]; then
    echo "$tool_jdk"
    return
  fi
  echo "javac not found. Run: ../scripts/setup-jdk.sh or apt install openjdk-21-jdk-headless" >&2
  exit 1
}

JAVAC="$(find_javac)"
JAVA_BIN="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/bin/java"
if [[ ! -x "$JAVA_BIN" ]]; then
  JAVA_BIN="$(command -v java)"
fi
CLASSPATH="$(build_classpath)"

mkdir -p "$OUT"

MODE="${1:-incremental}"
POLICY="${2:-dcbo}"

if [[ "$MODE" == "full" ]]; then
  mapfile -t SOURCES < <(find "$SRC" -name '*.java' | sort)
  echo "Full compile: ${#SOURCES[@]} sources"
  "$JAVAC" -encoding UTF-8 -sourcepath "$SRC" -classpath "$CLASSPATH" -d "$OUT" "${SOURCES[@]}"
elif [[ "$MODE" == "run" ]]; then
  POLICY="${2:-dcbo}"
  "$0" incremental
  "$JAVA_BIN" -cp "$CLASSPATH" org.fog.test.dynacol.DynaColColdStartSimulation "$POLICY"
  exit 0
else
  mapfile -t SOURCES < <(find "$SRC/org/fog/dynacol" "$SRC/org/fog/test/dynacol" -name '*.java' | sort)
  echo "Incremental DynaCol compile: ${#SOURCES[@]} sources"
  "$JAVAC" -encoding UTF-8 -sourcepath "$SRC" -classpath "$CLASSPATH" -d "$OUT" "${SOURCES[@]}"
fi

echo "Build complete: $OUT"

if [[ "$MODE" == "run-after" ]]; then
  "$JAVA_BIN" -cp "$CLASSPATH" org.fog.test.dynacol.DynaColColdStartSimulation "$POLICY"
fi
