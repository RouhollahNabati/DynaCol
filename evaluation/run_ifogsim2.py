#!/usr/bin/env python3
"""
Run iFogSim2 DynaCol evaluation trials and write raw CSV for generate_results.py.

Environment:
  QUICK=1          Smaller grid (2 node counts, 2 trials, normal+burst only)
  NODES=500        Comma-separated node counts (e.g. 500 or 100,500)
  SCENARIOS=...    Comma-separated scenario keys
  TRIALS=N         Override trial count (default 10, or 3 if QUICK)
  RESUME=1         Skip completed cells in output CSV
  FRESH=1          Delete output CSV before run
  WORKERS=N        Parallel Java trial workers (default 1)
  POLICY=X         Run only one policy key (for sharded runs)
  ABLATION=1       Ablation grid (5 variants x normal+burst @ N=500)
  SENSITIVITY=1    Wt/MaxColony sweep (DCBO @ N=500, n=5 by default)
  INCREMENTAL=1    Mid-simulation arrival (dcbo+static_dcbo; INCREMENTAL_NODES=100,300; n=5)
  GREEDY_GRID=1    Greedy-Nearest @ N=100,300,500,1000 x normal,burst,churn
  OFFLINE_GA=1     Offline generational GA on static colonies @ N=500 x normal,burst,churn
  ENERGY_GRID=1    All main policies @ all N (refresh energy/cost columns)
  SLADEADLINE=1    SLA deadline scale sweep (dcbo,edgeward,fogplan @ N=500)
  SLADEADLINE_SLOPE=1  N_slope sweep (0.05,0.1,0.15 @ N=500)
  SLADEADLINE_SCALES=0.8,1.0,1.2
"""

from __future__ import annotations

import csv
import hashlib
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IFOG = ROOT / "ifogsim2"
OUT = Path(__file__).resolve().parent / "results" / "simulator" / "trials.csv"

POLICIES = [
    "dcbo",
    "static_dcbo",
    "fogplan",
    "edgeward",
    "greedy",
    "tavousi",
    "dogani",
]
POLICY_METHOD = {
    "dcbo": "DynaCol/DCBO",
    "static_dcbo": "Static-DCBO",
    "fogplan": "FogPlan-MinCost",
    "edgeward": "Edgeward",
    "greedy": "Greedy-Nearest",
    "tavousi": "Tavousi-Fuzzy",
    "dogani": "Dogani-TwoTier",
    "offline_ga": "Offline-GA",
    "gahc": "Offline-GA",
}

ABLATION_VARIANTS = [
    "full",
    "no-handover",
    "crt-only",
    "grt-only",
]
ABLATION_METHOD = {
    "full": "Full DCBO",
    "no-handover": "No-Handover",
    "crt-only": "CRT-only",
    "grt-only": "GRT-only",
}

NODE_COUNTS = [100, 300, 500, 1000, 2000]
MAIN_GRID_NODES = [100, 300, 500, 1000]
MAIN_GRID_SCENARIO_KEYS = ["normal", "burst", "churn"]
SCENARIOS = [
    "normal",
    "burst",
    "mobility",
    "mobility_burst",
    "churn",
    "fcm_failure",
]

SENSITIVITY_WT = [10, 20, 40]
SENSITIVITY_MAX_COLONY = [30, 50, 80]

SLADEADLINE_POLICIES = ["dcbo", "edgeward", "fogplan"]
SLADEADLINE_DEFAULT_SCALES = [0.8, 1.0, 1.2]
SLADEADLINE_DEFAULT_N_SLOPES = [0.05, 0.1, 0.15]

BASE_SEED = 20260628


def parse_sla_deadline_scales() -> list[float]:
    raw = os.environ.get("SLADEADLINE_SCALES", "").strip()
    if not raw:
        return SLADEADLINE_DEFAULT_SCALES
    return [float(x.strip()) for x in raw.split(",") if x.strip()]


def parse_sla_n_slopes() -> list[float]:
    raw = os.environ.get("SLADEADLINE_N_SLOPES", "").strip()
    if not raw:
        return SLADEADLINE_DEFAULT_N_SLOPES
    return [float(x.strip()) for x in raw.split(",") if x.strip()]


def sla_deadline_variant(scale: float) -> str:
    pct = int(round(scale * 100))
    return f"sla_m{pct}"


def sla_n_slope_variant(n_slope: float) -> str:
    pct = int(round(n_slope * 1000))
    return f"sla_slope{pct:03d}"


def parse_node_list(quick: bool) -> list[int]:
    raw = os.environ.get("NODES", "").strip()
    if raw:
        return [int(x.strip()) for x in raw.split(",") if x.strip()]
    return [100, 500] if quick else MAIN_GRID_NODES


def parse_scenario_list(quick: bool) -> list[str]:
    raw = os.environ.get("SCENARIOS", "").strip()
    if raw:
        return [x.strip() for x in raw.split(",") if x.strip()]
    return ["normal", "burst"] if quick else MAIN_GRID_SCENARIO_KEYS


def setup_java_env() -> None:
    local_jdk = ROOT / ".tools" / "jdk-21"
    java_home = os.environ.get("JAVA_HOME")
    if not java_home and local_jdk.exists():
        java_home = str(local_jdk)
    java_home = java_home or "/usr/lib/jvm/java-21-openjdk-amd64"
    os.environ["JAVA_HOME"] = java_home
    os.environ["PATH"] = f"{java_home}/bin:{os.environ.get('PATH', '')}"
    os.environ["LD_LIBRARY_PATH"] = ":".join(
        filter(None, [
            f"{java_home}/lib",
            f"{java_home}/lib/server",
            os.environ.get("LD_LIBRARY_PATH", ""),
        ])
    )


def classpath() -> str:
    jars = list((IFOG / "jars").glob("*.jar"))
    jars += list((IFOG / "jars" / "commons-math3-3.5").glob("*.jar"))
    parts = [str(IFOG / "out" / "production" / "iFogSim")] + [str(j) for j in jars]
    return ":".join(parts)


def ensure_compiled() -> None:
    if os.environ.get("SKIP_COMPILE", "").strip() in {"1", "true", "yes"}:
        return
    compile_sh = IFOG / "compile.sh"
    if not compile_sh.exists():
        raise SystemExit(f"Missing {compile_sh}")
    subprocess.run(
        ["bash", str(compile_sh), "incremental"],
        check=True,
        cwd=IFOG,
        env=os.environ.copy(),
    )


def load_completed(
    ablation: bool,
    sensitivity: bool,
    incremental: bool,
    sla_variant: bool,
    sla_slope: bool = False,
) -> set[tuple]:
    done: set[tuple] = set()
    if not OUT.exists():
        return done
    variant_prefix = "sla_slope" if sla_slope else "sla_m"
    with OUT.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if sla_variant:
                variant = row.get("variant", "").strip()
                if not variant.startswith(variant_prefix):
                    continue
                policy = row_policy_key(row)
                if not policy:
                    continue
                done.add((
                    variant,
                    policy,
                    int(row["nodes"]),
                    scenario_key_from_label(row["scenario"]),
                    int(row["trial"]),
                ))
            elif sensitivity:
                variant = row.get("variant", "").strip()
                if not variant.startswith("wt"):
                    continue
                done.add((
                    variant,
                    int(row["nodes"]),
                    scenario_key_from_label(row["scenario"]),
                    int(row["trial"]),
                ))
            elif ablation:
                variant = row.get("variant", "").strip()
                if not variant:
                    method = row.get("method", "")
                    variant = next((k for k, m in ABLATION_METHOD.items() if m == method), "")
                if not variant:
                    continue
                done.add((
                    variant,
                    int(row["nodes"]),
                    scenario_key_from_label(row["scenario"]),
                    int(row["trial"]),
                ))
            elif incremental:
                policy = row_policy_key(row)
                if not policy:
                    continue
                done.add((
                    policy,
                    int(row["nodes"]),
                    scenario_key_from_label(row["scenario"]),
                    int(row["trial"]),
                ))
            else:
                policy = row_policy_key(row)
                if not policy:
                    continue
                done.add((
                    policy,
                    int(row["nodes"]),
                    scenario_key_from_label(row["scenario"]),
                    int(row["trial"]),
                ))
    return done


def scenario_key_from_label(label: str) -> str:
    inv = {
        "Normal Load": "normal",
        "Burst Load": "burst",
        "Mobility": "mobility",
        "Mobility + Burst": "mobility_burst",
        "Churn": "churn",
        "FCM Failure": "fcm_failure",
        "Incremental Arrival (Normal)": "incremental_arrival",
        "Incremental Arrival (Burst)": "incremental_burst",
        "Incremental Arrival (Mobility)": "incremental_mobility",
        "Incremental Arrival (Mobility + Burst)": "incremental_mobility_burst",
    }
    return inv.get(label, label.lower().replace(" ", "_"))


def normalize_policy_key(raw: str) -> str:
    value = (raw or "").strip().lower()
    if not value:
        return ""
    if value in POLICY_METHOD:
        return value
    if value.startswith("dynacol_dcbo"):
        return "dcbo"
    if value.startswith("static_dcbo"):
        return "static_dcbo"
    if value.startswith("fogplan"):
        return "fogplan"
    if value.startswith("edgeward"):
        return "edgeward"
    if value.startswith("greedy"):
        return "greedy"
    if value.startswith("tavousi"):
        return "tavousi"
    if value.startswith("dogani"):
        return "dogani"
    if value.startswith("offline_ga"):
        return "offline_ga"
    if value.startswith("gahc"):
        return "gahc"
    return ""


def row_policy_key(row: dict) -> str:
    by_method = next(
        (p for p, m in POLICY_METHOD.items() if m == row.get("method", "")),
        "",
    )
    if by_method:
        return by_method
    return normalize_policy_key(row.get("policy", ""))


def stable_seed_offset(*parts: object, mod: int = 100000) -> int:
    payload = "|".join(str(p) for p in parts).encode("utf-8")
    digest = hashlib.blake2b(payload, digest_size=8).digest()
    return int.from_bytes(digest, byteorder="big", signed=False) % mod


def run_trial(
    policy: str,
    nodes: int,
    scenario: str,
    trial: int,
    seed: int,
    out_path: Path,
    variant: str = "",
    wt_ms: float = -1.0,
    max_colony_rtt_ms: float = -1.0,
    bootstrap_fraction: float = 1.0,
    sla_deadline_scale: float = 1.0,
    n_slope: float = -1.0,
) -> None:
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
    java_bin = Path(java) / "bin" / "java"
    if not java_bin.exists():
        java_bin = Path(shutil.which("java") or "java")

    cmd = [
        str(java_bin),
        "-cp",
        classpath(),
        "org.fog.test.dynacol.DynaColEvaluationRunner",
        policy,
        str(nodes),
        scenario,
        str(trial),
        str(seed),
        str(out_path),
        variant or "",
    ]
    if wt_ms > 0:
        cmd.append(str(wt_ms))
    else:
        cmd.append("")
    if max_colony_rtt_ms > 0:
        cmd.append(str(max_colony_rtt_ms))
    else:
        cmd.append("")
    if bootstrap_fraction >= 0:
        cmd.append(str(bootstrap_fraction))
    cmd.append(str(sla_deadline_scale))
    if n_slope > 0:
        cmd.append(str(n_slope))

    print(
        f"  trial policy={policy} variant={variant} nodes={nodes} scenario={scenario} "
        f"trial={trial} wt={wt_ms} mc={max_colony_rtt_ms} bf={bootstrap_fraction} "
        f"sla_scale={sla_deadline_scale} n_slope={n_slope}",
        flush=True,
    )
    subprocess.run(cmd, check=True, cwd=IFOG, env=os.environ.copy())


def iter_jobs(
    policies: list[str],
    nodes: list[int],
    scenarios: list[str],
    trials: int,
    ablation: bool = False,
    sensitivity: bool = False,
    incremental: bool = False,
    sla_deadline: bool = False,
    sla_slope: bool = False,
    sla_scales: list[float] | None = None,
    n_slopes: list[float] | None = None,
):
    if sla_slope:
        slopes = n_slopes or SLADEADLINE_DEFAULT_N_SLOPES
        for slope in slopes:
            variant = sla_n_slope_variant(slope)
            for policy in policies:
                for scenario in scenarios:
                    for trial in range(1, trials + 1):
                        seed = BASE_SEED + trial + stable_seed_offset(variant, policy, 500, scenario)
                        yield policy, 500, scenario, trial, seed, variant, -1.0, -1.0, 1.0, 1.0, slope
        return
    if sla_deadline:
        scales = sla_scales or SLADEADLINE_DEFAULT_SCALES
        for scale in scales:
            variant = sla_deadline_variant(scale)
            for policy in policies:
                for scenario in scenarios:
                    for trial in range(1, trials + 1):
                        seed = BASE_SEED + trial + stable_seed_offset(variant, policy, 500, scenario)
                        yield policy, 500, scenario, trial, seed, variant, -1.0, -1.0, 1.0, scale, -1.0
        return
    if sensitivity:
        for wt in SENSITIVITY_WT:
            for mc in SENSITIVITY_MAX_COLONY:
                variant = f"wt{wt}_mc{mc}"
                for scenario in scenarios:
                    for trial in range(1, trials + 1):
                        seed = BASE_SEED + trial + stable_seed_offset(variant, 500, scenario)
                        yield "dcbo", 500, scenario, trial, seed, variant, wt, mc, 1.0, 1.0, -1.0
        return
    if incremental:
        for policy in policies:
            for node_count in nodes:
                for scenario in scenarios:
                    if node_count > 100 and scenario in ("mobility", "mobility_burst"):
                        continue
                    for trial in range(1, trials + 1):
                        seed = BASE_SEED + trial + stable_seed_offset(policy, node_count, scenario, "incr")
                        if scenario == "mobility":
                            scen = "incremental_mobility"
                        elif scenario == "mobility_burst":
                            scen = "incremental_mobility_burst"
                        elif "burst" in scenario:
                            scen = "incremental_burst"
                        else:
                            scen = "incremental_arrival"
                        yield policy, node_count, scen, trial, seed, "", -1.0, -1.0, 0.5, 1.0, -1.0
        return
    if ablation:
        for variant in policies:
            for scenario in scenarios:
                for trial in range(1, trials + 1):
                    seed = BASE_SEED + trial + stable_seed_offset(variant, 500, scenario)
                    yield "dcbo", 500, scenario, trial, seed, variant, -1.0, -1.0, 1.0, 1.0, -1.0
        return
    for policy in policies:
        for node_count in nodes:
            for scenario in scenarios:
                for trial in range(1, trials + 1):
                    seed = BASE_SEED + trial + stable_seed_offset(policy, node_count, scenario)
                    yield policy, node_count, scenario, trial, seed, "", -1.0, -1.0, 1.0, 1.0, -1.0


def main() -> None:
    global OUT
    setup_java_env()
    ablation = os.environ.get("ABLATION", "").strip() in {"1", "true", "yes"}
    sensitivity = os.environ.get("SENSITIVITY", "").strip() in {"1", "true", "yes"}
    sla_deadline = os.environ.get("SLADEADLINE", "").strip() in {"1", "true", "yes"}
    sla_slope = os.environ.get("SLADEADLINE_SLOPE", "").strip() in {"1", "true", "yes"}
    incremental = os.environ.get("INCREMENTAL", "").strip() in {"1", "true", "yes"}
    greedy_grid = os.environ.get("GREEDY_GRID", "").strip() in {"1", "true", "yes"}
    offline_ga = os.environ.get("OFFLINE_GA", "").strip() in {"1", "true", "yes"}
    energy_grid = os.environ.get("ENERGY_GRID", "").strip() in {"1", "true", "yes"}
    quick = os.environ.get("QUICK", "").strip() in {"1", "true", "yes"}
    resume = os.environ.get("RESUME", "").strip() in {"1", "true", "yes"}
    fresh = os.environ.get("FRESH", "").strip() in {"1", "true", "yes"}
    trials = int(os.environ.get("TRIALS", "3" if quick else "10"))
    workers = max(1, int(os.environ.get("WORKERS", "1")))
    policy_filter = os.environ.get("POLICY", "").strip().lower()

    if energy_grid:
        nodes = [100, 300, 500, 1000]
        scenarios = ["normal", "burst", "churn"]
        policies = ["dcbo", "static_dcbo", "fogplan", "edgeward", "greedy", "tavousi", "dogani"]
        OUT = OUT.parent / "trials_energy.csv"
    elif offline_ga:
        nodes = [500]
        scenarios = ["normal", "burst", "churn"]
        policies = ["offline_ga"]
        OUT = OUT.parent / "trials_offline_ga.csv"
    elif sla_slope:
        nodes = [500]
        scenarios = ["normal", "burst", "churn"]
        policies = SLADEADLINE_POLICIES
        trials = int(os.environ.get("TRIALS", "5"))
        OUT = OUT.parent / "trials_sla_slope.csv"
    elif sla_deadline:
        nodes = [500]
        scenarios = ["normal", "burst", "churn"]
        policies = SLADEADLINE_POLICIES
        trials = int(os.environ.get("TRIALS", "5"))
        OUT = OUT.parent / "trials_sla_deadline.csv"
    elif sensitivity:
        nodes = [500]
        scenarios = ["normal", "burst"]
        policies = ["dcbo"]
        trials = int(os.environ.get("TRIALS", "5"))
        OUT = OUT.parent / "trials_sensitivity.csv"
    elif incremental:
        incr_nodes = os.environ.get("INCREMENTAL_NODES", "100,300").strip()
        nodes = [int(x.strip()) for x in incr_nodes.split(",") if x.strip()]
        scenarios = ["normal", "burst", "mobility", "mobility_burst"]
        policies = ["dcbo", "static_dcbo"]
        trials = int(os.environ.get("TRIALS", "5"))
        OUT = OUT.parent / "trials_incremental.csv"
    elif greedy_grid:
        nodes = [100, 300, 500, 1000]
        scenarios = ["normal", "burst", "churn"]
        policies = ["greedy"]
        OUT = OUT.parent / "trials_greedy.csv"
    elif ablation:
        nodes = [500]
        scenarios = ["normal", "burst"]
        policies = ABLATION_VARIANTS
        OUT = OUT.parent / "trials_ablation.csv"
    else:
        nodes = parse_node_list(quick)
        scenarios = parse_scenario_list(quick)
        policies = POLICIES
        if policy_filter:
            if policy_filter not in POLICIES:
                raise SystemExit(f"Unknown POLICY={policy_filter}; expected one of {POLICIES}")
            policies = [policy_filter]
            OUT = OUT.parent / f"trials_{policy_filter}.csv"

    OUT.parent.mkdir(parents=True, exist_ok=True)
    if fresh:
        OUT.unlink(missing_ok=True)

    auto_resume = OUT.exists() and not fresh
    completed = load_completed(ablation, sensitivity, incremental, sla_deadline or sla_slope, sla_slope) if (resume or auto_resume) else set()
    if completed:
        print(f"Resume: skipping {len(completed)} completed trials")

    ensure_compiled()
    sla_scales = parse_sla_deadline_scales() if sla_deadline else None
    n_slopes = parse_sla_n_slopes() if sla_slope else None
    if sla_deadline:
        total = len(policies) * len(nodes) * len(scenarios) * trials * len(sla_scales or SLADEADLINE_DEFAULT_SCALES)
    elif sla_slope:
        total = len(policies) * len(nodes) * len(scenarios) * trials * len(n_slopes or SLADEADLINE_DEFAULT_N_SLOPES)
    else:
        total = len(policies) * len(nodes) * len(scenarios) * trials
    mode = (
        "energy_grid" if energy_grid else
        "offline_ga" if offline_ga else
        "sla_slope" if sla_slope else
        "sla_deadline" if sla_deadline else
        "sensitivity" if sensitivity else
        "incremental" if incremental else
        "greedy" if greedy_grid else
        "ablation" if ablation else "main"
    )
    print(f"Running up to {total} iFogSim2 trials ({mode}) -> {OUT} (workers={workers})")

    pending = []
    skipped = 0
    n = 0
    for job in iter_jobs(
        policies, nodes, scenarios, trials,
        ablation, sensitivity, incremental, sla_deadline, sla_slope, sla_scales, n_slopes,
    ):
        n += 1
        policy, node_count, scenario, trial, seed, variant, wt, mc, bf, sla_scale, n_slope = job
        if sla_deadline or sla_slope:
            key = (variant, policy, node_count, scenario, trial)
        elif ablation or sensitivity:
            key = (variant, node_count, scenario, trial)
        else:
            key = (policy, node_count, scenario, trial)
        if key in completed:
            skipped += 1
            continue
        pending.append((n, policy, node_count, scenario, trial, seed, variant, wt, mc, bf, sla_scale, n_slope))

    if not pending:
        print(f"Done. Wrote {OUT} (skipped {skipped} existing trials)")
        return

    def _run(job: tuple) -> None:
        idx, policy, node_count, scenario, trial, seed, variant, wt, mc, bf, sla_scale, n_slope = job
        print(f"[{idx}/{total}] (skipped={skipped})", end=" ", flush=True)
        run_trial(policy, node_count, scenario, trial, seed, OUT, variant=variant,
                  wt_ms=wt, max_colony_rtt_ms=mc, bootstrap_fraction=bf,
                  sla_deadline_scale=sla_scale, n_slope=n_slope)

    if workers == 1:
        for job in pending:
            _run(job)
    else:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(_run, job) for job in pending]
            for fut in as_completed(futures):
                fut.result()

    print(f"Done. Wrote {OUT} (skipped {skipped} existing trials)")


if __name__ == "__main__":
    main()
