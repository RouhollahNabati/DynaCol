"""Evaluation workload parameters — mirror org.fog.dynacol.eval.EvaluationWorkloadSpec."""

from __future__ import annotations

BASE_DEADLINE_MS = 60.0
N_SLOPE = 0.1

SCENARIO_LABELS = {
    "normal": "Normal Load",
    "burst": "Burst Load",
    "churn": "Churn",
    "mobility": "Mobility",
    "mobility_burst": "Mobility + Burst",
    "fcm_failure": "FCM Failure",
}


def base_deadline_ms(target_nodes: int) -> float:
    return BASE_DEADLINE_MS + target_nodes * N_SLOPE


def sla_deadline_ms(scenario_key: str, target_nodes: int) -> float:
    base = base_deadline_ms(target_nodes)
    if scenario_key in ("burst", "mobility_burst"):
        return base * 0.85
    if scenario_key == "mobility":
        return base * 0.90
    if scenario_key == "churn":
        return base * 0.88
    if scenario_key == "fcm_failure":
        return base * 0.82
    return base


def sensor_period_ms(scenario_key: str) -> float:
    if scenario_key in ("burst", "mobility+burst", "mobility_burst"):
        return 1.0
    return 5.0


def apply_deadline_scale(deadline_ms: float, scale: float) -> float:
    if scale <= 0.0:
        return deadline_ms
    return deadline_ms * scale


def base_deadline_with_slope(target_nodes: int, n_slope: float) -> float:
    return BASE_DEADLINE_MS + target_nodes * n_slope


def n_slope_variant_label(n_slope: float) -> str:
    pct = int(round(n_slope * 1000))
    return f"sla_slope{pct:03d}"


def sla_deadline_variant_label(scale: float) -> str:
    pct = int(round(scale * 100))
    return f"sla_m{pct}"


def deadline_formula_latex(scenario_key: str) -> str:
    if scenario_key == "burst":
        return r"$0.85\,D_{\mathrm{base}}(N)$"
    if scenario_key == "churn":
        return r"$0.88\,D_{\mathrm{base}}(N)$"
    if scenario_key == "mobility":
        return r"$0.90\,D_{\mathrm{base}}(N)$"
    if scenario_key == "mobility_burst":
        return r"$0.85\,D_{\mathrm{base}}(N)$"
    return r"$D_{\mathrm{base}}(N)$"
