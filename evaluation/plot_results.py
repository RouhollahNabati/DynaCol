#!/usr/bin/env python3
"""
Render publication-quality figures from evaluation summary CSVs (stdlib + SVG).

Outputs SVG and PNG (via ImageMagick `convert` when available) under evaluation/results/figures/.
Styling is unified with paper/dynacol_style.tex via evaluation/visual_theme.py.
"""

from __future__ import annotations

import csv
import math
import os
import shutil
import statistics
import subprocess
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

from visual_theme import (
    ABLATION_COLORS,
    AXIS_COLOR,
    BAR_OPACITY,
    BAR_RX,
    BG_COLOR,
    FIG_HEIGHT,
    FIG_WIDTH,
    FONT_AXIS_LABEL,
    FONT_FAMILY,
    FONT_LEGEND,
    FONT_TICK,
    FONT_TICK_SMALL,
    FONT_TITLE,
    FONT_TITLE_COLOR,
    FONT_TITLE_WEIGHT,
    GRID_COLOR,
    LEGEND_LINE_HEIGHT,
    LEGEND_SWATCH,
    LINE_WIDTH,
    MARGIN_BOTTOM,
    MARGIN_LEFT,
    MARGIN_RIGHT,
    MARGIN_TOP,
    MARKER_RADIUS,
    PALETTE,
    SCENARIO_ORDER,
    SCENARIO_SHORT,
    heatmap_rgb,
    method_color,
    method_line_dash,
    methods_present,
)

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"
FIGURES = RESULTS / "figures"


def load_csv(path: Path) -> List[dict]:
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def load_sim_trials() -> List[dict]:
    rows: List[dict] = []
    for path in sorted((RESULTS / "simulator").glob("trials*.csv")):
        rows.extend(load_csv(path))
    return rows


class SvgCanvas:
    def __init__(self, width: int, height: int, title: str = "") -> None:
        self.width = width
        self.height = height
        self.title = title
        self.parts: List[str] = []

    def _esc(self, s: str) -> str:
        return (
            s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('"', "&quot;")
        )

    def rect(self, x: float, y: float, w: float, h: float, fill: str, opacity: float = 1.0, rx: float = 0) -> None:
        self.parts.append(
            f'<rect x="{x:.2f}" y="{y:.2f}" width="{w:.2f}" height="{h:.2f}" '
            f'fill="{fill}" opacity="{opacity:.2f}" rx="{rx:.2f}"/>'
        )

    def line(self, x1: float, y1: float, x2: float, y2: float, stroke: str, width: float = LINE_WIDTH,
             dash: str = "") -> None:
        dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
        self.parts.append(
            f'<line x1="{x1:.2f}" y1="{y1:.2f}" x2="{x2:.2f}" y2="{y2:.2f}" '
            f'stroke="{stroke}" stroke-width="{width:.2f}"{dash_attr}/>'
        )

    def text(self, x: float, y: float, label: str, size: int = FONT_AXIS_LABEL, anchor: str = "middle",
             weight: str = "normal", fill: str = PALETTE["slate"]) -> None:
        self.parts.append(
            f'<text x="{x:.2f}" y="{y:.2f}" font-family="{FONT_FAMILY}" '
            f'font-size="{size}" font-weight="{weight}" text-anchor="{anchor}" fill="{fill}">'
            f"{self._esc(label)}</text>"
        )

    def circle(self, x: float, y: float, r: float, fill: str, stroke: str = "#fff", sw: float = 1.0) -> None:
        self.parts.append(
            f'<circle cx="{x:.2f}" cy="{y:.2f}" r="{r:.2f}" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{sw:.2f}"/>'
        )

    def polyline(self, points: Sequence[Tuple[float, float]], stroke: str, width: float = LINE_WIDTH,
                 fill: str = "none", dash: str = "") -> None:
        pts = " ".join(f"{x:.2f},{y:.2f}" for x, y in points)
        dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
        self.parts.append(
            f'<polyline points="{pts}" fill="{fill}" stroke="{stroke}" stroke-width="{width:.2f}" '
            f'stroke-linejoin="round" stroke-linecap="round"{dash_attr}/>'
        )

    def error_bar_v(self, x: float, y: float, y_top: float, y_bot: float, color: str) -> None:
        self.line(x, y_top, x, y_bot, color, 1.2)
        cap = 4
        self.line(x - cap, y_top, x + cap, y_top, color, 1.2)
        self.line(x - cap, y_bot, x + cap, y_bot, color, 1.2)

    def legend(self, items: List[Tuple[str, str]], x: float, y: float) -> None:
        box_w = LEGEND_SWATCH + 120
        box_h = len(items) * LEGEND_LINE_HEIGHT + 8
        self.rect(x - 4, y - 12, box_w, box_h, "white", opacity=0.94, rx=2)
        self.rect(x - 4, y - 12, box_w, box_h, "none")
        self.line(x - 4, y - 12, x - 4 + box_w, y - 12, GRID_COLOR, 0.8)
        self.line(x - 4, y - 12 + box_h, x - 4 + box_w, y - 12 + box_h, GRID_COLOR, 0.8)
        self.line(x - 4, y - 12, x - 4, y - 12 + box_h, GRID_COLOR, 0.8)
        self.line(x - 4 + box_w, y - 12, x - 4 + box_w, y - 12 + box_h, GRID_COLOR, 0.8)
        for i, (label, color) in enumerate(items):
            yy = y + i * LEGEND_LINE_HEIGHT
            self.rect(x, yy - 9, LEGEND_SWATCH, LEGEND_SWATCH, color, rx=BAR_RX)
            self.text(x + LEGEND_SWATCH + 6, yy + 3, label, size=FONT_LEGEND, anchor="start")

    def to_svg(self) -> str:
        title_block = ""
        if self.title:
            title_block = (
                f'<text x="{self.width / 2:.1f}" y="28" font-family="{FONT_FAMILY}" '
                f'font-size="{FONT_TITLE}" font-weight="{FONT_TITLE_WEIGHT}" text-anchor="middle" '
                f'fill="{FONT_TITLE_COLOR}">'
                f"{self._esc(self.title)}</text>"
            )
        body = "\n  ".join(self.parts)
        return (
            f'<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.width}" height="{self.height}" '
            f'viewBox="0 0 {self.width} {self.height}">\n'
            f'  <rect width="100%" height="100%" fill="{BG_COLOR}"/>\n'
            f"  {title_block}\n"
            f"  {body}\n"
            f"</svg>\n"
        )


def log_map(value: float, vmin: float, vmax: float, pmin: float, pmax: float) -> float:
    lv = math.log10(max(value, 1e-6))
    l0 = math.log10(max(vmin, 1e-6))
    l1 = math.log10(max(vmax, 1e-6))
    if abs(l1 - l0) < 1e-9:
        return (pmin + pmax) / 2
    return pmax - (lv - l0) / (l1 - l0) * (pmax - pmin)


def lin_map(value: float, vmin: float, vmax: float, pmin: float, pmax: float) -> float:
    if abs(vmax - vmin) < 1e-9:
        return (pmin + pmax) / 2
    return pmax - (value - vmin) / (vmax - vmin) * (pmax - pmin)


def nice_log_ticks(vmin: float, vmax: float) -> List[float]:
    ticks = []
    lo = int(math.floor(math.log10(max(vmin, 1))))
    hi = int(math.ceil(math.log10(max(vmax, 1))))
    for p in range(lo, hi + 1):
        for m in (1, 2, 5):
            v = m * 10 ** p
            if vmin * 0.85 <= v <= vmax * 1.15:
                ticks.append(float(v))
    return sorted(set(ticks))


def draw_axes_log_y(
    c: SvgCanvas,
    left: float, top: float, right: float, bottom: float,
    x_labels: List[str], y_min: float, y_max: float,
    x_title: str, y_title: str,
) -> Tuple[float, float, float, float]:
    c.line(left, top, left, bottom, AXIS_COLOR, 1.5)
    c.line(left, bottom, right, bottom, AXIS_COLOR, 1.5)
    c.text((left + right) / 2, bottom + 42, x_title, size=FONT_AXIS_LABEL, fill="#000")
    c.text(22, (top + bottom) / 2, y_title, size=FONT_AXIS_LABEL, anchor="middle", fill="#000")
    n = len(x_labels)
    for i, lbl in enumerate(x_labels):
        x = left + (i + 0.5) * (right - left) / max(n, 1)
        c.text(x, bottom + 16, lbl, size=FONT_TICK)
        c.line(x, bottom, x, bottom + 4, AXIS_COLOR, 1)
    for t in nice_log_ticks(y_min, y_max):
        y = log_map(t, y_min, y_max, top, bottom)
        c.line(left - 4, y, left, y, AXIS_COLOR, 1)
        c.text(left - 8, y + 4, f"{t:g}", size=FONT_TICK_SMALL, anchor="end")
        c.line(left, y, right, y, GRID_COLOR, 0.7, dash="4,4")
    return left, top, right, bottom


def save_canvas(c: SvgCanvas, name: str) -> None:
    FIGURES.mkdir(parents=True, exist_ok=True)
    svg_path = FIGURES / f"{name}.svg"
    svg_path.write_text(c.to_svg(), encoding="utf-8")
    print(f"  wrote {svg_path}")
    convert = shutil.which("convert")
    rsvg = shutil.which("rsvg-convert")
    if rsvg:
        pdf_path = FIGURES / f"{name}.pdf"
        subprocess.run([rsvg, "-f", "pdf", "-o", str(pdf_path), str(svg_path)], check=False)
        if pdf_path.exists():
            print(f"  wrote {pdf_path}")
    if convert:
        png_path = FIGURES / f"{name}.png"
        subprocess.run([convert, "-density", "200", str(svg_path), str(png_path)], check=False)
        if png_path.exists():
            print(f"  wrote {png_path}")


def plot_p95_scalability(p95_summary: List[dict]) -> None:
    methods = methods_present(p95_summary)
    nodes = sorted({int(r["nodes"]) for r in p95_summary})
    if not methods or len(nodes) < 2:
        return
    vals = [float(r["mean"]) for r in p95_summary if float(r["mean"]) > 0]
    y_min = min(vals) * 0.7
    y_max = max(vals) * 1.4
    w, h = FIG_WIDTH, FIG_HEIGHT
    c = SvgCanvas(w, h, "Scalability: P95 latency (normal load)")
    left, top, right, bottom = MARGIN_LEFT, MARGIN_TOP, w - MARGIN_RIGHT, h - MARGIN_BOTTOM
    draw_axes_log_y(c, left, top, right, bottom, [str(n) for n in nodes], y_min, y_max,
                    "Number of fog nodes (N)", "P95 latency (ms)")
    legend_items = []
    for method in methods:
        pts = []
        color = method_color(method)
        for n in nodes:
            row = next((r for r in p95_summary if r["method"] == method and int(r["nodes"]) == n), None)
            if not row:
                continue
            mean = float(row["mean"])
            err = float(row.get("yplus", 0))
            xi = nodes.index(n)
            x = left + (xi + 0.5) * (right - left) / len(nodes)
            y = log_map(mean, y_min, y_max, top, bottom)
            y_hi = log_map(mean + err, y_min, y_max, top, bottom)
            y_lo = log_map(max(mean - err, 1e-3), y_min, y_max, top, bottom)
            c.error_bar_v(x, y_hi, min(y_hi, y_lo), max(y_hi, y_lo), color)
            c.circle(x, y, MARKER_RADIUS, color)
            pts.append((x, y))
        if len(pts) >= 2:
            c.polyline(pts, color, dash=method_line_dash(method))
        legend_items.append((method, color))
    c.legend(legend_items, right - 175, top + 6)
    save_canvas(c, "fig_p95_scalability")


def plot_sla_grouped(sla_summary: List[dict], sla_node: int) -> None:
    methods = methods_present(sla_summary)
    scenarios = [s for s in SCENARIO_ORDER if any(r["scenario"] == s for r in sla_summary)]
    if not methods or not scenarios:
        return
    w, h = 860, 460
    c = SvgCanvas(w, h, f"SLA violations by scenario (N={sla_node})")
    left, top, right, bottom = 72, MARGIN_TOP, w - 24, h - 90
    ymax = max(
        float(r["mean"]) + float(r.get("yerr", 0))
        for r in sla_summary if int(r["nodes"]) == sla_node
    )
    ymax = max(ymax * 1.2, 1.0)
    c.line(left, top, left, bottom, AXIS_COLOR, 1.5)
    c.line(left, bottom, right, bottom, AXIS_COLOR, 1.5)
    c.text((left + right) / 2, bottom + 52, "Stress scenario", size=FONT_AXIS_LABEL, fill="#000")
    c.text(24, (top + bottom) / 2, "SLA violation (%)", size=FONT_AXIS_LABEL, anchor="middle", fill="#000")
    n_m, n_s = len(methods), len(scenarios)
    group_w = (right - left) / max(n_s, 1) * 0.75
    bar_w = group_w / n_m
    legend_items = []
    for mi, method in enumerate(methods):
        color = method_color(method)
        legend_items.append((method, color))
        for si, sc in enumerate(scenarios):
            row = next(
                (r for r in sla_summary
                 if r["method"] == method and r["scenario"] == sc and int(r["nodes"]) == sla_node),
                None,
            )
            if not row:
                continue
            mean = float(row["mean"])
            err = float(row.get("yerr", 0))
            gx = left + (si + 0.5) * (right - left) / n_s
            x = gx - group_w / 2 + mi * bar_w + bar_w * 0.08
            y = lin_map(mean, 0, ymax, bottom, top)
            y0 = bottom
            c.rect(x, y, bar_w * 0.84, y0 - y, color, opacity=BAR_OPACITY, rx=BAR_RX)
            if err > 0:
                ex = x + bar_w * 0.42
                ey = lin_map(mean + err, 0, ymax, bottom, top)
                c.error_bar_v(ex, ey, ey, y, color)
            if mi == 0:
                c.text(gx, bottom + 16, SCENARIO_SHORT.get(sc, sc), size=FONT_TICK_SMALL)
    for t in lin_ticks(0, ymax, 5):
        y = lin_map(t, 0, ymax, bottom, top)
        c.line(left - 4, y, left, y, AXIS_COLOR, 1)
        c.text(left - 8, y + 4, f"{t:g}", size=FONT_TICK_SMALL, anchor="end")
        c.line(left, y, right, y, GRID_COLOR, 0.7, dash="4,4")
    c.legend(legend_items, right - 175, top + 4)
    save_canvas(c, "fig_sla_scenarios")


def lin_ticks(vmin: float, vmax: float, n: int) -> List[float]:
    if vmax <= vmin:
        return [vmin]
    step = (vmax - vmin) / max(n - 1, 1)
    return [vmin + i * step for i in range(n)]


def plot_sla_heatmap(sla_summary: List[dict], sla_node: int) -> None:
    methods = methods_present(sla_summary)
    scenarios = [s for s in SCENARIO_ORDER if any(r["scenario"] == s for r in sla_summary)]
    if not methods or not scenarios:
        return
    matrix: List[List[float]] = []
    vmax = 0.0
    for method in methods:
        row_vals = []
        for sc in scenarios:
            match = next(
                (r for r in sla_summary
                 if r["method"] == method and r["scenario"] == sc and int(r["nodes"]) == sla_node),
                None,
            )
            v = float(match["mean"]) if match else 0.0
            row_vals.append(v)
            vmax = max(vmax, v)
        matrix.append(row_vals)
    vmax = max(vmax, 1.0)
    cell_w = 72
    cell_h = 36
    left, top = 150, 56
    w = left + len(scenarios) * cell_w + 90
    h = top + len(methods) * cell_h + 80
    c = SvgCanvas(w, h, f"SLA heatmap (N={sla_node})")
    for j, sc in enumerate(scenarios):
        c.text(left + j * cell_w + cell_w / 2, top - 10, SCENARIO_SHORT.get(sc, sc), size=FONT_TICK_SMALL)
    for i, method in enumerate(methods):
        c.text(left - 10, top + i * cell_h + cell_h / 2 + 4, method,
               size=FONT_TICK_SMALL, anchor="end", fill=method_color(method))
        for j, v in enumerate(matrix[i]):
            t = min(v / vmax, 1.0)
            r, g, b = heatmap_rgb(v, vmax)
            color = f"rgb({r},{g},{b})"
            x = left + j * cell_w
            y = top + i * cell_h
            c.rect(x, y, cell_w - 4, cell_h - 4, color, rx=BAR_RX)
            c.text(x + (cell_w - 4) / 2, y + cell_h / 2 + 4, f"{v:.1f}", size=FONT_TICK_SMALL,
                   fill="#111" if t < 0.55 else "#fff")
    save_canvas(c, "fig_sla_heatmap")


def plot_overhead(oh_summary: List[dict]) -> None:
    methods = methods_present(oh_summary)
    nodes = sorted({int(r["nodes"]) for r in oh_summary})
    if not methods or len(nodes) < 2:
        return
    vals = [float(r["mean"]) for r in oh_summary if float(r["mean"]) > 0]
    y_min = min(vals) * 0.6
    y_max = max(vals) * 1.5
    w, h = FIG_WIDTH, FIG_HEIGHT
    c = SvgCanvas(w, h, "Control-plane overhead vs. scale")
    left, top, right, bottom = MARGIN_LEFT, MARGIN_TOP, w - MARGIN_RIGHT, h - MARGIN_BOTTOM
    draw_axes_log_y(c, left, top, right, bottom, [str(n) for n in nodes], y_min, y_max,
                    "Number of fog nodes (N)", "Normalized overhead")
    legend_items = []
    for method in methods:
        pts = []
        color = method_color(method)
        for n in nodes:
            row = next((r for r in oh_summary if r["method"] == method and int(r["nodes"]) == n), None)
            if not row:
                continue
            mean = float(row["mean"])
            xi = nodes.index(n)
            x = left + (xi + 0.5) * (right - left) / len(nodes)
            y = log_map(mean, y_min, y_max, top, bottom)
            c.circle(x, y, MARKER_RADIUS, color)
            pts.append((x, y))
        if len(pts) >= 2:
            c.polyline(pts, color, dash=method_line_dash(method))
        legend_items.append((method, color))
    c.legend(legend_items, right - 175, top + 6)
    save_canvas(c, "fig_overhead_scalability")


def plot_tradeoff(p95_summary: List[dict], sim_trials: List[dict]) -> None:
    methods = methods_present(p95_summary)
    if not sim_trials:
        return
    points: List[Tuple[str, int, float, float]] = []
    for method in methods:
        for n in sorted({int(r["nodes"]) for r in p95_summary if r["method"] == method}):
            p95_row = next((r for r in p95_summary if r["method"] == method and int(r["nodes"]) == n), None)
            ctrl = [
                float(r["control_messages"]) for r in sim_trials
                if r["method"] == method and int(r["nodes"]) == n and r["scenario"] == "Normal Load"
            ]
            if p95_row and ctrl:
                points.append((method, n, statistics.mean(ctrl), float(p95_row["mean"])))
    if not points:
        return
    xs = [p[2] for p in points]
    ys = [p[3] for p in points]
    x_min, x_max = min(xs) * 0.7, max(xs) * 1.4
    y_min, y_max = min(ys) * 0.7, max(ys) * 1.4
    w, h = FIG_WIDTH, FIG_HEIGHT
    c = SvgCanvas(w, h, "Latency vs. control traffic trade-off")
    left, top, right, bottom = MARGIN_LEFT, MARGIN_TOP, w - MARGIN_RIGHT, h - MARGIN_BOTTOM
    draw_axes_log_y(c, left, top, right, bottom,
                    [f"{int(x_min)}", "…", f"{int(x_max)}"], y_min, y_max,
                    "Control messages (mean)", "P95 latency (ms)")
    for t in nice_log_ticks(x_min, x_max):
        x = log_map(t, x_min, x_max, left, right)
        c.line(x, bottom, x, bottom + 4, AXIS_COLOR, 1)
        c.text(x, bottom + 16, f"{t:g}", size=FONT_TICK_SMALL)
    legend_items = []
    for method in methods:
        color = method_color(method)
        mpts = [(p[2], p[3], p[1]) for p in points if p[0] == method]
        if not mpts:
            continue
        poly = []
        for cx, cy, n in sorted(mpts):
            x = log_map(cx, x_min, x_max, left, right)
            y = log_map(cy, y_min, y_max, top, bottom)
            c.circle(x, y, MARKER_RADIUS, color)
            c.text(x + 8, y - 6, f"N={n}", size=8, anchor="start", fill=color)
            poly.append((x, y))
        if len(poly) >= 2:
            c.polyline(poly, color)
        legend_items.append((method, color))
    c.legend(legend_items, right - 175, top + 6)
    save_canvas(c, "fig_tradeoff")


def plot_dashboard(
    p95_summary: List[dict],
    sla_summary: List[dict],
    oh_summary: List[dict],
    sla_node: int,
) -> None:
    """Write a simple HTML gallery linking all figures."""
    blue = PALETTE["blue"]
    html = [
        "<!DOCTYPE html><html><head><meta charset='utf-8'>",
        "<title>DynaCol Evaluation Figures</title>",
        f"<style>body{{font-family:{FONT_FAMILY};margin:24px;background:#f7f7f9;}}",
        f"h1{{color:{blue};}} .grid{{display:grid;grid-template-columns:1fr 1fr;gap:20px;}}",
        "figure{background:#fff;border-radius:8px;padding:12px;box-shadow:0 2px 8px #0001;}",
        f"figcaption{{font-size:13px;color:{PALETTE['slate']};margin-top:8px;}}",
        "</style></head><body>",
        "<h1>DynaCol Evaluation — Figure Gallery</h1>",
        "<div class='grid'>",
    ]
    captions = {
        "fig_p95_scalability": "P95 end-to-end latency across fog scale (log Y).",
        "fig_sla_scenarios": f"SLA violation rate per stress scenario at N={sla_node}.",
        "fig_sla_heatmap": f"Heatmap of SLA violations (N={sla_node}).",
        "fig_overhead_scalability": "Normalized control-plane overhead vs. N.",
        "fig_tradeoff": "P95 latency vs. control-message volume.",
    }
    for name, cap in captions.items():
        png = FIGURES / f"{name}.png"
        svg = FIGURES / f"{name}.svg"
        src = png.name if png.exists() else svg.name
        if not (FIGURES / src).exists():
            continue
        html.append(
            f"<figure><img src='{src}' alt='{name}'><figcaption><b>{name}</b> — {cap}</figcaption></figure>"
        )
    html += ["</div></body></html>"]
    index = FIGURES / "index.html"
    index.write_text("\n".join(html), encoding="utf-8")
    print(f"  wrote {index}")


def plot_ablation(ablation_summary: List[dict]) -> None:
    if not ablation_summary:
        return
    variants = [r["variant"] for r in ablation_summary]
    p95_vals = [float(r["p95_mean"]) for r in ablation_summary]
    w, h = FIG_WIDTH, int(FIG_HEIGHT * 0.85)
    c = SvgCanvas(w, h, "Ablation at N=500: P95 latency (ms)")
    left, top, right, bottom = MARGIN_LEFT, MARGIN_TOP, w - MARGIN_RIGHT, h - MARGIN_BOTTOM
    y_max = max(p95_vals) * 1.25
    draw_axes_log_y(c, left, top, right, bottom, variants, 1.0, y_max,
                    "Variant", "P95 latency (ms)")
    bar_w = (right - left) / max(len(variants), 1) * 0.55
    for i, (variant, val) in enumerate(zip(variants, p95_vals)):
        x = left + (i + 0.5) * (right - left) / len(variants)
        y = log_map(val, 1.0, y_max, top, bottom)
        color = ABLATION_COLORS.get(variant, PALETTE["slate"])
        c.rect(x - bar_w / 2, y, bar_w, bottom - y, color, rx=BAR_RX)
        c.text(x, bottom + 14, variant.replace(" ", "\n"), size=7, anchor="middle")
    save_canvas(c, "fig_ablation_p95")


def main() -> None:
    p95_summary = load_csv(RESULTS / "p95_latency_summary.csv")
    sla_summary = load_csv(RESULTS / "sla_violation_summary.csv")
    oh_summary = load_csv(RESULTS / "control_overhead_summary.csv")
    sim_trials = load_sim_trials()

    sla_node = int(os.environ.get("EVAL_SLA_NODE", "500"))
    if sla_summary:
        nodes_in_sla = sorted({int(r["nodes"]) for r in sla_summary})
        if sla_node not in nodes_in_sla and nodes_in_sla:
            sla_node = nodes_in_sla[-1]

    print(f"Plotting figures -> {FIGURES}")
    plot_p95_scalability(p95_summary)
    plot_sla_grouped(sla_summary, sla_node)
    plot_sla_heatmap(sla_summary, sla_node)
    plot_overhead(oh_summary)
    plot_tradeoff(p95_summary, sim_trials)
    plot_dashboard(p95_summary, sla_summary, oh_summary, sla_node)
    ablation_summary = load_csv(RESULTS / "ablation_summary.csv")
    plot_ablation(ablation_summary)
    print("Done.")


if __name__ == "__main__":
    main()
