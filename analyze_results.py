# /// script
# requires-python = ">=3.11"
# dependencies = ["matplotlib==3.10.8", "numpy==2.2.6", "scipy==1.15.3"]
# ///
# How to run: uv run --python 3.12 analyze_results.py
"""Validate relay exports and generate seed-level publication artifacts."""
import sys
from pathlib import Path

from analysis_data import KEY, PAIRED, Statistic, load_geometry, load_metrics, summaries, write_csv
from analysis_plots import plots, scenario_plot


def findings(paired: list[Statistic], path: Path) -> None:
    """Report measured extrema and limitations without a fixed positive conclusion."""
    lines = ["# Measured findings", "", "All extrema search every condition; differences are R minus comparator.",
             "Intervals describe variation across 20 independent seeds; no multiple-comparison correction.", ""]
    for contrast in ("R-N", "R-A"):
        for metric in PAIRED:
            records = [r for r in paired if r.method == contrast and r.metric == metric]
            scale, unit = (1e6, "Mbit/s") if metric.endswith("bps") else ((.01, "percentage points") if metric.endswith("fraction") else (1, "ms"))
            for name, record in (("Largest", max(records, key=lambda r: r.mean)), ("Smallest", min(records, key=lambda r: r.mean))):
                lines.append(f"- {name} {contrast} {metric} change: {record.mean/scale:.6g} {unit} "
                             f"(95% CI [{record.ci_low/scale:.6g}, {record.ci_high/scale:.6g}]); condition {dict(zip(KEY, record[:8], strict=True))}.")
            lines.append(f"- {contrast} {metric}: {sum(r.ci_low <= 0 <= r.ci_high for r in records)}/{len(records)} intervals include zero.")
    lines += ["", "R/A network-Q05 equality does not imply equal placements, outage, or other outcomes.",
              "A/R similarity is empirical, not evidence that backhaul can always be ignored.",
              "Capacity caps can flatten objectives and induce ties. Boundary clipping changes the error distribution.",
              "Selection and evaluation share a propagation family with independent streams; this is not external model validation.",
              "Outage means rate strictly below 500000 bit/s. CSV rates remain bit/s; fractions remain proportions.",
              "R/A selection_ms each includes shared scoring plus joint R/A selection; do not add them or treat them as independent runs.",
              "Timing intervals describe this machine/run and include JIT/order effects; evaluation_ms is separate.",
              "Finite grid, fixed altitude/resources, finite samples and descriptive multiple comparisons limit generalization.",
              "summary.csv preserves every condition and method; paired.csv preserves every within-condition contrast."]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    """Execute the fixed analysis relative to this file without CLI arguments."""
    if len(sys.argv) != 1:
        raise SystemExit("This script takes no arguments; run uv run --python 3.12 analyze_results.py")
    base = Path(__file__).resolve().parent
    dataset = load_metrics(base / "results/metrics.csv")
    geometry = load_geometry(base / "results/geometry.csv", dataset)
    summary, paired = summaries(dataset)
    directory = base / "figures"
    directory.mkdir(exist_ok=True)
    scenario_plot(geometry, directory)
    write_csv(base / "results/summary.csv", KEY + ["method", "metric", "n", "mean", "ci_low", "ci_high"], summary)
    write_csv(base / "results/paired.csv", KEY + ["contrast", "metric", "n", "mean", "ci_low", "ci_high"], paired)
    plots(summary, paired, directory)
    findings(paired, base / "results/findings.md")
    print(f"Analyzed {len(dataset.groups)} conditions; {len(summary)} summaries; {len(paired)} paired summaries; 5 PNG/PDF figures.")


if __name__ == "__main__":
    main()
