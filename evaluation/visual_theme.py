"""
Unified visual theme for DynaCol evaluation outputs (SVG figures, LaTeX tables, TikZ charts).

Single source of truth — colors and typography match paper/dynacol_style.tex.
Cluster Computing (Springer two-column) axis presets: clusterSubfig*, clusterSpan*, clusterGrid*.
"""

from __future__ import annotations

from typing import Dict, List, Sequence, Tuple

# ---------------------------------------------------------------------------
# Palette (colorblind-aware; matches manuscript dynacol_* colors)
# ---------------------------------------------------------------------------

PALETTE: Dict[str, str] = {
    "blue": "#2563EB",
    "teal": "#0F766E",
    "orange": "#EA580C",
    "purple": "#7C3AED",
    "green": "#16A34A",
    "red": "#DC2626",
    "slate": "#475569",
    "grid": "#CBD5E1",
    "axis": "#64748B",
    "bg": "#FAFAFA",
}

METHOD_ORDER: List[str] = [
    "DynaCol/DCBO",
    "FogPlan-MinCost",
    "Static-DCBO",
    "Greedy-Nearest",
    "Tavousi-Fuzzy",
    "Dogani-TwoTier",
    "Edgeward",
    "Offline-GA",
]

METHOD_COLORS: Dict[str, str] = {
    "DynaCol/DCBO": PALETTE["blue"],
    "FogPlan-MinCost": PALETTE["orange"],
    "Static-DCBO": PALETTE["teal"],
    "Greedy-Nearest": PALETTE["green"],
    "Tavousi-Fuzzy": PALETTE["red"],
    "Dogani-TwoTier": PALETTE["slate"],
    "Edgeward": PALETTE["purple"],
    "Offline-GA": PALETTE["slate"],
}

METHOD_LATEX_COLORS: Dict[str, str] = {
    "DynaCol/DCBO": "dynacolBlue",
    "FogPlan-MinCost": "dynacolOrange",
    "Static-DCBO": "dynacolTeal",
    "Greedy-Nearest": "dynacolGreen",
    "Tavousi-Fuzzy": "dynacolRed",
    "Dogani-TwoTier": "dynacolSlate",
    "Edgeward": "dynacolPurple",
    "Offline-GA": "dynacolSlate",
}

METHOD_LINE_STYLES: Dict[str, str] = {
    "DynaCol/DCBO": r"mark=*, solid, color=dynacolBlue, line width=1.1pt",
    "FogPlan-MinCost": r"mark=square*, densely dashed, color=dynacolOrange, line width=1.1pt",
    "Static-DCBO": r"mark=triangle*, densely dotted, color=dynacolTeal, line width=1.1pt",
    "Greedy-Nearest": r"mark=+, solid, color=dynacolGreen, line width=1.1pt",
    "Tavousi-Fuzzy": r"mark=pentagon*, loosely dashed, color=dynacolRed, line width=1.1pt",
    "Dogani-TwoTier": r"mark=otimes*, loosely dotted, color=dynacolSlate, line width=1.1pt",
    "Edgeward": r"mark=diamond*, dashdotted, color=dynacolPurple, line width=1.1pt",
}

METHOD_LINE_DASH: Dict[str, str] = {
    "DynaCol/DCBO": "",
    "FogPlan-MinCost": "8,4",
    "Static-DCBO": "2,3",
    "Edgeward": "8,3,2,3",
}

METHOD_BAR_STYLES: Dict[str, str] = {
    "DynaCol/DCBO": r"draw=dynacolBlue!85!black, fill=dynacolBlue!72",
    "FogPlan-MinCost": r"draw=dynacolOrange!85!black, fill=dynacolOrange!72",
    "Static-DCBO": r"draw=dynacolTeal!85!black, fill=dynacolTeal!70",
    "Edgeward": r"draw=dynacolPurple!85!black, fill=dynacolPurple!68",
}

ABLATION_VARIANTS: List[str] = [
    "Full DCBO",
    "No-Handover",
    "CRT-only",
    "GRT-only",
]

ABLATION_COLORS: Dict[str, str] = {
    "Full DCBO": PALETTE["blue"],
    "No-Handover": PALETTE["orange"],
    "CRT-only": PALETTE["teal"],
    "GRT-only": PALETTE["purple"],
    "No-Learning": PALETTE["green"],
}

SCENARIO_ORDER: List[str] = [
    "Normal Load",
    "Burst Load",
    "Mobility",
    "Mobility + Burst",
    "Churn",
    "FCM Failure",
]

SCENARIO_SHORT: Dict[str, str] = {
    "Normal Load": "Normal",
    "Burst Load": "Burst",
    "Mobility": "Mobility",
    "Mobility + Burst": "Mob.+Burst",
    "Churn": "Churn",
    "FCM Failure": "FCM fail.",
}

FIG_WIDTH = 760
FIG_HEIGHT = 440
MARGIN_LEFT = 78
MARGIN_TOP = 52
MARGIN_RIGHT = 28
MARGIN_BOTTOM = 72

FONT_FAMILY = "DejaVu Sans,Arial,sans-serif"
FONT_TITLE = 14
FONT_AXIS_LABEL = 11
FONT_TICK = 10
FONT_TICK_SMALL = 9
FONT_LEGEND = 10
FONT_TITLE_WEIGHT = "bold"
FONT_TEXT = PALETTE["slate"]
FONT_TITLE_COLOR = "#111111"

AXIS_COLOR = PALETTE["axis"]
GRID_COLOR = PALETTE["grid"]
BG_COLOR = PALETTE["bg"]
LINE_WIDTH = 1.25
MARKER_RADIUS = 5.0
BAR_OPACITY = 0.86
BAR_RX = 2.0
LEGEND_SWATCH = 14
LEGEND_LINE_HEIGHT = 18

TABLE_SCRIPTSIZE = True
TABLE_ARRAYSTRETCH = "1.10"
TABLE_FLOAT_PLACEMENT = "!t"


def methods_present(rows: Sequence[dict], key: str = "method") -> List[str]:
    present = {r[key] for r in rows}
    return [m for m in METHOD_ORDER if m in present] + sorted(present - set(METHOD_ORDER))


def method_color(method: str, fallback: str = "#444444") -> str:
    return METHOD_COLORS.get(method, fallback)


def method_line_dash(method: str) -> str:
    return METHOD_LINE_DASH.get(method, "")


def method_line_style(method: str) -> str:
    return METHOD_LINE_STYLES.get(method, r"mark=o, solid, line width=1.1pt")


def method_bar_style(method: str) -> str:
    return METHOD_BAR_STYLES.get(method, r"draw=black!60, fill=gray!40")


def method_latex_label(method: str, escape_fn) -> str:
    color = METHOD_LATEX_COLORS.get(method)
    label = escape_fn(method)
    if color:
        return rf"\textcolor{{{color}}}{{\textbf{{{label}}}}}"
    return label


def heatmap_rgb(value: float, vmax: float) -> Tuple[int, int, int]:
    t = min(max(value / max(vmax, 1e-9), 0.0), 1.0)
    if t < 0.5:
        u = t * 2.0
        return _lerp_rgb((248, 250, 252), (234, 88, 12), u)
    u = (t - 0.5) * 2.0
    return _lerp_rgb((234, 88, 12), (220, 38, 38), u)


def _lerp_rgb(a: Tuple[int, int, int], b: Tuple[int, int, int], t: float) -> Tuple[int, int, int]:
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def table_preamble_lines() -> List[str]:
    lines: List[str] = []
    if TABLE_SCRIPTSIZE:
        lines.append(r"\scriptsize")
    lines.append(rf"\renewcommand{{\arraystretch}}{{{TABLE_ARRAYSTRETCH}}}")
    return lines


def table_open(table_star: bool = False) -> str:
    env = "table*" if table_star else "table"
    return rf"\begin{{{env}}}[{TABLE_FLOAT_PLACEMENT}]"


def latex_style_tex() -> str:
    """Full \\definecolor + pgfplotsset block for paper/dynacol_style.tex."""
    p = PALETTE
    legend_below = (
        r"      font=\tiny,",
        r"      at={(0.5,-0.32)},",
        r"      anchor=north,",
        r"      draw=dynacolGrid!80,",
        r"      fill=white,",
        r"      fill opacity=0.95,",
        r"      text opacity=1,",
        r"      rounded corners=2pt,",
        r"      inner xsep=3pt,",
        r"      inner ysep=1.5pt,",
    )
    legend_inside = (
        r"      font=\tiny,",
        r"      at={(0.02,0.98)},",
        r"      anchor=north west,",
        r"      draw=dynacolGrid!80,",
        r"      fill=white,",
        r"      fill opacity=0.95,",
        r"      text opacity=1,",
        r"      rounded corners=2pt,",
        r"      inner xsep=3pt,",
        r"      inner ysep=1.5pt,",
    )
    return "\n".join([
        "% Shared color palette and pgfplots styles for the DynaCol manuscript.",
        rf"\definecolor{{dynacolBlue}}{{HTML}}{{{p['blue'][1:]}}}",
        rf"\definecolor{{dynacolTeal}}{{HTML}}{{{p['teal'][1:]}}}",
        rf"\definecolor{{dynacolOrange}}{{HTML}}{{{p['orange'][1:]}}}",
        rf"\definecolor{{dynacolPurple}}{{HTML}}{{{p['purple'][1:]}}}",
        rf"\definecolor{{dynacolGreen}}{{HTML}}{{{p['green'][1:]}}}",
        rf"\definecolor{{dynacolRed}}{{HTML}}{{{p['red'][1:]}}}",
        rf"\definecolor{{dynacolSlate}}{{HTML}}{{{p['slate'][1:]}}}",
        rf"\definecolor{{dynacolGrid}}{{HTML}}{{{p['grid'][1:]}}}",
        rf"\definecolor{{dynacolAxis}}{{HTML}}{{{p['axis'][1:]}}}",
        "",
        r"\pgfplotscreateplotcyclelist{dynacolLineCycle}{%",
        r"{dynacolBlue, mark=*, solid, line width=1.1pt},%",
        r"{dynacolOrange, mark=square*, densely dashed, line width=1.1pt},%",
        r"{dynacolTeal, mark=triangle*, densely dotted, line width=1.1pt},%",
        r"{dynacolPurple, mark=diamond*, dashdotted, line width=1.1pt},%",
        r"{dynacolGreen, mark=otimes*, loosely dotted, line width=1.1pt},%",
        r"{dynacolRed, mark=pentagon*, loosely dashed, line width=1.1pt}%",
        r"}",
        r"\pgfplotscreateplotcyclelist{dynacolBarCycle}{%",
        r"{draw=dynacolBlue!85!black, fill=dynacolBlue!72},%",
        r"{draw=dynacolOrange!85!black, fill=dynacolOrange!72},%",
        r"{draw=dynacolTeal!85!black, fill=dynacolTeal!70},%",
        r"{draw=dynacolPurple!85!black, fill=dynacolPurple!68},%",
        r"{draw=dynacolGreen!85!black, fill=dynacolGreen!70},%",
        r"{draw=dynacolRed!85!black, fill=dynacolRed!68}%",
        r"}",
        r"\pgfplotsset{",
        r"  clusterBaseAxis/.style={",
        r"    axis background/.style={fill=gray!2},",
        r"    axis line style={draw=dynacolAxis, line width=0.4pt},",
        r"    tick style={draw=dynacolAxis, line width=0.35pt},",
        r"    grid=major,",
        r"    major grid style={line width=.15pt, draw=dynacolGrid!70},",
        r"    tick label style={font=\tiny, text=dynacolSlate},",
        r"    label style={font=\scriptsize, text=black},",
        r"    title style={font=\scriptsize, text=black},",
        r"  },",
        r"  clusterSubfigLine/.style={",
        r"    clusterBaseAxis,",
        r"    width=\linewidth,",
        r"    height=3.6cm,",
        r"    cycle list name=dynacolLineCycle,",
        r"    mark options={solid, scale=0.82, line width=0.4pt},",
        r"    every axis plot/.append style={smooth},",
        r"    legend columns=2,",
        r"    legend image post style={line width=1.0pt},",
        r"    legend style={",
        *legend_below,
        r"    },",
        r"  },",
        r"  clusterSubfigBar/.style={",
        r"    clusterBaseAxis,",
        r"    width=\linewidth,",
        r"    height=3.6cm,",
        r"    ybar,",
        r"    bar width=3.2pt,",
        r"    enlarge x limits=0.08,",
        r"    every axis plot/.append style={fill opacity=0.86},",
        r"    legend columns=2,",
        r"    legend image code/.code={",
        r"      \draw[#1] (0cm,-0.06cm) rectangle (0.20cm,0.09cm);",
        r"    },",
        r"    legend style={",
        *legend_below,
        r"    },",
        r"  },",
        r"  clusterSpanLine/.style={",
        r"    clusterSubfigLine,",
        r"    width=0.92\textwidth,",
        r"    height=5.2cm,",
        r"    every axis plot/.append style={sharp plot},",
        r"    legend columns=4,",
        r"    legend style={",
        r"      font=\scriptsize,",
        r"      at={(0.5,-0.22)},",
        r"      anchor=north,",
        r"      draw=dynacolGrid!80,",
        r"      fill=white,",
        r"      fill opacity=0.95,",
        r"      rounded corners=2pt,",
        r"    },",
        r"  },",
        r"  clusterGridPanel/.style={",
        r"    clusterSubfigBar,",
        r"    width=0.22\textwidth,",
        r"    height=3.4cm,",
        r"    bar width=2.6pt,",
        r"    ylabel style={font=\tiny},",
        r"    x tick label style={rotate=30, anchor=east, font=\tiny},",
        r"  },",
        r"  clusterGridCell/.style={",
        r"    clusterSubfigBar,",
        r"    width=0.46\textwidth,",
        r"    height=3.2cm,",
        r"    bar width=2.8pt,",
        r"    ylabel style={font=\tiny},",
        r"    x tick label style={rotate=28, anchor=east, font=\tiny},",
        r"    legend style={draw=none, fill=none},",
        r"  },",
        r"  clusterSpanBar/.style={",
        r"    clusterSubfigBar,",
        r"    width=0.88\textwidth,",
        r"    height=4.2cm,",
        r"    bar width=4.0pt,",
        r"    xlabel={Runtime stress scenario},",
        r"    x tick label style={rotate=32, anchor=east, font=\tiny},",
        r"  },",
        r"  clusterAblationPanel/.style={",
        r"    clusterSubfigBar,",
        r"    width=\linewidth,",
        r"    height=3.8cm,",
        r"    bar width=4.5pt,",
        r"    x tick label style={rotate=25, anchor=east, font=\tiny},",
        r"    legend style={draw=none, fill=none},",
        r"  },",
        r"  clusterTradeoffAxis/.style={",
        r"    clusterSubfigLine,",
        r"    legend columns=1,",
        r"    legend style={",
        *legend_inside,
        r"    },",
        r"  },",
        r"  dynacolCompactLineAxis/.style={clusterSubfigLine},",
        r"  dynacolCompactBarAxis/.style={clusterSubfigBar},",
        r"  dynacolLineAxis/.style={clusterSpanLine},",
        r"  dynacolBarAxis/.style={clusterSubfigBar},",
        r"}",
        "",
    ])
