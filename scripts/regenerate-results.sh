#!/usr/bin/env bash
# Regenerate summary CSVs, LaTeX tables, and figures from committed trial logs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export TRIALS="${TRIALS:-10}"
export MIN_SIM_TRIALS="${MIN_SIM_TRIALS:-$TRIALS}"
export REQUIRE_REAL=1
export ALLOW_SYNTHETIC=0
export SKIP_PDF=1

cd "$ROOT"
python3 "$ROOT/scripts/dedupe-trial-csvs.py" "$TRIALS"
TRIALS="$TRIALS" MIN_SIM_TRIALS="$MIN_SIM_TRIALS" REQUIRE_REAL=1 ALLOW_SYNTHETIC=0 \
  SKIP_PDF=1 python3 "$ROOT/evaluation/generate_results.py"
echo "Done. Outputs under $ROOT/evaluation/results/ and $ROOT/evaluation/latex/"
