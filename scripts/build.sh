#!/usr/bin/env bash
# Alias for regenerate-results.sh (kept for backward compatibility).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec bash "$ROOT/scripts/regenerate-results.sh"
