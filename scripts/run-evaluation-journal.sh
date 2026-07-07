#!/usr/bin/env bash
# Full journal evaluation grid: 4 policies × 4 nodes × 3 scenarios × TRIALS + ablation @ N=500.
# Default TRIALS=10, real-only artifact generation (no synthetic fallback).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="${JAVA_HOME}/bin:${PATH}"
export LD_LIBRARY_PATH="${JAVA_HOME}/lib:${JAVA_HOME}/lib/server:${LD_LIBRARY_PATH:-}"

export TRIALS="${TRIALS:-10}"
export MIN_SIM_TRIALS="${MIN_SIM_TRIALS:-$TRIALS}"
export REQUIRE_REAL=1
export ALLOW_SYNTHETIC=0
export NODES="${NODES:-100,300,500,1000}"
export SCENARIOS="${SCENARIOS:-normal,burst,churn}"
export WORKERS="${WORKERS:-1}"
export FRESH="${FRESH:-1}"
export RESUME="${RESUME:-0}"
export EVAL_SLA_NODE="${EVAL_SLA_NODE:-500}"

cd "$ROOT"

if [ "$FRESH" = "1" ]; then
  echo "FRESH=1: removing prior simulator trial CSVs"
  rm -f "$ROOT/evaluation/results/simulator/trials"*.csv
fi

echo "==> Compiling DynaCol..."
bash ifogsim2/compile.sh incremental

echo "==> Main grid: 4 policies × ${NODES} × ${SCENARIOS} × ${TRIALS} trials"
# Optional reviewer extras (Greedy, sensitivity, incremental):
#   bash scripts/run-evaluation-reviewer-extras.sh
for p in dcbo static_dcbo fogplan edgeward; do
  echo "==> Policy: $p"
  POLICY="$p" RESUME=1 FRESH=0 python3 evaluation/run_ifogsim2.py
done

echo "==> Ablation: 5 variants × normal+burst @ N=500 × ${TRIALS} trials"
ABLATION=1 RESUME=1 FRESH=0 python3 evaluation/run_ifogsim2.py

echo "==> Regenerating tables and figures..."
bash "$ROOT/scripts/regenerate-results.sh"

echo "Done. Manifest: $ROOT/evaluation/results/manifest.json"
