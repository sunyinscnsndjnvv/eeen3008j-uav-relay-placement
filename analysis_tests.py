# /// script
# requires-python = ">=3.11"
# dependencies = ["matplotlib==3.10.8", "numpy==2.2.6", "scipy==1.15.3", "pytest==8.4.2"]
# ///
# How to run: uv run --python 3.12 analysis_tests.py -q
"""Behavioral statistics, input contracts, numeric ordering and CLI integration."""
import csv
import math
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Final

import pytest

import analysis_data as data
from analysis_plots import supplemental_order

BASE: Final = Path(__file__).resolve().parent
CHECK: Final = BASE / "build/analysis-check"
Cell = str | int | float


def fixture_rows() -> list[tuple[Cell, ...]]:
    """Generate the frozen design with correlated, nonconstant method changes."""
    conditions = [
        ("core", "main", error, error, shadow, backhaul, 200, 21)
        for error in (0, 10, 25, 50, 100)
        for shadow in data.SHADOWS for backhaul in data.BACKHAULS
    ]
    conditions += [("mismatch", "main", 50, error, "heavy", "medium", 200, 21) for error in (25, 50, 100)]
    conditions += [("uniform", "uniform", 50, 50, shadow, backhaul, 200, 21)
                   for shadow, backhaul in (("light", "weak"), ("heavy", "medium"), ("heavy", "strong"))]
    conditions += [("grid", "main", 50, 50, "heavy", "medium", 200, 41)]
    conditions += [("stability", "main", 50, 50, "heavy", "medium", count, 21) for count in (100, 400, 800)]
    rows: list[tuple[Cell, ...]] = []
    for key in conditions:
        for index in range(20):
            seed = 2026111600 + index
            for method in data.METHODS:
                rate = 550000 + index * 1000 + {"N": 0, "R": 200 + 20 * index, "A": 200 + 20 * index}[method]
                rows.append((*key, seed, seed + 1, seed + 2, seed + 3, method, 250, 250,
                             rate, rate, rate - 10000, .2, .3, .6, .1, rate + 100000, 20, 4))
    return rows


def geometry_rows() -> list[tuple[Cell, ...]]:
    """Provide estimated users and deliberately coincident method placements."""
    key = ("core", "main", 50, 50, "heavy", "medium", 200, 21, 2026111600)
    positions = [("candidate", i * 21 + j, i * 25, j * 25, 100) for i in range(21) for j in range(21)]
    positions += [("BS", 0, 50, 250, 25)]
    positions += [("user", i, 300 + i * 10, 150 + i * 20, 0) for i in range(10)]
    positions += [(method, 0, 250, 250, 100) for method in data.METHODS]
    return [(*key, *position) for position in positions]


def write_rows(path: Path, header: list[str], rows: list[tuple[Cell, ...]]) -> None:
    """Write lexical fixtures through the same external CSV boundary."""
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(rows)


@pytest.fixture
def metrics(tmp_path: Path) -> Path:
    path = tmp_path / "metrics.csv"
    write_rows(path, data.HEADER, fixture_rows())
    return path


def test_student_t_interval_uses_independent_sample_standard_error() -> None:
    result = data.ci(range(20))
    half = 2.093024054408263 * math.sqrt(35 / 20)
    assert result == pytest.approx((9.5, 9.5 - half, 9.5 + half))


def test_correlated_nonconstant_pairs_use_seed_difference_variance(metrics: Path) -> None:
    _, paired = data.summaries(data.load_metrics(metrics))
    changes = [row for row in paired if row.method == "R-N" and row.metric == "network_q05_bps"]
    half = 20 * 2.093024054408263 * math.sqrt(35 / 20)
    assert tuple(value for row in changes for value in (row.n, row.mean, row.ci_low, row.ci_high)) == pytest.approx(
        (20, 390, 390 - half, 390 + half) * 40)


@pytest.mark.parametrize("fault", ["duplicate", "missing_method", "missing_condition", "unaligned", "nonfinite", "fraction", "radius"])
def test_malformed_design_fails_at_csv_boundary(tmp_path: Path, fault: str) -> None:
    rows = fixture_rows()
    variants = {
        "duplicate": rows + [rows[0]], "missing_method": rows[1:], "missing_condition": rows[60:],
        "unaligned": [(row[:11] + (0,) + row[12:]) if i == 0 else row for i, row in enumerate(rows)],
        "nonfinite": [(row[:16] + ("nan",) + row[17:]) if i == 0 else row for i, row in enumerate(rows)],
        "fraction": [(row[:21] + (.5,) + row[22:]) if i == 0 else row for i, row in enumerate(rows)],
        "radius": [(row[:2] + (75, 75) + row[4:]) if row[0] == "core" and row[3] == 10 else row for row in rows],
    }
    path = tmp_path / "metrics.csv"
    write_rows(path, data.HEADER, variants[fault])
    with pytest.raises(data.AnalysisError):
        data.load_metrics(path)


def test_supplemental_stability_axis_is_numeric(metrics: Path) -> None:
    summary, _ = data.summaries(data.load_metrics(metrics))
    records = [r for r in summary if r.study == "stability" or (
        r.study == "core" and r.test_epsilon == 50 and r.shadow == "heavy" and r.backhaul == "medium")]
    assert [key.selection_samples for key in supplemental_order(records, 6)] == [100, 200, 400, 800]


def test_full_cli_writes_every_condition_and_paired_interval(tmp_path: Path) -> None:
    sandbox = tmp_path / "synthetic"
    (sandbox / "results").mkdir(parents=True)
    for name in ("analyze_results.py", "analysis_data.py", "analysis_plots.py"):
        shutil.copy2(BASE / name, sandbox / name)
    write_rows(sandbox / "results/metrics.csv", data.HEADER, fixture_rows())
    write_rows(sandbox / "results/geometry.csv", data.GEOMETRY, geometry_rows())
    result = subprocess.run([sys.executable, str(sandbox / "analyze_results.py")], cwd=tmp_path,
                            capture_output=True, text=True, check=False)
    CHECK.mkdir(parents=True, exist_ok=True)
    (CHECK / "synthetic-execution.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    assert result.returncode == 0, result.stderr
    with (sandbox / "results/paired.csv").open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    assert len(rows) == 560
    assert {row["contrast"] for row in rows} == {"R-N", "R-A"}
    assert {row["study"] for row in rows} == {"core", "mismatch", "uniform", "grid", "stability"}
    for suffix in ("png", "pdf"):
        assert {path.stem for path in (sandbox / "figures").glob(f"*.{suffix}")} == {
            "figure1_geometry", "figure2_error_sensitivity", "figure3a_paired_pressure",
            "figure3b_bottlenecks", "figure4_supplemental"}


def test_cli_rejects_arguments(tmp_path: Path) -> None:
    result = subprocess.run([sys.executable, str(BASE / "analyze_results.py"), "--help"], cwd=tmp_path,
                            capture_output=True, text=True, check=False)
    assert result.returncode != 0


if __name__ == "__main__":
    CHECK.mkdir(parents=True, exist_ok=True)
    raise SystemExit(pytest.main([str(Path(__file__).resolve()), "--junitxml", str(CHECK / "pytest.xml"), *sys.argv[1:]]))
