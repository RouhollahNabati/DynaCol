# Reproducibility Notes

This document supplements the README with details referenced in the DynaCol
evaluation paper (Cluster Computing submission).

## Evaluation grid

| Setting | Value |
|---------|-------|
| Node counts | 100, 300, 500, 1000 |
| Scenarios | Normal Load, Burst Load, Churn |
| Trials per cell | 10 (`n=10`) |
| Base seed | 20260628 |
| Significance | Welch / Mann–Whitney with Holm correction (α=0.05) |

**Main-grid policies:** DynaCol/DCBO, Static-DCBO, FogPlan-MinCost, Edgeward,
Greedy-Nearest, Tavousi-Fuzzy, Dogani-TwoTier.

**Ablation variants** (N=500, normal + burst): Full DCBO, No-Handover, CRT-only,
GRT-only.

**Supplementary sweeps** (raw CSVs included): parameter sensitivity (`Wt`,
MaxColonySize), SLA deadline scale, SLA `N_slope`, incremental arrival,
offline generational GA, energy/cost refresh.

## Raw trial logs

Per-policy shards live under `evaluation/results/simulator/trials_*.csv`.
`evaluation/generate_results.py` merges all `trials*.csv` files, deduplicates by
(method, nodes, scenario, trial), and writes summary CSVs plus LaTeX table
fragments under `evaluation/latex/`.

Run metadata is recorded in `evaluation/results/manifest.json`.

## Edgeward bookkeeping correction

Stock iFogSim2 Edgeward placement aborts on burst/churn workloads with a
null-pointer in `shiftModuleNorth` when the per-device module-instance map lacks
entries after a module is removed.

**Files changed:** `ifogsim2/src/org/fog/placement/ModulePlacementEdgewards.java`
(and the mobile variant `ModulePlacementMobileEdgewards.java`).

**Fix (two parts):**

1. **Pre-initialization** — in the constructor, every application module is
   registered with instance count `0` on each fog device before placement begins.
2. **Safe lookups** — in `shiftModuleNorth`, map reads use `getOrDefault(..., 0)`
   instead of bare `get(...)`, so missing keys default to zero rather than
   throwing.

Module mapping, cloud-ward thresholds, and tuple routing are unchanged; only the
bookkeeping guard prevents trial aborts under dynamic load.

## Baseline fidelity (summary)

| Baseline | Implementation class | Notes |
|----------|---------------------|-------|
| FogPlan-MinCost | `FogPlanMinCostEngine` | Port of FogPlan Algorithm 2 (Min-Cost) |
| Static-DCBO | `StaticClusterOverlayBuilder` + DCBO | Pre-clustered geography, no runtime formation |
| Greedy-Nearest | `GreedyNearestPlacement` | Nearest feasible host, flat overlay |
| Tavousi-Fuzzy | `FuzzyTavousiPlacement` | Simplified Mamdani-style scorer |
| Dogani-TwoTier | `TwoTierDoganiPlacement` | Soft tier preference, top-5 nearest candidates |
| Offline-GA | `OfflineGeneticPlacement` | Pop=20, gen=15, tournament k=3 |

See class-level comments in `ifogsim2/src/org/fog/dynacol/baseline/` for details.

## Regenerating published numbers

From a clean checkout with Java 21 and Python 3:

```bash
chmod +x scripts/regenerate-results.sh
./scripts/regenerate-results.sh
```

This reads the committed trial CSVs and rewrites all summary statistics, test
results, LaTeX tables, and figures. No simulator re-run is required.

To re-run simulations (long-running; multi-day on a single machine):

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # adjust if needed
bash ifogsim2/compile.sh full
./scripts/run-evaluation-journal.sh                  # core 4 policies + ablation
POLICY=greedy GREEDY_GRID=1 python3 evaluation/run_ifogsim2.py
POLICY=tavousi python3 evaluation/run_ifogsim2.py
POLICY=dogani python3 evaluation/run_ifogsim2.py
# optional sweeps: SENSITIVITY=1, SLADEADLINE=1, SLADEADLINE_SLOPE=1, etc.
./scripts/regenerate-results.sh
```

## Verification

`scripts/check-main-grid.py` reports missing or under-filled cells in the main
evaluation grid. After regeneration, `evaluation/results/manifest.json` should
list an empty `missing_keys` array when all required trials are present.
