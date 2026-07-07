#!/usr/bin/env python3
"""Return 0 if a policy main grid CSV has all N x scenario cells with >= min trials."""
from __future__ import annotations

import csv
import sys
from collections import Counter
from pathlib import Path

NODES = (100, 300, 500, 1000)
SCENARIOS = ("Normal Load", "Burst Load", "Churn")


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: check-main-grid.py <trials_policy.csv> [min_trials]")
    path = Path(sys.argv[1])
    min_trials = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    if not path.exists():
        raise SystemExit(1)
    with path.open(newline="", encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if not (r.get("variant") or "").strip()]
    counts = Counter((int(r["nodes"]), r["scenario"]) for r in rows)
    missing = []
    for n in NODES:
        for s in SCENARIOS:
            if counts.get((n, s), 0) < min_trials:
                missing.append(f"N={n} {s} ({counts.get((n, s), 0)}/{min_trials})")
    if missing:
        print(f"incomplete: {path.name} ({len(missing)} cells)", file=sys.stderr)
        for item in missing[:8]:
            print(f"  - {item}", file=sys.stderr)
        if len(missing) > 8:
            print(f"  ... +{len(missing) - 8} more", file=sys.stderr)
        raise SystemExit(1)
    print(f"ok: {path.name} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
