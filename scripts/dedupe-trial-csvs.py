#!/usr/bin/env python3
"""Keep at most TRIALS rows per (method, nodes, scenario) for journal grid cells."""
from __future__ import annotations

import csv
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIM = ROOT / "evaluation" / "results" / "simulator"
ALLOWED_NODES = {100, 300, 500, 1000}
ALLOWED_SCENARIOS = {"Normal Load", "Burst Load", "Churn"}
TRIALS = int(sys.argv[1]) if len(sys.argv) > 1 else 10


def dedupe(path: Path) -> None:
    rows: list[dict] = []
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames or []
        for row in reader:
            if int(row["nodes"]) not in ALLOWED_NODES:
                continue
            if row["scenario"] not in ALLOWED_SCENARIOS:
                continue
            if (row.get("variant") or "").strip():
                continue
            try:
                if float(row.get("p95_ms") or 0) <= 0 and float(row.get("mean_loop_ms") or 0) <= 0:
                    continue
            except ValueError:
                pass
            rows.append(row)
    buckets: dict[tuple, dict[int, dict]] = defaultdict(dict)
    for row in rows:
        key = (row["method"], row["nodes"], row["scenario"])
        trial = int(row["trial"])
        buckets[key][trial] = row
    out_rows: list[dict] = []
    for key in sorted(buckets):
        trials = buckets[key]
        for t in sorted(trials)[:TRIALS]:
            out_rows.append(trials[t])
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(out_rows)
    print(f"{path.name}: {len(out_rows)} rows ({len(buckets)} cells)")


SKIP = {
    "trials_ablation.csv",
    "trials_sensitivity.csv",
    "trials_incremental.csv",
    "trials_sla_deadline.csv",
    "trials_sla_slope.csv",
    "trials_offline_ga.csv",
    "trials_gahc.csv",
    "trials_energy.csv",
}


def main() -> None:
    for path in sorted(SIM.glob("trials_*.csv")):
        if path.name in SKIP:
            continue
        dedupe(path)


if __name__ == "__main__":
    main()
