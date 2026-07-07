# DynaCol — Dynamic Colony-Based Optimizer for Fog Computing

DynaCol is a cold-start framework for self-organizing fog colonies and online
service placement. The core placement engine is **DCBO** (Dynamic Colony-Based
Optimizer), built on an instrumented **iFogSim2** simulator.

This repository contains:

- the Java implementation (`ifogsim2/src/org/fog/dynacol/`)
- evaluation harness and baseline adapters
- raw simulator trial logs used in the paper
- scripts to regenerate all summary tables and figures

## Repository layout

```
DynaCol/
├── ifogsim2/                  # iFogSim2 + DynaCol Java sources
│   ├── src/org/fog/dynacol/   # colony formation, CRT/GRT, DCBO placement
│   ├── src/org/fog/test/dynacol/  # evaluation entry points
│   ├── compile.sh
│   └── jars/                  # third-party dependencies
├── evaluation/
│   ├── run_ifogsim2.py        # batch simulator driver
│   ├── generate_results.py    # statistics, significance tests, LaTeX, figures
│   ├── results/               # raw trial CSVs + summaries + figures
│   └── latex/                 # auto-generated table fragments
├── scripts/
│   ├── regenerate-results.sh  # rebuild tables/figures from committed CSVs
│   ├── run-evaluation-journal.sh
│   └── setup-jdk.sh
├── REPRODUCIBILITY.md         # grid spec, baseline notes, Edgeward fix
└── LICENSE
```

## Requirements

| Component | Version |
|-----------|---------|
| Java JDK | 21 (runtime + compiler) |
| Python | 3.10+ (stdlib only; no pip install required) |
| OS | Linux recommended |

Install OpenJDK 21 on Debian/Ubuntu:

```bash
sudo apt install openjdk-21-jdk-headless
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

If you cannot install system-wide, use `./scripts/setup-jdk.sh` to unpack a
local JDK under `.tools/` (git-ignored).

## Quick start — regenerate paper results

Pre-computed trial logs are included. To rebuild all summary CSVs, LaTeX tables,
and figures without re-running simulations:

```bash
chmod +x scripts/regenerate-results.sh
./scripts/regenerate-results.sh
```

Outputs:

| Path | Content |
|------|---------|
| `evaluation/results/*_summary.csv` | Mean, std, 95% CI per configuration |
| `evaluation/results/significance_tests.csv` | Holm-corrected pairwise tests vs DCBO |
| `evaluation/latex/tab_*.tex` | Table fragments |
| `evaluation/results/figures/` | PNG + SVG plots |
| `evaluation/results/manifest.json` | Run metadata and completeness check |

See [REPRODUCIBILITY.md](REPRODUCIBILITY.md) for the full evaluation grid,
baseline fidelity notes, and the Edgeward bookkeeping fix.

## Build and run the simulator

```bash
cd ifogsim2
chmod +x compile.sh
./compile.sh full          # first-time build
./compile.sh incremental   # rebuild DynaCol sources only
./compile.sh run dcbo      # quick single-policy demo
```

Single evaluation trial (writes one row to a CSV):

```bash
cd ifogsim2
./compile.sh incremental
# Build classpath from compiled output + jars:
CP="out/production/iFogSim"
for j in jars/*.jar jars/commons-math3-3.5/*.jar; do CP="$CP:$j"; done
java -cp "$CP" org.fog.test.dynacol.DynaColEvaluationRunner \
  dcbo 500 burst 1 20260628 /tmp/trial.csv
```

Arguments: `[policy] [nodes] [scenario] [trial] [seed] [csvPath]`.

Policies: `dcbo`, `static_dcbo`, `fogplan`, `edgeward`, `greedy`, `tavousi`,
`dogani`.

## Re-run the full evaluation (optional)

Re-running all trials takes days on a single machine. The committed CSVs match
the paper's reported grid (580 main-grid trials + 100 ablation trials, `n=10`).

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./scripts/run-evaluation-journal.sh     # DCBO, Static-DCBO, FogPlan, Edgeward + ablation

# Additional main-grid baselines (resume-safe):
POLICY=greedy GREEDY_GRID=1 python3 evaluation/run_ifogsim2.py
POLICY=tavousi python3 evaluation/run_ifogsim2.py
POLICY=dogani python3 evaluation/run_ifogsim2.py

./scripts/regenerate-results.sh
```

Useful environment variables for `run_ifogsim2.py`:

| Variable | Effect |
|----------|--------|
| `TRIALS=10` | Trials per configuration cell |
| `RESUME=1` | Skip completed cells |
| `QUICK=1` | Small smoke grid |
| `ABLATION=1` | Ablation variants @ N=500 |
| `SENSITIVITY=1` | Wt / MaxColonySize sweep |
| `SLADEADLINE=1` | Deadline scale sweep |
| `GREEDY_GRID=1` | Greedy-Nearest full grid |

## Key Java components

| Paper concept | Java class |
|---------------|------------|
| Colony bootstrap | `colony.ColonyFormationProtocol` |
| Manager handover | `colony.ManagerHandoverService` |
| Potency score | `colony.PotencyCalculator` |
| CRT / GRT | `table.ColonyResourceTable`, `table.GlobalResourceTable` |
| DCBO placement | `placement.DCBOPlacement` |
| FogPlan Min-Cost | `baseline.fogplan.FogPlanMinCostEngine` |
| Static-DCBO | `colony.StaticClusterOverlayBuilder` |
| Greedy-Nearest | `placement.GreedyNearestPlacement` |
| Tavousi-Fuzzy | `baseline.tavousi.FuzzyTavousiPlacement` |
| Dogani-TwoTier | `baseline.dogani.TwoTierDoganiPlacement` |
| Edgeward (iFogSim2) | `placement.ModulePlacementEdgewards` |
| Batch evaluation | `test.dynacol.DynaColEvaluationRunner` |

## Citation

If you use this code, please cite the DynaCol paper (Cluster Computing, under
review). BibTeX will be added upon publication.

## License

DynaCol extensions are released under the [MIT License](LICENSE).

The bundled iFogSim2 base is licensed by the CLOUDS Laboratory; see
[ifogsim2/LICENSE.txt](ifogsim2/LICENSE.txt).

## Contact

Rouhollah Nabati — [https://github.com/RouhollahNabati/DynaCol](https://github.com/RouhollahNabati/DynaCol)
