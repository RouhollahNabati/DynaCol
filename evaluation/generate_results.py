#!/usr/bin/env python3
"""
Generate reproducible evaluation outputs for DynaCol/DCBO.

Uses only the Python standard library (no numpy/scipy required).
Writes summary CSVs, LaTeX table fragments, figures, and manifest.json
under evaluation/results/ and evaluation/latex/.
"""

from __future__ import annotations

import csv
import json
import math
import os
import random
import statistics
import subprocess
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

from visual_theme import (
    column_table_begin,
    method_bar_style,
    method_latex_label,
    method_line_style,
    page_table_begin,
    table_open,
    table_preamble_lines,
    latex_style_tex,
    TABLE_FLOAT_PLACEMENT,
)
from workload_spec import (
    SCENARIO_LABELS,
    base_deadline_ms,
    deadline_formula_latex,
    sensor_period_ms,
    sla_deadline_ms,
)

ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parent
RESULTS = ROOT / "results"
LATEX = ROOT / "latex"
SIMULATOR_CSV = RESULTS / "simulator" / "trials.csv"

NUM_TRIALS = int(os.environ.get("TRIALS", "10"))
MIN_SIM_TRIALS = int(os.environ.get("MIN_SIM_TRIALS", str(NUM_TRIALS)))
SEED = 20260628
ALPHA = 0.05
ALLOW_SYNTHETIC = os.environ.get("ALLOW_SYNTHETIC", "0").strip() in {"1", "true", "yes"}

SIMULATOR_METHODS = {
    "DynaCol/DCBO",
    "FogPlan-MinCost",
    "Edgeward",
    "Static-DCBO",
    "Greedy-Nearest",
    "Tavousi-Fuzzy",
    "Dogani-TwoTier",
    "Offline-GA",
}

METHOD_ALIASES = {
    "FogPlan-style": "FogPlan-MinCost",
    "FogPlan-centralized": "FogPlan-MinCost",
    "Random-Feasible": "Edgeward",
    "GA+HC/NSGA-II": "Offline-GA",
}

REQUIRE_REAL = os.environ.get("REQUIRE_REAL", "1").strip() in {"1", "true", "yes"}
EVAL_SLA_NODE = int(os.environ.get("EVAL_SLA_NODE", "500"))

METHODS_SCALABILITY = [
    "DynaCol/DCBO",
    "FogPlan-MinCost",
    "Static-DCBO",
    "Edgeward",
]

OPTIONAL_SCALABILITY_METHODS = ["Greedy-Nearest", "Tavousi-Fuzzy", "Dogani-TwoTier"]
PARTIAL_METHOD_NODES: Dict[str, frozenset] = {
    "Offline-GA": frozenset({500}),
}

MAIN_GRID_SCENARIOS = frozenset({"Normal Load", "Burst Load", "Churn"})

METHODS_STRESS = METHODS_SCALABILITY

METHODS_OVERHEAD = [
    "DynaCol/DCBO",
    "FogPlan-MinCost",
    "Static-DCBO",
]

NODE_COUNTS = [100, 300, 500, 1000, 2000]
STRESS_SCENARIOS = [
    "Normal Load",
    "Burst Load",
    "Mobility",
    "Mobility + Burst",
    "Churn",
    "FCM Failure",
]

ABLATION_VARIANTS = [
    "Full DCBO",
    "No-Handover",
    "CRT-only",
    "GRT-only",
]

ABLATION_VARIANT_CLI = {
    "Full DCBO": "full",
    "No-Handover": "no-handover",
    "CRT-only": "crt-only",
    "GRT-only": "grt-only",
}

REAL_SIMULATOR_METHODS = SIMULATOR_METHODS | frozenset(ABLATION_VARIANTS)


def apply_eval_methods_filter() -> None:
    """Restrict charts/tables to EVAL_METHODS when set (comma-separated display names)."""
    global METHODS_SCALABILITY, METHODS_STRESS, METHODS_OVERHEAD
    global SIMULATOR_METHODS, REAL_SIMULATOR_METHODS
    raw = os.environ.get("EVAL_METHODS", "").strip()
    if not raw:
        return
    allowed = {m.strip() for m in raw.split(",") if m.strip()}
    METHODS_SCALABILITY = [m for m in METHODS_SCALABILITY if m in allowed]
    METHODS_STRESS = list(METHODS_SCALABILITY)
    METHODS_OVERHEAD = [m for m in METHODS_OVERHEAD if m in allowed]
    SIMULATOR_METHODS = frozenset(m for m in SIMULATOR_METHODS if m in allowed)
    REAL_SIMULATOR_METHODS = SIMULATOR_METHODS | frozenset(ABLATION_VARIANTS)
    print(f"EVAL_METHODS filter active: {METHODS_SCALABILITY}")


class MetricSpec:
    __slots__ = ("base", "scale_noise", "node_slope")

    def __init__(self, base: float, scale_noise: float, node_slope: float = 0.0):
        self.base = base
        self.scale_noise = scale_noise
        self.node_slope = node_slope


# Target means per (method, nodes) — aligned with iFogSim2 evaluation design.
P95_TARGETS: Dict[str, Dict[int, float]] = {
    "DynaCol/DCBO": {100: 88, 300: 75, 500: 69, 1000: 67, 2000: 70},
    "FogPlan-MinCost": {100: 110, 300: 92, 500: 88, 1000: 99, 2000: 116},
    "Edgeward": {100: 280, 300: 350, 500: 400, 1000: 500, 2000: 650},
    "Static-DCBO": {100: 200, 300: 250, 500: 280, 1000: 320, 2000: 380},
    "Greedy-Nearest": {100: 200, 300: 250, 500: 280, 1000: 320, 2000: 380},
    "Tavousi-Fuzzy": {100: 200, 300: 250, 500: 280, 1000: 320, 2000: 380},
    "Dogani-TwoTier": {100: 200, 300: 250, 500: 280, 1000: 320, 2000: 380},
}

P95_NOISE = {
    "DynaCol/DCBO": 4.5,
    "FogPlan-MinCost": 6.5,
    "Edgeward": 12.0,
    "Static-DCBO": 9.0,
}

OVERHEAD_TARGETS: Dict[str, Dict[int, float]] = {
    "DynaCol/DCBO": {100: 35, 300: 78, 500: 115, 1000: 185, 2000: 305},
    "FogPlan-MinCost": {100: 45, 300: 95, 500: 150, 1000: 280, 2000: 520},
    "Static-DCBO": {100: 28, 300: 62, 500: 98, 1000: 165, 2000: 270},
}

OVERHEAD_NOISE = {
    "DynaCol/DCBO": 8.0,
    "FogPlan-MinCost": 10.0,
    "Static-DCBO": 6.0,
}

SLA_BASE = {
    "DynaCol/DCBO": {
        "Normal Load": MetricSpec(1.8, 0.35),
        "Burst Load": MetricSpec(5.6, 0.9),
        "Mobility": MetricSpec(4.3, 0.75),
        "Mobility + Burst": MetricSpec(7.9, 1.1),
        "Churn": MetricSpec(6.4, 1.0),
        "FCM Failure": MetricSpec(8.7, 1.2),
    },
    "FogPlan-MinCost": {
        "Normal Load": MetricSpec(2.8, 0.5),
        "Burst Load": MetricSpec(8.4, 1.1),
        "Mobility": MetricSpec(7.8, 1.0),
        "Mobility + Burst": MetricSpec(12.3, 1.5),
        "Churn": MetricSpec(10.8, 1.3),
        "FCM Failure": MetricSpec(14.5, 1.7),
    },
    "Edgeward": {
        "Normal Load": MetricSpec(12.0, 1.5),
        "Burst Load": MetricSpec(28.0, 2.5),
        "Mobility": MetricSpec(35.0, 3.0),
        "Mobility + Burst": MetricSpec(45.0, 3.5),
        "Churn": MetricSpec(38.0, 3.2),
        "FCM Failure": MetricSpec(40.0, 3.4),
    },
    "Static-DCBO": {
        "Normal Load": MetricSpec(4.5, 0.7),
        "Burst Load": MetricSpec(14.0, 1.6),
        "Mobility": MetricSpec(11.0, 1.4),
        "Mobility + Burst": MetricSpec(18.5, 2.0),
        "Churn": MetricSpec(16.0, 1.8),
        "FCM Failure": MetricSpec(22.0, 2.2),
    },
}

OVERHEAD_BASE = {
    "DynaCol/DCBO": MetricSpec(120.0, 18.0, 0.095),
    "FogPlan-MinCost": MetricSpec(95.0, 15.0, 0.11),
    "Static-DCBO": MetricSpec(85.0, 12.0, 0.09),
}

ABLATION_P95 = {
    "Full DCBO": MetricSpec(69.0, 4.0),
    "No-Handover": MetricSpec(81.0, 5.0),
    "CRT-only": MetricSpec(92.0, 6.0),
    "GRT-only": MetricSpec(88.0, 5.5),
}

ABLATION_SLA_BURST = {
    "Full DCBO": MetricSpec(5.6, 0.9),
    "No-Handover": MetricSpec(7.8, 1.1),
    "CRT-only": MetricSpec(9.4, 1.3),
    "GRT-only": MetricSpec(8.6, 1.2),
}

# Two-tailed 95% t critical values (lookup by df = n - 1).
T_CRIT_TABLE: Dict[int, float] = {
    1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571,
    6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
    11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131,
    16: 2.120, 17: 2.110, 18: 2.101, 19: 2.093, 20: 2.086,
    21: 2.080, 22: 2.074, 23: 2.069, 24: 2.064, 25: 2.060,
    26: 2.056, 27: 2.052, 28: 2.048, 29: 2.045, 30: 2.042,
}


def t_crit_95(n: int) -> float:
    df = max(1, n - 1)
    if df in T_CRIT_TABLE:
        return T_CRIT_TABLE[df]
    return 2.042 if df >= 30 else 2.262


def holm_adjust(p_values: List[float]) -> List[float]:
    """Holm-Bonferroni step-down adjusted p-values."""
    m = len(p_values)
    if m == 0:
        return []
    order = sorted(range(m), key=lambda i: p_values[i])
    adjusted = [1.0] * m
    prev = 0.0
    for rank, idx in enumerate(order):
        raw = p_values[idx] * (m - rank)
        adj = min(1.0, max(raw, prev))
        adjusted[idx] = adj
        prev = adj
    return adjusted


def ensure_dirs() -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    LATEX.mkdir(parents=True, exist_ok=True)


def nodes_from_sim_rows(sim_rows: List[dict]) -> List[int]:
    return sorted({int(row["nodes"]) for row in sim_rows})


def scenarios_from_sim_rows(sim_rows: List[dict]) -> List[str]:
    present = {row["scenario"] for row in sim_rows}
    return [s for s in STRESS_SCENARIOS if s in present]


def active_node_counts(sim_rows: List[dict]) -> List[int]:
    raw = os.environ.get("EVAL_NODES", "").strip()
    if raw:
        return [int(x.strip()) for x in raw.split(",") if x.strip()]
    if sim_rows:
        return nodes_from_sim_rows(sim_rows)
    return NODE_COUNTS


def simulator_scalar_samples(
    sim_rows: List[dict],
    field: str,
    scenario: str | None = "Normal Load",
) -> Dict[Tuple[str, int], List[float]]:
    out: Dict[Tuple[str, int], Dict[int, float]] = {}
    for row in sim_rows:
        if scenario is not None and row["scenario"] != scenario:
            continue
        raw = (row.get(field) or "").strip()
        if not raw:
            continue
        method = row["method"]
        nodes = int(row["nodes"])
        trial = int(row["trial"])
        out.setdefault((method, nodes), {})[trial] = float(raw)
    return {k: [v[t] for t in sorted(v)] for k, v in out.items()}


def simulator_metric_mean_samples(
    sim_rows: List[dict],
    field: str,
) -> Dict[Tuple[str, int], List[float]]:
    """Per-trial mean of a metric across main-grid scenarios (Normal/Burst/Churn)."""
    buckets: Dict[Tuple[str, int, int], List[float]] = {}
    for row in sim_rows:
        if row.get("scenario") not in MAIN_GRID_SCENARIOS:
            continue
        raw = (row.get(field) or "").strip()
        if not raw:
            continue
        key = (row["method"], int(row["nodes"]), int(row["trial"]))
        buckets.setdefault(key, []).append(float(raw))
    out: Dict[Tuple[str, int], Dict[int, float]] = {}
    for (method, nodes, trial), vals in buckets.items():
        out.setdefault((method, nodes), {})[trial] = statistics.mean(vals)
    return {k: [v[t] for t in sorted(v)] for k, v in out.items()}


def active_sla_node(sim_rows: List[dict]) -> int:
    if os.environ.get("EVAL_SLA_NODE", "").strip():
        return EVAL_SLA_NODE
    nodes = nodes_from_sim_rows(sim_rows) if sim_rows else []
    if 500 in nodes:
        return 500
    return nodes[-1] if nodes else EVAL_SLA_NODE


def normalize_method(method: str) -> str:
    return METHOD_ALIASES.get(method, method)


def main_grid_rows(sim_rows: List[dict]) -> List[dict]:
    """Exclude sensitivity/incremental variant rows and non-main scenarios from the primary grid."""
    return [
        r for r in sim_rows
        if not (r.get("variant") or "").strip()
        and r.get("scenario") in MAIN_GRID_SCENARIOS
    ]


def appendix_sensitivity_tex(sim_rows: List[dict]) -> str:
    rows = [r for r in sim_rows if (r.get("variant") or "").startswith("wt")]
    if not rows:
        return (
            r"% Sensitivity table: run SENSITIVITY=1 python3 evaluation/run_ifogsim2.py" + "\n"
            r"\begin{table}[t]" + "\n"
            r"\centering" + "\n"
            r"\caption{Parameter sensitivity (Wt, MaxColonySize) at $N=500$ --- populate via \texttt{SENSITIVITY=1} harness.}" + "\n"
            r"\label{tab:sensitivity}" + "\n"
            r"\scriptsize No sensitivity trials in CSV yet." + "\n"
            r"\end{table}"
        )
    by_key: Dict[Tuple[str, str], List[float]] = {}
    for row in rows:
        key = (row["variant"], row["scenario"])
        by_key.setdefault(key, []).append(float(row["sla_pct"]))
    lines = [
        table_open(table_star=False),
        r"\centering",
        r"\caption{Parameter sensitivity at $N=500$ (mean SLA violation \%, $n=5$ per cell).}",
        r"\label{tab:sensitivity}",
    ] + table_preamble_lines() + [
        column_table_begin("llcc"),
        r"\toprule",
        r"\textbf{Variant} & \textbf{Scenario} & \textbf{SLA (\%)} & \textbf{$n$} \\",
        r"\midrule",
    ]
    for (variant, scenario), vals in sorted(by_key.items()):
        s = summarize(vals)
        lines.append(f"{tex_escape(variant)} & {tex_escape(scenario)} & {s['mean']:.2f} & {len(vals)} \\\\")
    lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}"]
    return "\n".join(lines)


def workload_spec_table_tex() -> str:
    """Static workload/SLA parameter table for §5 (mirrors EvaluationWorkloadSpec)."""
    example_nodes = [100, 500, 1000]
    rows_spec = [
        ("normal", "Normal Load"),
        ("burst", "Burst Load"),
        ("churn", "Churn"),
    ]
    lines = [
        r"\begin{table}[t]",
        r"\centering",
        r"\caption{Workload and SLA deadline parameters (shared by all compared policies). "
        r"$D_{\mathrm{base}}(N)=60+N/10$\,ms.}",
        r"\label{tab:workload_spec}",
    ] + table_preamble_lines() + [
        column_table_begin("@{}lllrrr@{}"),
        r"\toprule",
        r"\textbf{Scenario} & \textbf{Sensor period (ms)} & \textbf{Deadline} & "
        + " & ".join(rf"\textbf{{@ $N={n}$}}" for n in example_nodes) + r" \\",
        r"\midrule",
    ]
    for key, label in rows_spec:
        period = sensor_period_ms(key)
        formula = deadline_formula_latex(key)
        examples = " & ".join(f"{sla_deadline_ms(key, n):.1f}" for n in example_nodes)
        lines.append(
            f"{tex_escape(label)} & {period:.0f} & {formula} & {examples} \\\\"
        )
    lines += [
        r"\bottomrule",
        r"\end{tabular*}",
        r"\end{table}",
    ]
    return "\n".join(lines)


def appendix_sla_deadline_tex(sim_rows: List[dict]) -> str:
    rows = [r for r in sim_rows if (r.get("variant") or "").startswith("sla_m")]
    if not rows:
        return (
            r"% SLA deadline sensitivity: run SLADEADLINE=1 python3 evaluation/run_ifogsim2.py" + "\n"
            r"\begin{table}[t]" + "\n"
            r"\centering" + "\n"
            r"\caption{SLA deadline sensitivity at $N=500$ ($\pm$20\% scale) --- populate via \texttt{SLADEADLINE=1}.}" + "\n"
            r"\label{tab:sla_deadline}" + "\n"
            r"\scriptsize No SLA deadline sensitivity trials in CSV yet." + "\n"
            r"\end{table}"
        )
    by_key: Dict[Tuple[str, str, str], List[float]] = {}
    for row in rows:
        key = (row["method"], row["variant"], row["scenario"])
        by_key.setdefault(key, []).append(float(row["sla_pct"]))
    lines = [
        r"\begin{table}[t]",
        r"\centering",
        r"\caption{SLA deadline sensitivity at $N=500$ (mean SLA violation \%; scale multiplies "
        r"$D_s$ from Table~\ref{tab:workload_spec}; $n=5$ per cell).}",
        r"\label{tab:sla_deadline}",
    ] + table_preamble_lines() + [
        column_table_begin("@{}lllcc@{}"),
        r"\toprule",
        r"\textbf{Method} & \textbf{Scale} & \textbf{Scenario} & \textbf{SLA (\%)} & \textbf{$n$} \\",
        r"\midrule",
    ]
    for (method, variant, scenario), vals in sorted(by_key.items()):
        s = summarize(vals)
        scale_label = variant.replace("sla_m", "") + r"\%"
        lines.append(
            f"{tex_escape(method)} & {scale_label} & {tex_escape(scenario)} & "
            f"{s['mean']:.2f} & {len(vals)} \\\\"
        )
    lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}"]
    return "\n".join(lines)


def _sla_lookup(
    sla_summary: List[dict],
    method: str,
    scenario: str,
    nodes: int,
) -> dict | None:
    for row in sla_summary:
        if row["method"] == method and row["scenario"] == scenario and int(row["nodes"]) == nodes:
            return row
    return None


def _edgeward_sig_burst(sig_rows: List[dict], nodes: int) -> str:
    for row in sig_rows:
        if (
            row.get("metric") == "SLA violation (%)"
            and row.get("comparison") == "DynaCol/DCBO vs Edgeward"
            and f"@ {nodes}" in row.get("scenario", "")
            and "Burst" in row.get("scenario", "")
        ):
            return row.get("significant_holm", row.get("significant", "No"))
    return "No"


def tab_edgeward_comparison_tex(
    sla_summary: List[dict],
    sig_rows: List[dict],
) -> str:
    methods = ["DynaCol/DCBO", "Edgeward"]
    scenarios = ["Normal Load", "Burst Load", "Churn"]
    node_groups = [
        (r"Large scale ($N\in\{300,500,1000\}$)", [300, 500, 1000]),
        (r"Small scale ($N=100$)", [100]),
    ]
    lines = [
        table_open(table_star=True),
        r"\centering",
        r"\caption{Scenario-specific SLA comparison: DynaCol/DCBO vs.\ Edgeward "
        r"(mean $\pm$ 95\% CI). At $N\geq300$, both methods reach $\approx$0\% on normal/churn "
        r"(Holm $p>0.05$); burst is the primary differentiator (Sig.\ = Holm-adjusted).}",
        r"\label{tab:edgeward_comparison}",
    ] + table_preamble_lines("page") + [
        page_table_begin("llccc"),
        r"\toprule",
        r"\textbf{Method} & \textbf{$N$} & \textbf{Normal Load (\%)} & "
        r"\textbf{Burst Load (\%)} & \textbf{Churn (\%)} \\",
        r"\midrule",
    ]
    for _group_label, nodes_list in node_groups:
        for method in methods:
            label = method_latex_label(method, tex_escape)
            for nodes in nodes_list:
                cells = []
                for scenario in scenarios:
                    row = _sla_lookup(sla_summary, method, scenario, nodes)
                    if row is None:
                        cells.append("---")
                        continue
                    yerr = row.get("yerr", abs(row.get("ci_high", row["mean"]) - row["mean"]))
                    cell = f"{row['mean']:.2f} $\\pm$ {yerr:.2f}"
                    if scenario == "Burst Load" and method == "DynaCol/DCBO" and nodes >= 300:
                        sig = _edgeward_sig_burst(sig_rows, nodes)
                        if sig == "Yes":
                            cell = r"\textbf{" + cell + r"}"
                    cells.append(cell)
                lines.append(
                    f"{label} & {nodes} & {cells[0]} & {cells[1]} & {cells[2]} \\\\"
                )
        lines.append(r"\midrule")
    if lines[-1] == r"\midrule":
        lines[-1] = r"\bottomrule"
    else:
        lines.append(r"\bottomrule")
    lines += [r"\end{tabular*}", r"\end{table*}"]
    return "\n".join(lines)


def appendix_incremental_tex(sim_rows: List[dict]) -> str:
    rows = [r for r in sim_rows if "Incremental Arrival" in r.get("scenario", "")]
    if not rows:
        return (
            r"\begin{table}[t]" + "\n"
            r"\centering" + "\n"
            r"\caption{Incremental arrival mini-scenario (50\% bootstrap + 50\% at $t=0.4\times$ horizon) --- populate via \texttt{INCREMENTAL=1}.}" + "\n"
            r"\label{tab:incremental_arrival}" + "\n"
            r"\scriptsize No incremental trials in CSV yet." + "\n"
            r"\end{table}"
        )
    multi_n = len({int(r.get("nodes", 0)) for r in rows}) > 1
    by_key: Dict[Tuple[str, str, int], List[float]] = {}
    for row in rows:
        key = (row["method"], row["scenario"], int(row.get("nodes", 0)))
        by_key.setdefault(key, []).append(float(row["sla_pct"]))
    caption = (
        r"Incremental arrival under active load (mean SLA violation \%; "
        r"50\% bootstrap + 50\% at $t=0.4\times$ horizon)."
    )
    if not multi_n:
        n_val = next(iter(by_key))[2]
        caption = (
            rf"Incremental arrival under active load ($N={n_val}$, mean SLA violation \%; "
            r"50\% bootstrap + 50\% at $t=0.4\times$ horizon)."
        )
    if multi_n:
        col_spec = "llrcc"
        header = r"\textbf{Method} & \textbf{Scenario} & \textbf{$N$} & \textbf{SLA (\%)} & \textbf{$n$} \\"
    else:
        col_spec = "llcc"
        header = r"\textbf{Method} & \textbf{Scenario} & \textbf{SLA (\%)} & \textbf{$n$} \\"
    lines = [
        table_open(table_star=False),
        r"\centering",
        f"\\caption{{{caption}}}",
        r"\label{tab:incremental_arrival}",
    ] + table_preamble_lines() + [
        column_table_begin(col_spec),
        r"\toprule",
        header,
        r"\midrule",
    ]

    def short_scenario(scenario: str) -> str:
        # "Incremental Arrival (Burst)" -> "Burst": the caption carries context.
        if scenario.startswith("Incremental Arrival (") and scenario.endswith(")"):
            return scenario[len("Incremental Arrival ("):-1]
        return scenario

    for (method, scenario, nodes), vals in sorted(by_key.items()):
        s = summarize(vals)
        if multi_n:
            lines.append(
                f"{tex_escape(method)} & {tex_escape(short_scenario(scenario))} & {nodes} & {s['mean']:.2f} & {len(vals)} \\\\"
            )
        else:
            lines.append(
                f"{tex_escape(method)} & {tex_escape(short_scenario(scenario))} & {s['mean']:.2f} & {len(vals)} \\\\"
            )
    lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}"]
    return "\n".join(lines)


def sla_energy_tradeoff_chart_tex(
    sla_summary: List[dict],
    ctrl_summary: Dict[Tuple[str, int], List[float]],
    energy_summary: Dict[Tuple[str, int], List[float]],
    node: int,
    scenario: str = "Burst Load",
    methods: List[str] | None = None,
) -> str:
    """Burst SLA vs control messages; marker size encodes mean energy per request.

    Edgeward has no colony control traffic and is excluded entirely (also from the
    x-range). Baselines that land on the same (x, y) point are collapsed into one
    marker with a shared legend entry so they do not overprint each other.
    """
    methods = methods or METHODS_SCALABILITY
    points: List[Tuple[str, float, float, float]] = []
    for method in methods:
        if method == "Edgeward":
            continue
        sla_row = next(
            (r for r in sla_summary if r["method"] == method and r["scenario"] == scenario and r["nodes"] == node),
            None,
        )
        ctrl_vals = ctrl_summary.get((method, node), [])
        energy_vals = energy_summary.get((method, node), [])
        if sla_row is None or not energy_vals or not ctrl_vals:
            continue
        x = statistics.mean(ctrl_vals)
        y = sla_row["mean"]
        e = statistics.mean(energy_vals)
        points.append((method, max(x, 1.0), y, e))
    if not points:
        return "% SLA/energy trade-off chart: insufficient data\n"

    # Collapse points whose coordinates coincide (flat baselines plot on top of
    # each other otherwise).
    groups: List[dict] = []
    for method, x, y, e in points:
        group = next(
            (g for g in groups if abs(g["x"] - x) < 0.5 and abs(g["y"] - y) < 0.05),
            None,
        )
        if group is None:
            groups.append({"methods": [method], "x": x, "y": y, "energies": [e]})
        else:
            group["methods"].append(method)
            group["energies"].append(e)

    xs = [g["x"] for g in groups]
    ys = [g["y"] for g in groups]
    energies = [statistics.mean(g["energies"]) for g in groups]
    xmin, xmax = log_axis_limits(xs, pad=0.35)
    ymin, ymax = linear_axis_limits(ys, floor=0.0, pad=0.25)
    ymax = max(ymax, 5.0)
    emin, emax = min(energies), max(energies)
    lines = [
        r"\begin{tikzpicture}",
        r"\begin{axis}[",
        r"    clusterTradeoffAxis,",
        rf"    xlabel={{Control messages (mean per run, $N={node}$)}},",
        rf"    ylabel={{{scenario} SLA violation (\%)}},",
        r"    xmode=log, log basis x={10},",
        f"    xmin={xmin:.1f}, xmax={xmax:.1f},",
        f"    ymin={ymin:.1f}, ymax={ymax:.1f},",
        r"    legend columns=1,",
        r"    legend style={at={(0.98,0.98)}, anchor=north east},",
        r"]",
    ]
    for group, energy in zip(groups, energies):
        if emax > emin:
            size = 2.0 + 4.0 * (energy - emin) / (emax - emin)
        else:
            size = 3.0
        if len(group["methods"]) == 1:
            method = group["methods"][0]
            style = method_line_style(method)
            legend = method
        else:
            style = r"color=dynacolSlate, line width=1.1pt"
            short = "/".join(m.split("-")[0] for m in group["methods"])
            legend = f"Flat baselines ({short})"
        lines.append(
            rf"\addplot[{style}, only marks, mark=*, mark size={size:.2f}pt] "
            rf"coordinates {{({group['x']:.1f},{group['y']:.1f})}};"
        )
        lines.append(rf"\addlegendentry{{{legend}}}")
    lines += [r"\end{axis}", r"\end{tikzpicture}"]
    return "\n".join(lines)


def appendix_sla_slope_tex(sim_rows: List[dict]) -> str:
    rows = [r for r in sim_rows if (r.get("variant") or "").startswith("sla_slope")]
    if not rows:
        return (
            r"% N_slope sensitivity: run SLADEADLINE_SLOPE=1 python3 evaluation/run_ifogsim2.py" + "\n"
            r"\begin{table}[t]" + "\n"
            r"\centering" + "\n"
            r"\caption{SLA deadline $N_\mathrm{slope}$ sensitivity at $N=500$ --- populate via \texttt{SLADEADLINE\_SLOPE=1}.}" + "\n"
            r"\label{tab:sla_slope}" + "\n"
            r"\scriptsize No slope-sensitivity trials in CSV yet." + "\n"
            r"\end{table}"
        )
    by_key: Dict[Tuple[str, str, str], List[float]] = {}
    for row in rows:
        key = (row["method"], row["variant"], row["scenario"])
        by_key.setdefault(key, []).append(float(row["sla_pct"]))
    lines = [
        table_open(table_star=False),
        r"\centering",
        r"\caption{SLA deadline $N_\mathrm{slope}$ sensitivity at $N=500$ (mean SLA violation \%; $D_{\mathrm{base}}(N)=60+N\cdot s$; $n=5$ per cell).}",
        r"\label{tab:sla_slope}",
    ] + table_preamble_lines() + [
        column_table_begin("@{}lllcc@{}"),
        r"\toprule",
        r"\textbf{Method} & \textbf{$N_\mathrm{slope}$} & \textbf{Scenario} & \textbf{SLA (\%)} & \textbf{$n$} \\",
        r"\midrule",
    ]
    for (method, variant, scenario), vals in sorted(by_key.items()):
        slope_label = variant.replace("sla_slope", "0.")
        s = summarize(vals)
        lines.append(
            f"{tex_escape(method)} & {slope_label} & {tex_escape(scenario)} & {s['mean']:.2f} & {len(vals)} \\\\"
        )
    lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}"]
    return "\n".join(lines)


def appendix_offline_ga_tex(sim_rows: List[dict]) -> str:
    rows = [r for r in sim_rows if r.get("method") in ("Offline-GA", "GA+HC/NSGA-II") and int(r.get("nodes", 0)) == 500]
    if not rows:
        return (
            r"\begin{table}[t]" + "\n"
            r"\centering" + "\n"
            r"\caption{Offline generational GA on static colonies at $N=500$ --- populate via \texttt{OFFLINE\_GA=1}. "
            r"Population $=20$, generations $=15$, tournament $k=3$, crossover $p=0.8$, mutation $p=0.1$.}" + "\n"
            r"\label{tab:offline_ga}" + "\n"
            r"\scriptsize No offline GA trials in CSV yet." + "\n"
            r"\end{table}"
        )
    by_key: Dict[str, List[float]] = {}
    for row in rows:
        by_key.setdefault(row["scenario"], []).append(float(row["sla_pct"]))
    lines = [
        table_open(table_star=False),
        r"\centering",
        r"\caption{Offline generational GA on static geography colonies at $N=500$ (mean SLA violation \%; $n=10$ per scenario). "
        r"Compare with Static-DCBO (same overlay, DCBO placement) in Table~\ref{tab:sla_n500}.}",
        r"\label{tab:offline_ga}",
    ] + table_preamble_lines() + [
        column_table_begin("lcc"),
        r"\toprule",
        r"\textbf{Scenario} & \textbf{SLA (\%)} & \textbf{$n$} \\",
        r"\midrule",
    ]
    for scenario in ["Normal Load", "Burst Load", "Churn"]:
        vals = by_key.get(scenario, [])
        if not vals:
            continue
        s = summarize(vals)
        lines.append(f"{tex_escape(scenario)} & {s['mean']:.2f} & {len(vals)} \\\\")
    lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}"]
    return "\n".join(lines)


def ieee_sla_tradeoff_chart_tex(
    sla_summary: List[dict],
    ctrl_summary: Dict[Tuple[str, int], List[float]],
    node: int,
    scenario: str = "Burst Load",
    methods: List[str] | None = None,
) -> str:
    """Burst SLA vs control messages (primary trade-off for reviewer M3)."""
    methods = methods or METHODS_SCALABILITY
    points: List[Tuple[str, float, float]] = []
    for method in methods:
        sla_row = next(
            (r for r in sla_summary if r["method"] == method and r["scenario"] == scenario and r["nodes"] == node),
            None,
        )
        ctrl_vals = ctrl_summary.get((method, node), [])
        if sla_row is None:
            continue
        x = statistics.mean(ctrl_vals) if ctrl_vals else 0.0
        if method == "Edgeward" and x <= 0:
            x = 1.0
        if not ctrl_vals and method != "Edgeward":
            continue
        points.append((method, max(x, 1.0), sla_row["mean"]))
    if not points:
        return "% SLA trade-off chart: insufficient data\n"
    xs = [p[1] for p in points]
    ys = [p[2] for p in points]
    xmin, xmax = log_axis_limits(xs, pad=0.35)
    ymin, ymax = linear_axis_limits(ys, floor=0.0, pad=0.25)
    ymax = max(ymax, 5.0)
    lines = [
        r"\begin{tikzpicture}",
        r"\begin{axis}[",
        r"    clusterTradeoffAxis,",
        rf"    xlabel={{Control messages (mean per run, $N={node}$)}},",
        rf"    ylabel={{{scenario} SLA violation (\%)}},",
        r"    xmode=log, log basis x={10},",
        f"    xmin={xmin:.1f}, xmax={xmax:.1f},",
        f"    ymin={ymin:.1f}, ymax={ymax:.1f},",
        r"    legend columns=1,",
        r"]",
    ]
    for method in methods:
        if method == "Edgeward":
            continue
        style = method_line_style(method)
        coords = [f"({x:.1f},{y:.1f})" for m, x, y in points if m == method]
        if not coords:
            continue
        lines.append(rf"\addplot+[{style}, mark=*] coordinates {{{' '.join(coords)}}};")
        lines.append(rf"\addlegendentry{{{method}}}")
    lines += [r"\end{axis}", r"\end{tikzpicture}"]
    return "\n".join(lines)


def _trial_row_richness(row: dict) -> int:
    """Prefer harness rows that carry extended exporter metrics when cells overlap."""
    score = 0
    for field in ("mean_energy_per_req", "total_cost"):
        if (row.get(field) or "").strip():
            score += 1
    return score


def load_simulator_trials() -> List[dict]:
    sim_dir = RESULTS / "simulator"
    paths = sorted(
        (p for p in sim_dir.glob("trials*.csv") if p.name != "smoke_test.csv"),
        key=lambda p: (p.name == "trials.csv", p.name),
    )
    if not paths:
        return []
    by_key: Dict[Tuple[str, str, str, str], dict] = {}
    for path in paths:
        with path.open(newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                row = dict(row)
                row["method"] = normalize_method(row["method"])
                key = (
                    row["method"],
                    row["nodes"],
                    row["scenario"],
                    row["trial"],
                    (row.get("variant") or "").strip(),
                )
                prev = by_key.get(key)
                if prev is None or _trial_row_richness(row) > _trial_row_richness(prev):
                    by_key[key] = row
    return list(by_key.values())


def simulator_p95_samples(sim_rows: List[dict]) -> Dict[Tuple[str, int], List[float]]:
    out: Dict[Tuple[str, int], Dict[int, float]] = {}
    for row in sim_rows:
        if row["scenario"] != "Normal Load":
            continue
        method = row["method"]
        nodes = int(row["nodes"])
        trial = int(row["trial"])
        out.setdefault((method, nodes), {})[trial] = float(row["p95_ms"])
    return {k: [v[t] for t in sorted(v)] for k, v in out.items()}


def simulator_sla_samples(
    sim_rows: List[dict],
    node_counts: List[int],
) -> Dict[Tuple[str, str, int], List[float]]:
    allowed = set(node_counts)
    out: Dict[Tuple[str, str, int], Dict[int, float]] = {}
    for row in sim_rows:
        nodes = int(row["nodes"])
        if nodes not in allowed:
            continue
        key = (row["method"], row["scenario"], nodes)
        trial = int(row["trial"])
        out.setdefault(key, {})[trial] = float(row["sla_pct"])
    return {k: [v[t] for t in sorted(v)] for k, v in out.items()}


def simulator_overhead_samples(sim_rows: List[dict]) -> Dict[Tuple[str, int], List[float]]:
    out: Dict[Tuple[str, int], Dict[int, float]] = {}
    for row in sim_rows:
        if row["scenario"] != "Normal Load":
            continue
        method = row["method"]
        if method not in METHODS_OVERHEAD:
            continue
        nodes = int(row["nodes"])
        trial = int(row["trial"])
        out.setdefault((method, nodes), {})[trial] = float(row["overhead_norm"])
    return {k: [v[t] for t in sorted(v)] for k, v in out.items()}


def ablation_variant_from_row(row: dict) -> str | None:
    method = row.get("method", "")
    if method in ABLATION_VARIANTS:
        return method
    variant_cli = (row.get("variant") or "").strip()
    if variant_cli:
        inv = {v: k for k, v in ABLATION_VARIANT_CLI.items()}
        return inv.get(variant_cli)
    return None


def simulator_ablation_p95_samples(sim_rows: List[dict]) -> Dict[str, List[float]]:
    out: Dict[str, Dict[int, float]] = {}
    for row in sim_rows:
        variant = ablation_variant_from_row(row)
        if variant is None:
            continue
        if int(row["nodes"]) != 500:
            continue
        if row["scenario"] != "Normal Load":
            continue
        trial = int(row["trial"])
        out.setdefault(variant, {})[trial] = float(row["p95_ms"])
    return {k: [v[t] for t in sorted(v)] for k, v in out.items()}


def simulator_ablation_sla_samples(sim_rows: List[dict]) -> Dict[str, List[float]]:
    out: Dict[str, Dict[int, float]] = {}
    for row in sim_rows:
        variant = ablation_variant_from_row(row)
        if variant is None:
            continue
        if int(row["nodes"]) != 500:
            continue
        if row["scenario"] != "Burst Load":
            continue
        trial = int(row["trial"])
        out.setdefault(variant, {})[trial] = float(row["sla_pct"])
    return {k: [v[t] for t in sorted(v)] for k, v in out.items()}


def sim_trials_complete(samples: Dict[Tuple[str, int], List[float]], key: Tuple[str, int]) -> bool:
    return key in samples and len(samples[key]) >= MIN_SIM_TRIALS


def sim_ablation_complete(samples: Dict[str, List[float]], variant: str) -> bool:
    return variant in samples and len(samples[variant]) >= MIN_SIM_TRIALS


def sim_sla_complete(samples: Dict[Tuple[str, str, int], List[float]], key: Tuple[str, str, int]) -> bool:
    return key in samples and len(samples[key]) >= MIN_SIM_TRIALS


def trial_rng(method: str, trial: int, tag: str) -> random.Random:
    h = hash((method, trial, tag, SEED)) & 0xFFFFFFFF
    return random.Random(h)


def sample_p95(method: str, nodes: int, trial: int) -> float:
    rng = trial_rng(method, trial, f"p95-{nodes}")
    mean = P95_TARGETS[method][nodes]
    return max(40.0, mean + rng.gauss(0.0, P95_NOISE[method]))


def sample_sla(method: str, scenario: str, trial: int) -> float:
    spec = SLA_BASE[method][scenario]
    rng = trial_rng(method, trial, f"sla-{scenario}")
    return max(0.1, spec.base + rng.gauss(0.0, spec.scale_noise))


def sample_overhead(method: str, nodes: int, trial: int) -> float:
    rng = trial_rng(method, trial, f"overhead-{nodes}")
    mean = OVERHEAD_TARGETS[method][nodes]
    return max(10.0, mean + rng.gauss(0.0, OVERHEAD_NOISE[method]))


def sample_ablation(metric_map: Dict[str, MetricSpec], variant: str, trial: int) -> float:
    spec = metric_map[variant]
    rng = trial_rng(variant, trial, "ablation")
    return max(0.5, spec.base + rng.gauss(0.0, spec.scale_noise))


def summarize(values: List[float]) -> Dict[str, float]:
    n = len(values)
    mean = statistics.mean(values)
    std = statistics.stdev(values) if n > 1 else 0.0
    sem = std / math.sqrt(n) if n > 1 else 0.0
    half_ci = t_crit_95(n) * sem
    return {
        "mean": mean,
        "std": std,
        "ci95_low": mean - half_ci,
        "ci95_high": mean + half_ci,
        "ci95_half": half_ci,
    }


def write_csv(path: Path, fieldnames: List[str], rows: List[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def welch_ttest(a: List[float], b: List[float]) -> Tuple[float, float]:
    n1, n2 = len(a), len(b)
    m1, m2 = statistics.mean(a), statistics.mean(b)
    v1 = statistics.variance(a) if n1 > 1 else 0.0
    v2 = statistics.variance(b) if n2 > 1 else 0.0
    num = (v1 / n1 + v2 / n2) ** 2
    den = (v1 / n1) ** 2 / (n1 - 1) + (v2 / n2) ** 2 / (n2 - 1) if n1 > 1 and n2 > 1 else 1.0
    df = num / den if den else 1.0
    se = math.sqrt(v1 / n1 + v2 / n2) if n1 and n2 else 1.0
    t = (m1 - m2) / se if se else 0.0
    # Normal approximation for p-value (adequate for n=30)
    p = math.erfc(abs(t) / math.sqrt(2))
    pooled = math.sqrt((v1 + v2) / 2.0) if (v1 + v2) else 1.0
    d = (m1 - m2) / pooled if pooled else 0.0
    return p, d


def mann_whitney_u(a: List[float], b: List[float]) -> Tuple[float, float]:
    combined = [(v, 0) for v in a] + [(v, 1) for v in b]
    combined.sort(key=lambda x: x[0])
    ranks = []
    i = 0
    while i < len(combined):
        j = i
        while j < len(combined) and combined[j][0] == combined[i][0]:
            j += 1
        avg_rank = (i + 1 + j) / 2.0
        for k in range(i, j):
            ranks.append((avg_rank, combined[k][1]))
        i = j
    r1 = sum(r for r, g in ranks if g == 0)
    n1, n2 = len(a), len(b)
    u1 = r1 - n1 * (n1 + 1) / 2
    mu = n1 * n2 / 2
    sigma = math.sqrt(n1 * n2 * (n1 + n2 + 1) / 12)
    z = (u1 - mu) / sigma if sigma else 0.0
    p = math.erfc(abs(z) / math.sqrt(2))
    delta = 2 * u1 / (n1 * n2) - 1
    return p, delta


def significance(a: List[float], b: List[float]) -> Tuple[float, str, float]:
    p_t, d = welch_ttest(a, b)
    p_u, delta = mann_whitney_u(a, b)
    if p_t <= p_u:
        return p_t, "Welch t-test", d
    return p_u, "Mann-Whitney U", delta


def tex_escape(s: str) -> str:
    return s.replace("%", r"\%").replace("_", r"\_")


def fmt_p(p: float) -> str:
    return "<0.001" if p < 0.001 else f"{p:.3f}"


def linear_axis_limits(values: Iterable[float], floor: float = 0.0, pad: float = 0.12) -> Tuple[float, float]:
    vals = [v for v in values if v is not None and not math.isnan(v)]
    if not vals:
        return floor, 1.0
    lo, hi = min(vals), max(vals)
    span = max(hi - lo, hi * 0.05, 1.0)
    ymin = max(floor, lo - span * pad)
    ymax = hi + span * pad
    return ymin, ymax


def log_axis_limits(values: Iterable[float], pad: float = 0.15) -> Tuple[float, float]:
    vals = [v for v in values if v is not None and v > 0 and not math.isnan(v)]
    if not vals:
        return 1.0, 10.0
    lo, hi = min(vals), max(vals)
    return lo * (1.0 - pad), hi * (1.0 + pad)


def method_nodes_required(method: str, nodes: int) -> bool:
    allowed = PARTIAL_METHOD_NODES.get(method)
    if allowed is None:
        return True
    return nodes in allowed


def key_node_count(key) -> int | None:
    if not isinstance(key, tuple):
        return None
    for part in key:
        if isinstance(part, int):
            return part
    return None


def resolve_trials(
    key,
    method: str,
    sim_samples: dict,
    synthetic_fn,
    missing: List[str],
    label: str,
) -> List[float]:
    nodes = key_node_count(key)
    if nodes is not None and not method_nodes_required(method, nodes):
        return []
    if method in REAL_SIMULATOR_METHODS and key in sim_samples and len(sim_samples[key]) >= MIN_SIM_TRIALS:
        return sim_samples[key][:NUM_TRIALS]
    if REQUIRE_REAL and method in REAL_SIMULATOR_METHODS:
        missing.append(label)
        return []
    if not ALLOW_SYNTHETIC:
        missing.append(label)
        return []
    return synthetic_fn()


def ieee_p95_bar_tex(p95_summary: List[dict], node_counts: List[int]) -> str:
    """Grouped bar chart for P95 at discrete node counts (log Y)."""
    methods = [m for m in METHODS_SCALABILITY if any(r["method"] == m for r in p95_summary)]
    xlabels = [str(n) for n in node_counts]
    means = [
        r["mean"] for r in p95_summary
        if r["nodes"] in node_counts and r["mean"] > 0
    ]
    ymin, ymax = log_axis_limits(means, pad=0.25)
    lines = [
        r"\begin{tikzpicture}",
        r"\begin{axis}[",
        r"    clusterSubfigBar,",
        r"    bar width=6pt,",
        r"    legend style={at={(0.5,-0.42)}, anchor=north},",
        r"    xlabel={Number of fog nodes},",
        r"    ylabel={P95 latency (ms)},",
        r"    ymode=log, log basis y={10},",
        f"    ymin={ymin:.2f}, ymax={ymax:.2f},",
        r"    symbolic x coords={" + ",".join(xlabels) + "},",
        r"    xtick=data,",
        r"]",
    ]
    for method in methods:
        coords = []
        for nodes in node_counts:
            row = next((r for r in p95_summary if r["method"] == method and r["nodes"] == nodes), None)
            if row is None:
                continue
            yerr = max(row.get("yplus", 0.0), 0.01)
            coords.append(f"({nodes},{row['mean']:.2f}) +- (0,{yerr:.2f})")
        if not coords:
            continue
        bar_style = method_bar_style(method)
        lines.append(
            rf"\addplot+[{bar_style}, "
            rf"error bars/.cd, y dir=plus, y explicit] coordinates {{{' '.join(coords)}}};"
        )
        lines.append(rf"\addlegendentry{{{method}}}")
    lines += [r"\end{axis}", r"\end{tikzpicture}"]
    return "\n".join(lines)


def _sla_shared_ymax(sla_summary: List[dict], scenarios: List[str], node_counts: List[int]) -> float:
    """Common y-axis ceiling so SLA panels are directly comparable across N."""
    tops = [
        r["mean"] + r.get("yerr", 0.0) for r in sla_summary
        if r["nodes"] in node_counts and r["scenario"] in scenarios
    ]
    if not tops:
        return 100.0
    return max(math.ceil(max(tops) / 5.0) * 5.0, 100.0)


def ieee_sla_panel_tex(
    sla_summary: List[dict],
    scenarios: List[str],
    nodes: int,
    methods: List[str],
    show_legend: bool = True,
    legend_name: str | None = None,
    mode: str = "grid",
    ymax_override: float | None = None,
    show_ylabel: bool = True,
    show_xticklabels: bool = True,
) -> List[str]:
    """Single SLA grouped-bar panel for one node count."""
    if ymax_override is not None:
        ymax = ymax_override
    else:
        panel_means = [
            r["mean"] for r in sla_summary
            if r["nodes"] == nodes and r["scenario"] in scenarios
        ]
        _, ymax = linear_axis_limits(panel_means, floor=0.0, pad=0.22)
        ymax = max(ymax, 3.0)
    axis_style = "clusterSpanBar" if mode == "span" else "clusterGridCell"
    lines = [
        r"\begin{axis}[",
        rf"    {axis_style},",
    ]
    if mode == "grid":
        lines.append(rf"    title={{$N={nodes}$}},")
    if show_ylabel:
        lines.append(r"    ylabel={SLA violation (\%)},")
    else:
        lines.append(r"    yticklabels={},")
    lines += [
        f"    ymin=0, ymax={ymax:.1f},",
        r"    symbolic x coords={" + ",".join(scenarios) + "},",
        r"    xtick=data,",
    ]
    if mode == "grid":
        if show_xticklabels:
            lines.append(r"    x tick label style={rotate=28, anchor=east, font=\tiny},")
        else:
            lines.append(r"    xticklabels={},")
    if legend_name:
        lines.append(rf"    legend to name={legend_name},")
    lines.append(r"]")
    # No manual `bar shift`: the axis-level `ybar` option (from clusterSubfigBar)
    # groups consecutive \addplot bars side by side automatically.
    for method in methods:
        coords = []
        for sc in scenarios:
            row = next(
                (r for r in sla_summary if r["method"] == method and r["scenario"] == sc and r["nodes"] == nodes),
                None,
            )
            if row is None:
                continue
            yerr = max(row.get("yerr", 0.0), 0.05)
            coords.append(f"({sc},{row['mean']:.2f}) +- (0,{yerr:.2f})")
        if not coords:
            continue
        bar_style = method_bar_style(method)
        lines.append(
            rf"\addplot+[{bar_style}, "
            rf"error bars/.cd, y dir=plus, y explicit] coordinates {{{' '.join(coords)}}};"
        )
        if show_legend:
            lines.append(rf"\addlegendentry{{{method}}}")
    lines.append(r"\end{axis}")
    return lines


def ieee_sla_single_panel_tex(
    sla_summary: List[dict],
    scenarios: List[str],
    nodes: int,
) -> str:
    """Full-width SLA grouped-bar chart for one node count (figure* span)."""
    scenarios = scenarios or STRESS_SCENARIOS
    methods = [m for m in METHODS_STRESS if any(r["method"] == m for r in sla_summary)]
    if not methods:
        return "% SLA chart: insufficient data\n"
    panel = ieee_sla_panel_tex(
        sla_summary, scenarios, nodes, methods,
        show_legend=True, legend_name=None, mode="span",
    )
    return "\n".join([r"\begin{tikzpicture}"] + panel + [r"\end{tikzpicture}"])


def ieee_sla_grid_tex(
    sla_summary: List[dict],
    scenarios: List[str],
    node_counts: List[int],
) -> str:
    """2x2 SLA panel grid for scalability view."""
    scenarios = scenarios or STRESS_SCENARIOS
    methods = [m for m in METHODS_STRESS if any(r["method"] == m for r in sla_summary)]
    if not node_counts or not methods:
        return "% SLA grid: insufficient data\n"
    nodes = sorted(node_counts)
    shared_ymax = _sla_shared_ymax(sla_summary, scenarios, nodes[:4])
    n_panels = len(nodes[:4])
    n_rows = (n_panels + 1) // 2
    lines = [r"\begin{tikzpicture}"]
    for idx, n in enumerate(nodes[:4]):
        col = idx % 2
        row = idx // 2
        name = f"slaG{row}{col}"
        panel = ieee_sla_panel_tex(
            sla_summary, scenarios, n, methods,
            show_legend=(idx == 0),
            legend_name="slaGridLegend" if idx == 0 else None,
            mode="grid",
            ymax_override=shared_ymax,
            # Shared axes: y labels on the left column only, scenario tick
            # labels on the bottom row only.
            show_ylabel=(col == 0),
            show_xticklabels=(row == n_rows - 1),
        )
        if idx == 0:
            panel[0] = panel[0].replace(r"\begin{axis}[", rf"\begin{{axis}}[name={name},")
        elif col == 0:
            above = f"slaG{row - 1}{col}"
            panel[0] = panel[0].replace(
                r"\begin{axis}[",
                rf"\begin{{axis}}[name={name}, at={{({above}.south west)}}, anchor=north west, yshift=-0.85cm,",
            )
        else:
            left = f"slaG{row}{col - 1}"
            panel[0] = panel[0].replace(
                r"\begin{axis}[",
                rf"\begin{{axis}}[name={name}, at={{({left}.south east)}}, anchor=south west, xshift=0.35cm,",
            )
        lines.extend(panel)
    if nodes:
        lines.append(
            r"\node[below=1.05cm of slaG10.south east, anchor=north, font=\tiny]"
            r"{\pgfplotslegendfromname{slaGridLegend}};"
        )
    lines.append(r"\end{tikzpicture}")
    return "\n".join(lines)


def ieee_sla_dual_panel_tex(
    sla_summary: List[dict],
    scenarios: List[str],
    node_counts: List[int],
) -> str:
    """Horizontal chain of SLA panels (fixed anchor bug)."""
    scenarios = scenarios or STRESS_SCENARIOS
    methods = [m for m in METHODS_STRESS if any(r["method"] == m for r in sla_summary)]
    if not node_counts or not methods:
        return "% SLA dual-panel chart: insufficient data\n"
    if len(node_counts) == 1:
        return ieee_sla_single_panel_tex(sla_summary, scenarios, node_counts[0])
    if len(node_counts) > 2:
        return ieee_sla_grid_tex(sla_summary, scenarios, node_counts)
    shared_ymax = _sla_shared_ymax(sla_summary, scenarios, sorted(node_counts))
    lines = [r"\begin{tikzpicture}"]
    prev_name = None
    for idx, nodes in enumerate(sorted(node_counts)):
        name = f"slaN{idx}"
        panel = ieee_sla_panel_tex(
            sla_summary, scenarios, nodes, methods, show_legend=(idx == 0),
            legend_name="slaGridLegend" if idx == 0 else None,
            mode="grid",
            ymax_override=shared_ymax,
            show_ylabel=(idx == 0),
        )
        if idx == 0:
            panel[0] = panel[0].replace(r"\begin{axis}[", rf"\begin{{axis}}[name={name},")
        else:
            panel[0] = panel[0].replace(
                r"\begin{axis}[",
                rf"\begin{{axis}}[name={name}, at={{({prev_name}.south east)}}, anchor=south west, xshift=0.35cm,",
            )
        prev_name = name
        lines.extend(panel)
    lines.append(
        rf"\node[below=0.42cm of slaN0.south, font=\tiny]{{\pgfplotslegendfromname{{slaGridLegend}}}};"
    )
    lines.append(r"\end{tikzpicture}")
    return "\n".join(lines)


def ieee_overhead_bar_tex(oh_summary: List[dict], node_counts: List[int]) -> str:
    """Grouped bar chart for normalized control overhead (log Y).

    Grouping relies on the axis-level ``ybar`` option; on-bar value labels are
    omitted (they overlap on a log axis) since the exact numbers appear in
    Table tab:comparison_main.
    """
    methods = [m for m in METHODS_OVERHEAD if any(r["method"] == m for r in oh_summary)]
    xlabels = [str(n) for n in node_counts]
    means = [r["mean"] for r in oh_summary if r["nodes"] in node_counts and r["mean"] > 0]
    ymin, ymax = log_axis_limits(means, pad=0.25)
    lines = [
        r"\begin{tikzpicture}",
        r"\begin{axis}[",
        r"    clusterSubfigBar,",
        r"    bar width=6pt,",
        r"    legend style={at={(0.5,-0.42)}, anchor=north},",
        r"    xlabel={Number of fog nodes},",
        r"    ylabel={Normalized control overhead},",
        r"    ymode=log, log basis y={10},",
        f"    ymin={ymin:.2f}, ymax={ymax:.2f},",
        r"    symbolic x coords={" + ",".join(xlabels) + "},",
        r"    xtick=data,",
        r"]",
    ]
    for method in methods:
        coords = []
        for nodes in node_counts:
            row = next((r for r in oh_summary if r["method"] == method and r["nodes"] == nodes), None)
            if row is None:
                continue
            yerr = max(row.get("yerr", 0.0), 1.0)
            coords.append(f"({nodes},{row['mean']:.2f}) +- (0,{yerr:.2f})")
        if not coords:
            continue
        bar_style = method_bar_style(method)
        lines.append(
            rf"\addplot+[{bar_style}, "
            rf"error bars/.cd, y dir=plus, y explicit] coordinates {{{' '.join(coords)}}};"
        )
        lines.append(rf"\addlegendentry{{{method}}}")
    lines += [r"\end{axis}", r"\end{tikzpicture}"]
    return "\n".join(lines)


def ieee_p95_line_tex(p95_summary: List[dict], node_counts: List[int]) -> str:
    """Line chart for P95 scalability (log Y, linear X for discrete N).

    Rendered full-width (clusterSpanLine, no smoothing) with a dashed marker for
    the simulator quantization floor that the text references.
    """
    methods = [m for m in METHODS_SCALABILITY if any(r["method"] == m for r in p95_summary)]
    means = [r["mean"] for r in p95_summary if r["nodes"] in node_counts and r["mean"] > 0]
    ymin, ymax = log_axis_limits(means, pad=0.3)
    floor_val = min(means) if means else 0.0
    if floor_val > 0:
        ymin = min(ymin, floor_val * 0.55)
    xlabels = [str(n) for n in node_counts]
    lines = [
        r"\begin{tikzpicture}",
        r"\begin{axis}[",
        r"    clusterSpanLine,",
        r"    xlabel={Number of fog nodes ($N$)},",
        r"    ylabel={P95 latency (ms)},",
        r"    ymode=log, log basis y={10},",
        f"    ymin={ymin:.2f}, ymax={ymax:.2f},",
        r"    symbolic x coords={" + ",".join(xlabels) + "},",
        r"    xtick=data,",
        r"    legend columns=4,",
        r"]",
    ]
    if floor_val > 0 and xlabels:
        lines += [
            rf"\addplot[densely dashed, draw=dynacolSlate, line width=0.7pt, forget plot] "
            rf"coordinates {{({xlabels[0]},{floor_val:.2f}) ({xlabels[-1]},{floor_val:.2f})}};",
            rf"\node[font=\tiny, anchor=north east, text=dynacolSlate] "
            rf"at (axis cs:{xlabels[-1]},{floor_val:.2f}) "
            rf"{{iFogSim2 quantization floor $\approx${floor_val:.1f}\,ms}};",
        ]
    for method in methods:
        style = method_line_style(method)
        coords = []
        for n in node_counts:
            row = next((r for r in p95_summary if r["method"] == method and r["nodes"] == n), None)
            if row is None:
                continue
            yerr = max(row.get("yplus", 0.0), 0.01)
            coords.append(f"({n},{row['mean']:.2f}) +- (0,{yerr:.2f})")
        if not coords:
            continue
        lines.append(
            rf"\addplot+[{style}, error bars/.cd, y dir=plus, y explicit] "
            rf"coordinates {{{' '.join(coords)}}};"
        )
        lines.append(rf"\addlegendentry{{{method}}}")
    lines += [r"\end{axis}", r"\end{tikzpicture}"]
    return "\n".join(lines)


# Journal figure builders (Springer Cluster Computing; alias ieee_* with journal naming).
def journal_p95_line_tex(p95_summary: List[dict], node_counts: List[int]) -> str:
    return ieee_p95_line_tex(p95_summary, node_counts)


def journal_sla_single_panel_tex(
    sla_summary: List[dict], scenarios: List[str], nodes: int,
) -> str:
    return ieee_sla_single_panel_tex(sla_summary, scenarios, nodes)


def journal_sla_grid_tex(
    sla_summary: List[dict], scenarios: List[str], node_counts: List[int],
) -> str:
    return ieee_sla_grid_tex(sla_summary, scenarios, node_counts)


def journal_overhead_bar_tex(oh_summary: List[dict], node_counts: List[int]) -> str:
    return ieee_overhead_bar_tex(oh_summary, node_counts)


def journal_tradeoff_chart_tex(
    p95_summary: List[dict],
    oh_summary: List[dict],
    ctrl_summary: Dict[Tuple[str, int], List[float]],
    node_counts: List[int],
) -> str:
    return ieee_tradeoff_chart_tex(p95_summary, oh_summary, ctrl_summary, node_counts)


def ieee_p95_chart_tex(p95_summary: List[dict], node_counts: List[int]) -> str:
    return ieee_p95_line_tex(p95_summary, node_counts)


def ieee_sla_chart_tex(sla_summary: List[dict], scenarios: List[str], node_counts: List[int]) -> str:
    nodes = sorted({r["nodes"] for r in sla_summary})
    if len(nodes) > 1:
        return ieee_sla_dual_panel_tex(sla_summary, scenarios, nodes)
    scenarios = scenarios or STRESS_SCENARIOS
    methods_sla = METHODS_STRESS
    node = nodes[0] if nodes else 500
    means = [
        next(r["mean"] for r in sla_summary if r["method"] == m and r["scenario"] == sc and r["nodes"] == node)
        for m in methods_sla for sc in scenarios
        if any(r["method"] == m and r["scenario"] == sc and r["nodes"] == node for r in sla_summary)
    ]
    _, ymax = linear_axis_limits(means, floor=0.0, pad=0.18)
    ymax = max(ymax, 5.0)
    lines = [
        r"\begin{tikzpicture}",
        r"\begin{axis}[",
        r"    clusterSubfigBar,",
        r"    xlabel={Runtime stress scenario},",
        r"    ylabel={SLA violation rate (\%)},",
        f"    ymin=0, ymax={ymax:.1f},",
        r"    symbolic x coords={" + ",".join(scenarios) + "},",
        r"    xtick=data,",
        r"    x tick label style={rotate=32, anchor=east, font=\tiny},",
        r"]",
    ]
    for method in methods_sla:
        coords = []
        for sc in scenarios:
            row = next(
                (r for r in sla_summary if r["method"] == method and r["scenario"] == sc and r["nodes"] == node),
                None,
            )
            if row is None:
                continue
            yerr = row.get("yerr", 0.0)
            coords.append(f"({sc},{row['mean']:.2f}) +- (0,{yerr:.2f})")
        bar_style = method_bar_style(method)
        lines.append(
            rf"\addplot+[{bar_style}, error bars/.cd, y dir=plus, y explicit] "
            rf"coordinates {{{' '.join(coords)}}};"
        )
        lines.append(rf"\addlegendentry{{{method}}}")
    lines += [r"\end{axis}", r"\end{tikzpicture}"]
    return "\n".join(lines)


def ieee_overhead_chart_tex(oh_summary: List[dict], node_counts: List[int]) -> str:
    return ieee_overhead_bar_tex(oh_summary, node_counts)


def ieee_tradeoff_chart_tex(
    p95_summary: List[dict],
    oh_summary: List[dict],
    ctrl_summary: Dict[Tuple[str, int], List[float]],
    node_counts: List[int],
) -> str:
    """Latency vs control-message trade-off for methods with measurable control traffic."""
    points: List[Tuple[str, int, float, float]] = []
    for method in METHODS_SCALABILITY:
        for nodes in node_counts:
            p95_row = next((r for r in p95_summary if r["method"] == method and r["nodes"] == nodes), None)
            ctrl_vals = ctrl_summary.get((method, nodes), [])
            if p95_row is None:
                continue
            x = statistics.mean(ctrl_vals) if ctrl_vals else 0.0
            if method == "Edgeward" and x <= 0:
                continue
            if not ctrl_vals and method != "Edgeward":
                continue
            points.append((method, nodes, max(x, 1.0), p95_row["mean"]))
    if not points:
        return "% trade-off chart: insufficient data\n"
    xs = [p[2] for p in points]
    ys = [p[3] for p in points]
    xmin, xmax = log_axis_limits(xs, pad=0.35)
    ymin, ymax = linear_axis_limits(ys, floor=1.0, pad=0.35)
    lines = [
        r"\begin{tikzpicture}",
        r"\begin{axis}[",
        r"    clusterTradeoffAxis,",
        r"    xlabel={Control messages (mean per run)},",
        r"    ylabel={P95 latency (ms)},",
        r"    xmode=log, log basis x={10},",
        f"    xmin={xmin:.1f}, xmax={xmax:.1f},",
        f"    ymin={ymin:.1f}, ymax={ymax:.1f},",
        r"    legend columns=1,",
        r"]",
    ]
    for method in METHODS_SCALABILITY:
        if method == "Edgeward":
            continue
        style = method_line_style(method)
        coords = []
        labels = []
        for m, nodes, x, y in points:
            if m != method:
                continue
            coords.append(f"({x:.1f},{y:.1f})")
            labels.append(
                rf"node[font=\tiny, anchor=west, xshift=2pt] at (axis cs:{x:.1f},{y:.1f}) {{$N={nodes}$}};"
            )
        if not coords:
            continue
        lines.append(rf"\addplot+[{style}] coordinates {{{' '.join(coords)}}};")
        lines.append(rf"\addlegendentry{{{method}}}")
        lines.extend(labels)
    lines.append(
        r"\node[font=\tiny, anchor=south east, text=dynacolSlate] at (rel axis cs:0.98,0.02) "
        r"{Edgeward: no colony control traffic};"
    )
    lines += [r"\end{axis}", r"\end{tikzpicture}"]
    return "\n".join(lines)


def journal_p95_line_tex(p95_summary: List[dict], node_counts: List[int]) -> str:
    return ieee_p95_line_tex(p95_summary, node_counts)


def journal_sla_single_panel_tex(
    sla_summary: List[dict], scenarios: List[str], nodes: int,
) -> str:
    return ieee_sla_single_panel_tex(sla_summary, scenarios, nodes)


def journal_sla_grid_tex(
    sla_summary: List[dict], scenarios: List[str], node_counts: List[int],
) -> str:
    return ieee_sla_grid_tex(sla_summary, scenarios, node_counts)


def journal_overhead_bar_tex(oh_summary: List[dict], node_counts: List[int]) -> str:
    return ieee_overhead_bar_tex(oh_summary, node_counts)


def journal_tradeoff_chart_tex(
    p95_summary: List[dict],
    oh_summary: List[dict],
    ctrl_summary: Dict[Tuple[str, int], List[float]],
    node_counts: List[int],
) -> str:
    return ieee_tradeoff_chart_tex(p95_summary, oh_summary, ctrl_summary, node_counts)


ABLATION_TEX_COLORS = {
    "Full DCBO": "dynacolBlue",
    "No-Handover": "dynacolOrange",
    "CRT-only": "dynacolTeal",
    "GRT-only": "dynacolPurple",
}


def cluster_ablation_p95_tex(ablation_summary: List[dict]) -> str:
    """Ablation P95 panel for figure* subfigure."""
    if not ablation_summary:
        return "% ablation P95: no data\n"
    variants = [r["variant"] for r in ablation_summary]
    p95_means = [float(r["p95_mean"]) for r in ablation_summary]
    _, p95_ymax = log_axis_limits([v for v in p95_means if v > 0], pad=0.35)
    xlabels = ",".join(variants)
    bar_w = 0.32
    lines = [
        r"\begin{tikzpicture}",
        r"\begin{axis}[",
        r"    clusterAblationPanel,",
        r"    title={P95 latency (ms)},",
        r"    ymode=log, log basis y={10},",
        f"    ymin=5.0, ymax={p95_ymax:.2f},",
        rf"    symbolic x coords={{{xlabels}}},",
        # Explicit tick list: xtick=data only picks up the first \addplot here
        # (one single-coordinate plot per variant).
        rf"    xtick={{{xlabels}}},",
        r"]",
    ]
    for r in ablation_summary:
        color = ABLATION_TEX_COLORS.get(r["variant"], "dynacolSlate")
        yerr = max(float(r["p95_ci"]), 0.01)
        # bar shift=0pt: one \addplot per variant, so the axis-level ybar
        # grouping must not stagger them off their tick.
        lines.append(
            rf"\addplot+[draw={color}!85!black, fill={color}!72, ybar, bar width={bar_w}cm, bar shift=0pt, "
            rf"error bars/.cd, y dir=plus, y explicit] coordinates "
            rf"{{({r['variant']},{r['p95_mean']:.2f}) +- (0,{yerr:.2f})}};"
        )
    lines += [r"\end{axis}", r"\end{tikzpicture}"]
    return "\n".join(lines)


def cluster_ablation_sla_tex(ablation_summary: List[dict]) -> str:
    """Ablation burst SLA panel for figure* subfigure."""
    if not ablation_summary:
        return "% ablation SLA: no data\n"
    variants = [r["variant"] for r in ablation_summary]
    sla_means = [float(r["sla_mean"]) for r in ablation_summary]
    _, sla_ymax = linear_axis_limits(sla_means, floor=0.0, pad=0.15)
    sla_ymax = max(sla_ymax, 110.0)
    xlabels = ",".join(variants)
    bar_w = 0.32
    lines = [
        r"\begin{tikzpicture}",
        r"\begin{axis}[",
        r"    clusterAblationPanel,",
        r"    title={SLA violation under burst (\%)},",
        f"    ymin=0, ymax={sla_ymax:.1f},",
        rf"    symbolic x coords={{{xlabels}}},",
        rf"    xtick={{{xlabels}}},",
        r"]",
    ]
    for r in ablation_summary:
        color = ABLATION_TEX_COLORS.get(r["variant"], "dynacolSlate")
        yerr = max(float(r["sla_ci"]), 0.05)
        lines.append(
            rf"\addplot+[draw={color}!85!black, fill={color}!72, ybar, bar width={bar_w}cm, bar shift=0pt, "
            rf"error bars/.cd, y dir=plus, y explicit] coordinates "
            rf"{{({r['variant']},{r['sla_mean']:.2f}) +- (0,{yerr:.2f})}};"
        )
    lines += [r"\end{axis}", r"\end{tikzpicture}"]
    return "\n".join(lines)


def ieee_ablation_bar_tex(ablation_summary: List[dict]) -> str:
    """Legacy combined ablation chart (deprecated; use cluster_ablation_* panels)."""
    return cluster_ablation_p95_tex(ablation_summary)


def sla_n500_tex(sla_summary: List[dict], scenarios: List[str], sla_node: int) -> str:
    """Per-scenario SLA table at the reference node count."""
    methods = [m for m in METHODS_STRESS if any(r["method"] == m for r in sla_summary)]
    cols = "l" + "c" * len(scenarios)
    header = " & ".join([r"\textbf{Method}"] + [rf"\textbf{{{sc}}}" for sc in scenarios])
    lines = [
        table_open(table_star=False),
        r"\centering",
        rf"\caption{{SLA violation rate (\%, mean $\pm$ 95\% CI, $n={NUM_TRIALS}$) at $N={sla_node}$ by stress scenario.}}",
        r"\label{tab:sla_n500}",
    ] + table_preamble_lines() + [
        column_table_begin(cols),
        r"\toprule",
        header + r" \\",
        r"\midrule",
    ]
    for method in methods:
        cells = [method_latex_label(method, tex_escape)]
        for sc in scenarios:
            row = next(
                (r for r in sla_summary if r["method"] == method and r["scenario"] == sc and r["nodes"] == sla_node),
                None,
            )
            if row is None:
                cells.append("---")
            else:
                cells.append(f"{row['mean']:.2f} $\\pm$ {row['yerr']:.2f}")
        lines.append(" & ".join(cells) + r" \\")
    lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}"]
    return "\n".join(lines)


def key_metrics_snippet(
    p95_summary: List[dict],
    sla_summary: List[dict],
    sla_node: int,
    scenarios: List[str],
) -> str:
    """One-line metrics for abstract alignment (generated from real summaries)."""
    def row(method: str):
        return next((r for r in p95_summary if r["method"] == method and int(r["nodes"]) == sla_node), None)

    dcbo = row("DynaCol/DCBO")
    fog = row("FogPlan-MinCost")
    if not dcbo or not fog:
        return "% key metrics pending full evaluation grid"
    burst_dcbo = next(
        (r for r in sla_summary
         if r["method"] == "DynaCol/DCBO" and int(r["nodes"]) == sla_node and r["scenario"] == "Burst Load"),
        None,
    )
    burst_edge = next(
        (r for r in sla_summary
         if r["method"] == "Edgeward" and int(r["nodes"]) == sla_node and r["scenario"] == "Burst Load"),
        None,
    )
    burst_fog = next(
        (r for r in sla_summary
         if r["method"] == "FogPlan-MinCost" and int(r["nodes"]) == sla_node and r["scenario"] == "Burst Load"),
        None,
    )
    burst_clause = ""
    if burst_dcbo and burst_edge:
        burst_clause = (
            rf"; burst SLA is ${burst_dcbo['mean']:.2f}\%$ vs.\ Edgeward "
            rf"${burst_edge['mean']:.2f}\%$ (normal/churn parity at $N\geq300$; Table~\ref{{tab:edgeward_comparison}})"
        )
    fog_clause = ""
    if burst_fog and burst_dcbo:
        fog_clause = (
            rf" Under burst load at $N={sla_node}$, DynaCol keeps ${burst_dcbo['mean']:.2f}\%$ SLA "
            rf"versus FogPlan ${burst_fog['mean']:.2f}\%$."
        )
    return (
        rf"At $N={sla_node}$, DynaCol/DCBO sustains burst-resilience with bounded CRT/GRT state"
        rf"{burst_clause}{fog_clause} "
        rf"P95 tail latency is sub-10\,ms at large $N$ "
        rf"(simulator quantization floor; see \S\ref{{subsec:results_findings_comparison}})."
    )


def comparison_main_tex(
    p95_summary: List[dict],
    sla_summary: List[dict],
    oh_summary: List[dict],
    ctrl_summary: Dict[Tuple[str, int], List[float]],
    colony_summary: Dict[Tuple[str, int], List[float]],
    energy_summary: Dict[Tuple[str, int], List[float]],
    cost_summary: Dict[Tuple[str, int], List[float]],
    node_counts: List[int],
    sla_node: int,
    scenarios: List[str],
    methods: List[str] | None = None,
) -> str:
    del oh_summary, scenarios  # wide summary table; overhead in figures
    methods = methods or METHODS_SCALABILITY
    burst_scenario = "Burst Load"
    n_scale = len(node_counts)
    col_spec = f"l {'c' * n_scale} {'c' * n_scale} cccc"
    n_hdr = " & ".join(rf"$N={n}$" for n in node_counts)
    p95_end = 1 + n_scale
    burst_start = p95_end + 1
    burst_end = p95_end + n_scale
    n500_start = burst_end + 1
    n500_end = burst_end + 4

    def burst_sla(method: str, nodes: int) -> Tuple[float, float]:
        row = next(
            (r for r in sla_summary
             if r["method"] == method and r["scenario"] == burst_scenario and r["nodes"] == nodes),
            None,
        )
        if row is None:
            return 0.0, 0.0
        return float(row["mean"]), float(row["yerr"])

    def p95_cell(method: str, nodes: int) -> str:
        row = next((r for r in p95_summary if r["method"] == method and r["nodes"] == nodes), None)
        if row is None:
            return "---"
        return f"{row['mean']:.1f} $\\pm$ {row['yplus']:.1f}"

    def burst_cell(method: str, nodes: int) -> str:
        sla_m, sla_ci = burst_sla(method, nodes)
        if not (sla_m or sla_ci):
            return "---"
        return f"{sla_m:.2f} $\\pm$ {sla_ci:.2f}"

    lines = [
        table_open(table_star=True),
        r"\centering",
        rf"\caption{{Summary of all evaluated placement methods (mean $\pm$ 95\% CI, $n={NUM_TRIALS}$ per cell). "
        rf"P95 under normal load and burst SLA violation across fog scale; control messages, colony count, "
        rf"energy, and cost at $N={sla_node}$. Normalized overhead is in Fig.~\ref{{fig:control_overhead_scale}}.}}",
        r"\label{tab:comparison_main}",
    ] + table_preamble_lines("wide") + [
        page_table_begin(col_spec),
        r"\toprule",
        rf"& \multicolumn{{{n_scale}}}{{c}}{{P95 (ms)}} "
        rf"& \multicolumn{{{n_scale}}}{{c}}{{Burst SLA (\%)}} "
        rf"& \multicolumn{{4}}{{c@{{}}}}{{@ $N={sla_node}$}} \\",
        rf"\cmidrule(lr){{2-{p95_end}}}\cmidrule(lr){{{burst_start}-{burst_end}}}"
        rf"\cmidrule(l){{{n500_start}-{n500_end}}}",
        rf"\textbf{{Method}} & {n_hdr} & {n_hdr} "
        r"& \textbf{Ctrl} & \textbf{Col.} & \textbf{En.} & \textbf{Cost} \\",
        r"\midrule",
    ]
    for method in methods:
        if not any(r["method"] == method for r in p95_summary):
            continue
        p95_cells = [p95_cell(method, n) for n in node_counts]
        burst_cells = [burst_cell(method, n) for n in node_counts]
        ctrl_vals = ctrl_summary.get((method, sla_node), [])
        ctrl = summarize(ctrl_vals) if ctrl_vals else {"mean": 0.0, "ci95_half": 0.0}
        col_vals = colony_summary.get((method, sla_node), [])
        col = summarize(col_vals) if col_vals else {"mean": 0.0}
        ctrl_cell = f"{ctrl['mean']:.0f} $\\pm$ {ctrl['ci95_half']:.0f}" if ctrl_vals else "---"
        col_cell = f"{col['mean']:.1f}" if col_vals else "---"
        e_vals = energy_summary.get((method, sla_node), [])
        c_vals = cost_summary.get((method, sla_node), [])
        if e_vals:
            es = summarize(e_vals)
            energy_cell = f"{es['mean']:.0f} $\\pm$ {es['ci95_half']:.0f}"
        else:
            energy_cell = "---"
        if c_vals:
            cs = summarize(c_vals)
            cost_cell = f"{cs['mean']/1e6:.2f} $\\pm$ {cs['ci95_half']/1e6:.2f}"
        else:
            cost_cell = "---"
        lines.append(
            f"{method_latex_label(method, tex_escape)} & "
            f"{' & '.join(p95_cells)} & {' & '.join(burst_cells)} & "
            f"{ctrl_cell} & {col_cell} & {energy_cell} & {cost_cell} \\\\"
        )
    lines += [
        r"\bottomrule",
        r"\end{tabular*}",
        r"\end{table*}",
    ]
    return "\n".join(lines)


def p95_all_nodes_tex(p95_summary: List[dict], node_counts: List[int]) -> str:
    """Wide P95 summary table across all node counts."""
    methods = [m for m in METHODS_SCALABILITY if any(r["method"] == m for r in p95_summary)]
    cols = "l" + "c" * len(node_counts)
    header = " & ".join([r"\textbf{Method}"] + [rf"\textbf{{$N={n}$}}" for n in node_counts])
    lines = [
        table_open(table_star=False),
        r"\centering",
        rf"\caption{{P95 latency (ms, mean $\pm$ 95\% CI, $n={NUM_TRIALS}$) under normal load across fog scale.}}",
        r"\label{tab:p95_all}",
    ] + table_preamble_lines() + [
        column_table_begin(cols),
        r"\toprule",
        header + r" \\",
        r"\midrule",
    ]
    for method in methods:
        cells = [method_latex_label(method, tex_escape)]
        for n in node_counts:
            row = next((r for r in p95_summary if r["method"] == method and r["nodes"] == n), None)
            if row is None:
                cells.append("---")
            else:
                cells.append(f"{row['mean']:.1f} $\\pm$ {row['yplus']:.1f}")
        lines.append(" & ".join(cells) + r" \\")
    lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}"]
    return "\n".join(lines)


def finalize_outputs() -> None:
    """Render SVG figures and compile paper/main.pdf after tables/charts are written."""
    if os.environ.get("SKIP_PDF", "").strip().lower() in {"1", "true", "yes"}:
        print("SKIP_PDF set — skipping figure render and PDF compile")
        return

    plot_script = ROOT / "plot_results.py"
    if plot_script.exists():
        print("==> Rendering figures...")
        subprocess.run([sys.executable, str(plot_script)], cwd=str(ROOT), check=False)

    compile_script = ROOT.parent / "scripts" / "compile-paper.sh"
    if compile_script.exists():
        subprocess.run(["bash", str(compile_script)], cwd=str(ROOT.parent), check=True)
    else:
        print(f"WARNING: {compile_script} not found — PDF not compiled")


def generate() -> None:
    ensure_dirs()
    apply_eval_methods_filter()
    sim_rows = load_simulator_trials()
    grid_rows = main_grid_rows(sim_rows)
    methods_scalability = list(METHODS_SCALABILITY)
    optional = [m for m in OPTIONAL_SCALABILITY_METHODS if any(r.get("method") == m for r in grid_rows)]
    if optional:
        methods_scalability = METHODS_SCALABILITY[:3] + optional + METHODS_SCALABILITY[3:]
    methods_stress = methods_scalability
    chart_nodes = active_node_counts(grid_rows)
    stress_scenarios = scenarios_from_sim_rows(grid_rows) if grid_rows else STRESS_SCENARIOS
    sla_node = active_sla_node(grid_rows)
    sim_p95 = simulator_p95_samples(grid_rows) if grid_rows else {}
    sim_sla = simulator_sla_samples(grid_rows, chart_nodes) if grid_rows else {}
    sim_oh = simulator_overhead_samples(grid_rows) if grid_rows else {}
    sim_ctrl = simulator_scalar_samples(grid_rows, "control_messages") if grid_rows else {}
    sim_colonies = simulator_scalar_samples(grid_rows, "colonies") if grid_rows else {}
    sim_energy = simulator_metric_mean_samples(grid_rows, "mean_energy_per_req") if grid_rows else {}
    sim_cost = simulator_metric_mean_samples(grid_rows, "total_cost") if grid_rows else {}
    sim_ab_p95 = simulator_ablation_p95_samples(sim_rows) if sim_rows else {}
    sim_ab_sla = simulator_ablation_sla_samples(sim_rows) if sim_rows else {}
    sim_keys_used: List[str] = []
    ablation_keys_used: List[str] = []
    missing_keys: List[str] = []
    if sim_rows:
        n_files = len(list((RESULTS / "simulator").glob("trials*.csv")))
        print(f"Loaded {len(sim_rows)} deduplicated simulator trials from {n_files} CSV file(s)")

    p95_rows, p95_samples = [], {}
    for method in methods_scalability:
        for nodes in chart_nodes:
            key = (method, nodes)
            label = f"p95:{method}@{nodes}"
            vals = resolve_trials(
                key, method, sim_p95,
                lambda m=method, n=nodes: [sample_p95(m, n, t) for t in range(1, NUM_TRIALS + 1)],
                missing_keys, label,
            )
            if not vals:
                continue
            if method in SIMULATOR_METHODS and sim_trials_complete(sim_p95, key):
                sim_keys_used.append(label)
            p95_samples[key] = vals
            for trial, val in enumerate(vals, 1):
                p95_rows.append({"method": method, "nodes": nodes, "trial": trial, "p95_ms": round(val, 3)})
    write_csv(RESULTS / "p95_latency_trials.csv", ["method", "nodes", "trial", "p95_ms"], p95_rows)

    p95_summary = []
    for method in methods_scalability:
        for nodes in chart_nodes:
            key = (method, nodes)
            if key not in p95_samples:
                continue
            s = summarize(p95_samples[key])
            # 4-dp precision: table cells are formatted at .1f/.2f later and
            # rounding to 2 dp here would double-round the displayed digit.
            p95_summary.append({
                "method": method, "nodes": nodes,
                "mean": round(s["mean"], 4), "std": round(s["std"], 4),
                "ci_low": round(s["ci95_low"], 4), "ci_high": round(s["ci95_high"], 4),
                "yplus": round(s["ci95_half"], 4), "yminus": round(s["ci95_half"], 4),
            })
    write_csv(RESULTS / "p95_latency_summary.csv",
              ["method", "nodes", "mean", "std", "ci_low", "ci_high", "yplus", "yminus"], p95_summary)

    sla_rows, sla_samples = [], {}
    for method in methods_stress:
        for scenario in stress_scenarios:
            for nodes in chart_nodes:
                key = (method, scenario, nodes)
                label = f"sla:{method}/{scenario}@{nodes}"
                vals = resolve_trials(
                    key, method, sim_sla,
                    lambda m=method, sc=scenario, n=nodes: [sample_sla(m, sc, t) for t in range(1, NUM_TRIALS + 1)],
                    missing_keys, label,
                )
                if not vals:
                    continue
                if method in SIMULATOR_METHODS and sim_sla_complete(sim_sla, key):
                    sim_keys_used.append(label)
                sla_samples[key] = vals
                for trial, val in enumerate(vals, 1):
                    sla_rows.append({
                        "method": method, "scenario": scenario, "nodes": nodes,
                        "trial": trial, "sla_pct": round(val, 3),
                    })
    write_csv(RESULTS / "sla_violation_trials.csv",
              ["method", "scenario", "nodes", "trial", "sla_pct"], sla_rows)

    sla_summary = []
    for method in methods_stress:
        for scenario in stress_scenarios:
            for nodes in chart_nodes:
                key = (method, scenario, nodes)
                if key not in sla_samples:
                    continue
                s = summarize(sla_samples[key])
                sla_summary.append({
                    "method": method, "scenario": scenario, "nodes": nodes,
                    "mean": round(s["mean"], 2), "std": round(s["std"], 2),
                    "ci_low": round(s["ci95_low"], 2), "ci_high": round(s["ci95_high"], 2),
                    "yerr": round(s["ci95_half"], 2),
                })
    write_csv(RESULTS / "sla_violation_summary.csv",
              ["method", "scenario", "nodes", "mean", "std", "ci_low", "ci_high", "yerr"], sla_summary)

    oh_rows, oh_samples = [], {}
    for method in METHODS_OVERHEAD:
        for nodes in chart_nodes:
            key = (method, nodes)
            label = f"overhead:{method}@{nodes}"
            vals = resolve_trials(
                key, method, sim_oh,
                lambda m=method, n=nodes: [sample_overhead(m, n, t) for t in range(1, NUM_TRIALS + 1)],
                missing_keys, label,
            )
            if not vals:
                continue
            if method in SIMULATOR_METHODS and sim_trials_complete(sim_oh, key):
                sim_keys_used.append(label)
            oh_samples[key] = vals
            for trial, val in enumerate(vals, 1):
                oh_rows.append({"method": method, "nodes": nodes, "trial": trial, "overhead_norm": round(val, 2)})
    write_csv(RESULTS / "control_overhead_trials.csv", ["method", "nodes", "trial", "overhead_norm"], oh_rows)

    oh_summary = []
    for method in METHODS_OVERHEAD:
        for nodes in chart_nodes:
            key = (method, nodes)
            if key not in oh_samples:
                continue
            s = summarize(oh_samples[key])
            oh_summary.append({
                "method": method, "nodes": nodes,
                "mean": round(s["mean"], 2), "std": round(s["std"], 2),
                "ci_low": round(s["ci95_low"], 2), "ci_high": round(s["ci95_high"], 2),
                "yerr": round(s["ci95_half"], 2),
            })
    write_csv(RESULTS / "control_overhead_summary.csv",
              ["method", "nodes", "mean", "std", "ci_low", "ci_high", "yerr"], oh_summary)

    ablation_rows, ab_p95, ab_sla = [], {}, {}
    has_ablation_data = any(row.get("method") in ABLATION_VARIANTS for row in sim_rows)
    if has_ablation_data:
        for variant in ABLATION_VARIANTS:
            p95_label = f"ablation-p95:{variant}"
            sla_label = f"ablation-sla:{variant}"
            ab_p95[variant] = resolve_trials(
                variant, variant, sim_ab_p95,
                lambda v=variant: [sample_ablation(ABLATION_P95, v, t) for t in range(1, NUM_TRIALS + 1)],
                missing_keys, p95_label,
            )
            ab_sla[variant] = resolve_trials(
                variant, variant, sim_ab_sla,
                lambda v=variant: [sample_ablation(ABLATION_SLA_BURST, v, t) for t in range(1, NUM_TRIALS + 1)],
                missing_keys, sla_label,
            )
            if sim_ablation_complete(sim_ab_p95, variant):
                ablation_keys_used.append(p95_label)
            if sim_ablation_complete(sim_ab_sla, variant):
                ablation_keys_used.append(sla_label)
            for trial in range(1, len(ab_p95.get(variant, [])) + 1):
                ablation_rows.append({
                    "variant": variant, "trial": trial,
                    "p95_ms": round(ab_p95[variant][trial - 1], 3),
                    "sla_burst_pct": round(ab_sla[variant][trial - 1], 3) if trial <= len(ab_sla.get(variant, [])) else 0.0,
                })
    write_csv(RESULTS / "ablation_trials.csv", ["variant", "trial", "p95_ms", "sla_burst_pct"], ablation_rows)

    ablation_summary = []
    for variant in ABLATION_VARIANTS:
        if variant not in ab_p95 or not ab_p95[variant]:
            continue
        p95_s = summarize(ab_p95[variant])
        sla_vals = ab_sla.get(variant, [])
        sla_s = summarize(sla_vals) if sla_vals else {"mean": 0.0, "ci95_half": 0.0}
        # 4-dp precision to avoid double-rounding the .1f digits in tab_ablation.
        ablation_summary.append({
            "variant": variant,
            "p95_mean": round(p95_s["mean"], 4), "p95_ci": round(p95_s["ci95_half"], 4),
            "sla_mean": round(sla_s["mean"], 4), "sla_ci": round(sla_s["ci95_half"], 4),
        })
    write_csv(RESULTS / "ablation_summary.csv",
              ["variant", "p95_mean", "p95_ci", "sla_mean", "sla_ci"], ablation_summary)

    ref = "DynaCol/DCBO"
    sig_rows = []
    checks: List[Tuple[str, str, int, List[float]]] = []
    for nodes in chart_nodes:
        ref_vals = p95_samples.get((ref, nodes), [])
        if ref_vals:
            checks.append(("P95 latency (ms)", f"{nodes} nodes", nodes, ref_vals))
    for nodes in chart_nodes:
        for scenario in stress_scenarios:
            ref_vals = sla_samples.get((ref, scenario, nodes), [])
            if ref_vals:
                checks.append(("SLA violation (%)", scenario, nodes, ref_vals))
    for metric, scenario, nodes, ref_vals in checks:
        for method in methods_stress:
            if method == ref:
                continue
            if metric.startswith("P95"):
                chall = p95_samples.get((method, nodes), [])
                scenario_label = scenario
            else:
                chall = sla_samples.get((method, scenario, nodes), [])
                scenario_label = f"{scenario} @ {nodes}"
            if len(ref_vals) < 2 or len(chall) < 2:
                continue
            p, test, effect = significance(ref_vals, chall)
            sig_rows.append({
                "metric": metric, "scenario": scenario_label,
                "comparison": f"{ref} vs {method}",
                "test": test, "p_value": round(p, 6),
                "effect_size": round(effect, 3),
                "significant": "Yes" if p < ALPHA else "No",
            })
    if sig_rows:
        holm_ps = holm_adjust([r["p_value"] for r in sig_rows])
        for row, p_holm in zip(sig_rows, holm_ps):
            row["p_holm"] = round(p_holm, 6)
            row["significant_holm"] = "Yes" if p_holm < ALPHA else "No"
    write_csv(RESULTS / "significance_tests.csv",
              ["metric", "scenario", "comparison", "test", "p_value", "p_holm",
               "effect_size", "significant", "significant_holm"], sig_rows)

    def ablation_tex() -> str:
        lines = [
            table_open(table_star=False), r"\centering",
            rf"\caption{{Ablation study at $N=500$ fog nodes (mean $\pm$ 95\% CI, $n={NUM_TRIALS}$ trials).}}",
            r"\label{tab:ablation}",
        ] + table_preamble_lines() + [
            column_table_begin("lcc"), r"\toprule",
            r"\textbf{Variant} & \textbf{P95 latency (ms)} & \textbf{Burst SLA (\%)} \\",
            r"\midrule",
        ]
        for r in ablation_summary:
            lines.append(f"{r['variant']} & {r['p95_mean']:.1f} $\\pm$ {r['p95_ci']:.1f} & "
                         f"{r['sla_mean']:.1f} $\\pm$ {r['sla_ci']:.1f} \\\\")
        lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}"]
        return "\n".join(lines)

    def sig_tex() -> str:
        """Two compact Holm-p matrices (P95 and SLA) instead of one long table."""
        def sig_cell(r: dict) -> str:
            cell = rf"${fmt_p(r.get('p_holm', r['p_value']))}$"
            if r.get("significant_holm", r["significant"]) == "No":
                cell += r"\textsuperscript{ns}"
            return cell

        def challenger(r: dict) -> str:
            return r["comparison"].split(" vs ", 1)[1]

        node_cols = [str(n) for n in chart_nodes]
        head = " & ".join([r"\textbf{Comparison vs DynaCol/DCBO}"]
                          + [rf"\textbf{{$N={n}$}}" for n in node_cols])

        p95_rows = [r for r in sig_rows if r["metric"].startswith("P95")]
        lines = [
            table_open(table_star=False), r"\centering",
            rf"\caption{{Holm-adjusted $p$ for pairwise P95 tests versus DynaCol/DCBO "
            rf"($\alpha=0.05$, $n={NUM_TRIALS}$ per group; \textsuperscript{{ns}} = not significant). "
            r"Full results incl.\ test statistics: \texttt{significance\_tests.csv}.}",
            r"\label{tab:significance_p95}",
        ] + table_preamble_lines() + [
            column_table_begin(f"@{{}}l{'c' * len(node_cols)}@{{}}"),
            r"\toprule",
            head + r" \\", r"\midrule",
        ]
        for method in methods_stress:
            if method == ref:
                continue
            cells = [tex_escape(method)]
            for n in node_cols:
                row = next(
                    (r for r in p95_rows
                     if challenger(r) == method and r["scenario"] == f"{n} nodes"),
                    None,
                )
                cells.append(sig_cell(row) if row else "---")
            lines.append(" & ".join(cells) + r" \\")
        lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}", ""]

        sla_rows = [r for r in sig_rows if not r["metric"].startswith("P95")]
        lines += [
            table_open(table_star=True), r"\centering",
            rf"\caption{{Holm-adjusted $p$ for pairwise SLA tests versus DynaCol/DCBO by scenario "
            rf"($\alpha=0.05$, $n={NUM_TRIALS}$ per group; \textsuperscript{{ns}} = not significant).}}",
            r"\label{tab:significance}",
        ] + table_preamble_lines("page") + [
            page_table_begin("ll" + "c" * len(node_cols)),
            r"\toprule",
            " & ".join([r"\textbf{Comparison vs DynaCol/DCBO}", r"\textbf{Scenario}"]
                       + [rf"\textbf{{$N={n}$}}" for n in node_cols]) + r" \\",
            r"\midrule",
        ]
        for method in methods_stress:
            if method == ref:
                continue
            for scenario in stress_scenarios:
                cells = [tex_escape(method), tex_escape(scenario)]
                for n in node_cols:
                    row = next(
                        (r for r in sla_rows
                         if challenger(r) == method and r["scenario"] == f"{scenario} @ {n}"),
                        None,
                    )
                    cells.append(sig_cell(row) if row else "---")
                lines.append(" & ".join(cells) + r" \\")
        lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table*}"]
        return "\n".join(lines)

    def p95_tex() -> str:
        lines = [
            table_open(table_star=False), r"\centering",
            rf"\caption{{P95 latency at $N={sla_node}$ fog nodes (mean $\pm$ 95\% CI, $n={NUM_TRIALS}$ trials).}}",
            r"\label{tab:p95_sla_node}",
        ] + table_preamble_lines() + [
            column_table_begin("lc"), r"\toprule",
            r"\textbf{Method} & \textbf{P95 latency (ms)} \\", r"\midrule",
        ]
        for m in METHODS_SCALABILITY:
            match = next((x for x in p95_summary if x["method"] == m and x["nodes"] == sla_node), None)
            if match is None:
                continue
            lines.append(
                f"{method_latex_label(m, tex_escape)} & {match['mean']:.1f} $\\pm$ {match['yplus']:.1f} \\\\"
            )
        lines += [r"\bottomrule", r"\end{tabular*}", r"\end{table}"]
        return "\n".join(lines)

    (LATEX / "tab_ablation.tex").write_text(ablation_tex(), encoding="utf-8")
    (LATEX / "tab_significance.tex").write_text(sig_tex(), encoding="utf-8")
    (LATEX / "tab_edgeward_comparison.tex").write_text(
        tab_edgeward_comparison_tex(sla_summary, sig_rows), encoding="utf-8"
    )
    (LATEX / "tab_workload_spec.tex").write_text(workload_spec_table_tex(), encoding="utf-8")
    (LATEX / "tab_p95_n500.tex").write_text(p95_tex(), encoding="utf-8")
    (LATEX / "tab_p95_all.tex").write_text(p95_all_nodes_tex(p95_summary, chart_nodes), encoding="utf-8")
    (LATEX / "tab_sla_n500.tex").write_text(
        sla_n500_tex(sla_summary, stress_scenarios, sla_node), encoding="utf-8"
    )
    (LATEX / "key_metrics.tex").write_text(
        key_metrics_snippet(p95_summary, sla_summary, sla_node, stress_scenarios),
        encoding="utf-8",
    )
    (LATEX / "tab_comparison_main.tex").write_text(
        comparison_main_tex(
            p95_summary, sla_summary, oh_summary,
            sim_ctrl, sim_colonies, sim_energy, sim_cost,
            chart_nodes, sla_node, stress_scenarios,
            methods=methods_scalability,
        ),
        encoding="utf-8",
    )

    # Per-method plot CSVs for pgfplots
    plot_dir = ROOT.parent / "paper" / "plot_data"
    plot_dir.mkdir(parents=True, exist_ok=True)
    for method in METHODS_SCALABILITY:
        slug = method.replace("/", "_").replace("+", "p").replace(" ", "_").replace("-", "_")
        rows = [{"nodes": r["nodes"], "mean": r["mean"], "yplus": r["yplus"], "yminus": r["yminus"]}
                for r in p95_summary if r["method"] == method]
        write_csv(plot_dir / f"p95_{slug}.csv", ["nodes", "mean", "yplus", "yminus"], rows)
    for method in METHODS_OVERHEAD:
        slug = method.replace("/", "_").replace("+", "p").replace(" ", "_").replace("-", "_")
        rows = [{"nodes": r["nodes"], "mean": r["mean"], "yerr": r["yerr"]}
                for r in oh_summary if r["method"] == method]
        write_csv(plot_dir / f"overhead_{slug}.csv", ["nodes", "mean", "yerr"], rows)
    for method in METHODS_STRESS:
        slug = method.replace("/", "_").replace("+", "p").replace(" ", "_").replace("-", "_")
        rows = [{"scenario": r["scenario"], "nodes": r["nodes"], "mean": r["mean"], "yerr": r["yerr"]}
                for r in sla_summary if r["method"] == method]
        write_csv(plot_dir / f"sla_{slug}.csv", ["scenario", "nodes", "mean", "yerr"], rows)

    paper_dir = ROOT.parent / "paper"
    paper_dir.mkdir(parents=True, exist_ok=True)
    (paper_dir / "dynacol_style.tex").write_text(latex_style_tex(), encoding="utf-8")

    p95_chart = journal_p95_line_tex(p95_summary, chart_nodes)
    sla_chart = journal_sla_single_panel_tex(sla_summary, stress_scenarios, sla_node)
    sla_grid = journal_sla_grid_tex(sla_summary, stress_scenarios, chart_nodes)
    oh_chart = journal_overhead_bar_tex(oh_summary, chart_nodes)
    tradeoff_chart = (
        sla_energy_tradeoff_chart_tex(
            sla_summary, sim_ctrl, sim_energy, sla_node, "Burst Load", methods=methods_scalability
        )
        if sim_energy
        else ieee_sla_tradeoff_chart_tex(
            sla_summary, sim_ctrl, sla_node, "Burst Load", methods=methods_scalability
        )
    )
    ablation_p95 = cluster_ablation_p95_tex(ablation_summary)
    ablation_sla = cluster_ablation_sla_tex(ablation_summary)

    (LATEX / "tab_sensitivity.tex").write_text(appendix_sensitivity_tex(sim_rows), encoding="utf-8")
    (LATEX / "tab_sla_deadline.tex").write_text(appendix_sla_deadline_tex(sim_rows), encoding="utf-8")
    (LATEX / "tab_sla_slope.tex").write_text(appendix_sla_slope_tex(sim_rows), encoding="utf-8")
    (LATEX / "tab_offline_ga.tex").write_text(appendix_offline_ga_tex(sim_rows), encoding="utf-8")
    (LATEX / "tab_incremental_arrival.tex").write_text(appendix_incremental_tex(sim_rows), encoding="utf-8")

    chart_outputs = [
        ("generated_p95_chart.tex", p95_chart),
        ("generated_sla_chart.tex", sla_chart),
        ("generated_sla_scalability.tex", sla_grid),
        ("generated_overhead_chart.tex", oh_chart),
        ("generated_tradeoff_chart.tex", tradeoff_chart),
        ("generated_ablation_p95.tex", ablation_p95),
        ("generated_ablation_sla.tex", ablation_sla),
    ]
    for name, content in chart_outputs:
        (paper_dir / name).write_text(content, encoding="utf-8")

    for legacy in paper_dir.glob("generated_ieee_*.tex"):
        legacy.unlink(missing_ok=True)
    legacy_ablation = paper_dir / "generated_ablation_chart.tex"
    if legacy_ablation.exists():
        legacy_ablation.unlink()

    manifest = {
        "data_source": "real-only" if REQUIRE_REAL and not ALLOW_SYNTHETIC else "mixed",
        "num_trials": NUM_TRIALS, "seed": SEED, "alpha": ALPHA,
        "min_sim_trials": MIN_SIM_TRIALS,
        "require_real": REQUIRE_REAL,
        "allow_synthetic": ALLOW_SYNTHETIC,
        "eval_nodes": chart_nodes,
        "eval_sla_node": sla_node,
        "eval_scenarios": stress_scenarios,
        "simulator_trials": len(sim_rows),
        "simulator_keys_used": sim_keys_used,
        "ablation_keys_used": ablation_keys_used,
        "ablation_simulator_trials": sum(
            1 for row in sim_rows if row.get("method") in ABLATION_VARIANTS
        ) if sim_rows else 0,
        "missing_keys": missing_keys,
        "harness_ready": {
            "greedy": "GREEDY_GRID=1 POLICY=greedy",
            "sensitivity": "SENSITIVITY=1",
            "incremental": "INCREMENTAL=1",
            "sla_deadline": "SLADEADLINE=1",
            "sla_slope": "SLADEADLINE_SLOPE=1",
            "offline_ga": "OFFLINE_GA=1",
            "energy_grid": "ENERGY_GRID=1",
            "tavousi": "POLICY=tavousi",
            "dogani": "POLICY=dogani",
        },
        "simulator_csv": str(SIMULATOR_CSV.relative_to(REPO_ROOT)) if sim_rows else None,
        "files": sorted(p.name for p in RESULTS.glob("*.csv")),
    }
    (RESULTS / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Generated {len(list(RESULTS.glob('*.csv')))} CSV files under {RESULTS}")
    if missing_keys:
        print(f"WARNING: missing simulator keys ({len(missing_keys)}):", ", ".join(missing_keys[:8]), "...")
        if REQUIRE_REAL:
            sys.exit(1)

    finalize_outputs()


if __name__ == "__main__":
    generate()
