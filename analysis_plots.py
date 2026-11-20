"""Publication figures from validated seed summaries, with overlap visible."""
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Final

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.axes import Axes
from matplotlib.figure import Figure
from matplotlib.patches import Circle

from analysis_data import BACKHAULS, METHODS, SHADOWS, Condition, Geometry, Statistic

COLORS: Final = {"N": "#0072B2", "R": "#D55E00", "A": "#009E73", "R-N": "#0072B2", "R-A": "#D55E00"}
plt.switch_backend("Agg")


def save(fig: Figure, directory: Path, name: str) -> None:
    """Export one layout to 300 dpi raster and vector PDF."""
    for suffix in ("png", "pdf"):
        fig.savefig(directory / f"{name}.{suffix}", dpi=300)
    plt.close(fig)


def series(ax: Axes, records: Sequence[Statistic], metric: str,
           labels: Sequence[str] = METHODS, scale: float = 1,
           positions: Mapping[Condition, int] | None = None) -> None:
    """Draw seed intervals with nested open markers for coincident series."""
    for index, label in enumerate(labels):
        selected = sorted((r for r in records if r.method == label and r.metric == metric),
                          key=lambda r: positions[r.condition] if positions is not None else r.test_epsilon)
        x = [positions[r.condition] if positions is not None else r.test_epsilon for r in selected]
        means = np.array([r.mean for r in selected]) / scale
        errors = np.array([[r.mean - r.ci_low, r.ci_high - r.mean] for r in selected]).T / scale
        ax.errorbar(x, means, yerr=errors, marker=("s", "o", "^")[index], ms=7 - 2 * index,
                    mfc="none", linestyle=("--", "-", ":")[index], capsize=2, label=label, color=COLORS[label])
    ax.grid(alpha=0.22)


def scenario_plot(rows: Sequence[Geometry], directory: Path) -> None:
    """Show the true placements with offset labels for coincidence."""
    fig, ax = plt.subplots(figsize=(7, 6), layout="constrained")
    for kind, marker, color, size, label in (("candidate", ".", "#cccccc", 8, "Candidate grid"),
                                           ("user", "o", "#444444", 65, "Estimated users"),
                                           ("BS", "*", "#000000", 65, "BS")):
        selected = [row for row in rows if row.kind == kind]
        ax.scatter([row.x for row in selected], [row.y for row in selected], marker=marker, color=color, s=size, label=label)
    for row in (row for row in rows if row.kind == "user"):
        ax.add_patch(Circle((row.x, row.y), 50, fill=False, color="#888888", alpha=0.5, lw=0.7))
    for method, offset, marker in zip(METHODS, ((14, 18), (-35, -35), (-30, 12)), ("s", "^", "D"), strict=True):
        row = next(row for row in rows if row.kind == method)
        ax.scatter(row.x, row.y, marker=marker, s=110, facecolors="none", edgecolors=COLORS[method], lw=2)
        ax.annotate(method, (row.x, row.y), xytext=offset, textcoords="offset points", color=COLORS[method],
                    weight="bold", arrowprops={"arrowstyle": "-", "color": COLORS[method]})
    ax.set(xlim=(-55, 555), ylim=(-55, 555), aspect="equal", xlabel="x (m)", ylabel="y (m)",
           title="Representative geometry: 50 m error disks\nHeavy blockage, medium backhaul; UAV height 100 m")
    ax.legend(loc="upper left", fontsize=8)
    save(fig, directory, "figure1_geometry")


def supplemental_order(records: Sequence[Statistic], field: int) -> list[Condition]:
    """Sort supplemental conditions by the numeric experimental axis."""
    return sorted({r.condition for r in records}, key=lambda key: (
        {5: key.backhaul}.get(field, ""),
        {3: key.test_epsilon, 6: key.selection_samples, 7: key.grid_side}.get(field, 0)))


def plots(summary: list[Statistic], paired: list[Statistic], directory: Path) -> None:
    """Render every planned panel, retaining all supplemental conditions."""
    plt.rcParams.update({"font.size": 9, "axes.spines.top": False, "axes.spines.right": False, "pdf.fonttype": 42})
    core = [r for r in summary if r.study == "core"]
    fig, axes = plt.subplots(2, 3, figsize=(11, 6.5), layout="constrained", sharex=True)
    for column, backhaul in enumerate(BACKHAULS):
        selected = [r for r in core if r.shadow == "heavy" and r.backhaul == backhaul]
        for row, (metric, scale, ylabel) in enumerate((("network_q05_bps", 1e6, "Network Q05 (Mbit/s)"),
                                                     ("outage_fraction", .01, "Outage (%)"))):
            series(axes[row, column], selected, metric, scale=scale)
            axes[row, column].set(title=f"{backhaul.capitalize()} backhaul" if row == 0 else "",
                                  ylabel=ylabel if column == 0 else "", xlabel="Test error radius (m)" if row else "")
    axes[0, 0].legend()
    fig.suptitle("Heavy blockage: seed means and 95% t intervals\nOverlapping curves use nested open markers")
    save(fig, directory, "figure2_error_sensitivity")
    fig, axes = plt.subplots(2, 3, figsize=(11, 6.5), layout="constrained", sharex=True)
    for row, shadow in enumerate(SHADOWS):
        for column, backhaul in enumerate(BACKHAULS):
            selected = [r for r in paired if r.study == "core" and r.shadow == shadow and r.backhaul == backhaul]
            series(axes[row, column], selected, "network_q05_bps", ("R-N", "R-A"), scale=1e6)
            axes[row, column].axhline(0, color="#555555", lw=.7)
            axes[row, column].set(title=f"{shadow.capitalize()}, {backhaul}",
                                  ylabel="Paired Q05 change (Mbit/s)" if column == 0 else "",
                                  xlabel="Test error radius (m)" if row else "")
    axes[0, 0].legend()
    fig.suptitle("All core conditions: paired differences with 95% t intervals\nOverlapping curves use nested open markers")
    save(fig, directory, "figure3a_paired_pressure")
    fig, axes = plt.subplots(2, 3, figsize=(11, 6.5), layout="constrained", sharey=True)
    for row, shadow in enumerate(SHADOWS):
        for column, backhaul in enumerate(BACKHAULS):
            ax, bottom = axes[row, column], np.zeros(3)
            selected = [r for r in core if r.test_epsilon == 50 and r.shadow == shadow and r.backhaul == backhaul]
            for part, color in (("access", "#0072B2"), ("backhaul", "#E69F00"), ("tie", "#999999")):
                records = [next(r for r in selected if r.method == m and r.metric == f"{part}_fraction") for m in METHODS]
                means = np.array([r.mean for r in records]) * 100
                errors = np.array([r.ci_high - r.mean for r in records]) * 100
                ax.bar(METHODS, means, bottom=bottom, color=color, label=part, yerr=errors, capsize=3)
                bottom += means
            ax.set(title=f"{shadow.capitalize()}, {backhaul}", ylabel="Bottleneck share (%)" if column == 0 else "")
    axes[0, 0].legend(fontsize=8)
    fig.suptitle("50 m error: stacked seed means; segment whiskers show 95% t half-widths")
    save(fig, directory, "figure3b_bottlenecks")
    fig, axes = plt.subplots(2, 2, figsize=(11, 7), layout="constrained")
    for ax, study, field in zip(axes.flat, ("mismatch", "uniform", "grid", "stability"), (3, 5, 7, 6), strict=True):
        selected = [r for r in summary if r.study == study]
        if study in ("grid", "stability"):
            selected += [r for r in core if r.test_epsilon == 50 and r.shadow == "heavy" and r.backhaul == "medium"]
        keys = supplemental_order(selected, field)
        series(ax, selected, "network_q05_bps", scale=1e6, positions={key: index for index, key in enumerate(keys)})
        labels = [f"{k.backhaul}/{k.shadow}" if study == "uniform" else f"{k[field]:g}" for k in keys]
        ax.set(xticks=range(len(keys)), xticklabels=labels, ylabel="Network Q05 (Mbit/s)",
               title=study.capitalize(), xlabel={"mismatch": "Actual radius (m), assumed 50 m", "uniform": "Backhaul / blockage",
                                                 "grid": "Grid side", "stability": "Selection samples"}[study])
    axes[0, 0].legend()
    save(fig, directory, "figure4_supplemental")
