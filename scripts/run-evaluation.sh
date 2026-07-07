#!/usr/bin/env bash
# Resume or start the full iFogSim2 evaluation grid (7200 trials, 8 policy shards).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="${JAVA_HOME}/bin:${PATH}"
export LD_LIBRARY_PATH="${JAVA_HOME}/lib:${JAVA_HOME}/lib/server:${LD_LIBRARY_PATH:-}"
export RESUME=1
export WORKERS=2
cd "$ROOT"
exec bash scripts/run-evaluation-parallel.sh
