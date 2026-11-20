"""Typed CSV boundaries and independent-seed statistics."""
import csv
import math
from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Final, NamedTuple, NewType

import numpy as np
from scipy.stats import t

HEADER: Final[list[str]] = list[str](("study,scenario,assumed_epsilon,test_epsilon,shadow,backhaul,"
                 "selection_samples,grid_side,seed,layout_seed,selection_seed,test_seed,"
                 "method,x,y,objective_bps,network_q05_bps,worst_user_q05_bps,outage_fraction,"
                 "access_fraction,backhaul_fraction,tie_fraction,mean_bps,selection_ms,evaluation_ms").split(","))
KEY: Final[list[str]] = HEADER[:8]
GEOMETRY: Final[list[str]] = KEY + ["seed", "kind", "index", "x", "y", "z"]
MEASURES: Final[list[str]] = HEADER[13:]
PAIRED: Final[list[str]] = ["network_q05_bps", "outage_fraction", "worst_user_q05_bps", "mean_bps",
                "backhaul_fraction", "selection_ms", "evaluation_ms"]
METHODS: Final = ("N", "R", "A")
BACKHAULS: Final = ("weak", "medium", "strong")
SHADOWS: Final = ("light", "heavy")
RADII: Final = frozenset((0., 10., 25., 50., 100.))
Seed = NewType("Seed", int)


class AnalysisError(ValueError):
    """A CSV or statistical contract violation with a typed diagnostic."""

    def __init__(self, detail: str, path: Path | None = None) -> None:
        self.detail = detail
        self.path = path
        super().__init__(f"{path}: {detail}" if path else detail)


class Condition(NamedTuple):
    study: str
    scenario: str
    assumed_epsilon: float
    test_epsilon: float
    shadow: str
    backhaul: str
    selection_samples: float
    grid_side: float


@dataclass(frozen=True, slots=True)
class Measurement:
    """One validated method observation; values follow MEASURES."""

    seed: Seed
    streams: tuple[Seed, Seed, Seed]
    method: str
    values: tuple[float, ...]

    def value(self, metric: str) -> float:
        return self.values[MEASURES.index(metric)]


@dataclass(frozen=True, slots=True)
class Dataset:
    """Read-only condition/seed/method index built at the CSV boundary."""

    groups: Mapping[Condition, Mapping[tuple[Seed, str], Measurement]]


class Statistic(NamedTuple):
    study: str
    scenario: str
    assumed_epsilon: float
    test_epsilon: float
    shadow: str
    backhaul: str
    selection_samples: float
    grid_side: float
    method: str
    metric: str
    n: int
    mean: float
    ci_low: float
    ci_high: float

    @property
    def condition(self) -> Condition:
        return Condition(self.study, self.scenario, self.assumed_epsilon, self.test_epsilon,
                         self.shadow, self.backhaul, self.selection_samples, self.grid_side)


@dataclass(frozen=True, slots=True)
class Geometry:
    kind: str
    index: int
    x: float
    y: float
    z: float


def read_csv(path: Path, header: list[str]) -> list[tuple[str, ...]]:
    """Check exact schema and row widths before producing lexical rows."""
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.reader(handle)
        if next(reader, []) != header:
            raise AnalysisError(f"Expected exact header {header}", path)
        rows = [tuple(row) for row in reader]
    if not rows or any(len(row) != len(header) for row in rows):
        raise AnalysisError("Empty data or malformed row", path)
    return rows


def number(raw: str) -> float:
    """Parse a finite numeric measurement."""
    try:
        value = float(raw)
    except ValueError as error:
        raise AnalysisError(f"Invalid numeric value {raw!r}") from error
    if not math.isfinite(value):
        raise AnalysisError(f"Nonfinite value {raw!r}")
    return value


def seed_value(raw: str) -> Seed:
    value = number(raw)
    if not value.is_integer():
        raise AnalysisError(f"Noninteger seed {raw!r}")
    return Seed(int(value))


def condition(row: tuple[str, ...]) -> Condition:
    return Condition(row[0], row[1], number(row[2]), number(row[3]), row[4], row[5],
                     number(row[6]), number(row[7]))


def ci(values: Sequence[float]) -> tuple[float, float, float]:
    """Student-t interval across independent seeds, never Monte Carlo draws."""
    samples = np.asarray(values, dtype=float)
    if len(samples) < 2 or not np.all(np.isfinite(samples)):
        raise AnalysisError("CI requires at least two finite independent seed values")
    mean = float(samples.mean())
    half = float(t.ppf(0.975, len(samples) - 1) * samples.std(ddof=1) / math.sqrt(len(samples)))
    return mean, mean - half, mean + half


def load_metrics(path: Path) -> Dataset:
    """Parse complete, aligned triplets from the frozen experimental design."""
    groups: defaultdict[Condition, dict[tuple[Seed, str], Measurement]] = defaultdict(dict)
    for raw in read_csv(path, HEADER):
        key, seed, method = condition(raw), seed_value(raw[8]), raw[12]
        identity = (seed, method)
        if method not in METHODS or identity in groups[key]:
            raise AnalysisError(f"Unknown method or duplicate seed/method: {key}, {identity}")
        values = tuple(number(value) for value in raw[13:])
        row = Measurement(seed, (seed_value(raw[9]), seed_value(raw[10]), seed_value(raw[11])), method, values)
        for field, value in zip(MEASURES, values, strict=True):
            if field not in ("x", "y") and (value < 0 or (field.endswith("fraction") and value > 1)):
                raise AnalysisError(f"Invalid measurement {field}: {value}")
        if abs(sum(row.value(f"{part}_fraction") for part in ("access", "backhaul", "tie")) - 1) > 1e-6:
            raise AnalysisError("Access/backhaul/tie fractions must sum to one")
        groups[key][identity] = row
    for key, rows in groups.items():
        seeds = {seed for seed, _ in rows}
        if len(seeds) != 20 or set(rows) != {(seed, method) for seed in seeds for method in METHODS}:
            raise AnalysisError(f"Expected 20 complete seed triplets: {key}")
        if any(len({rows[seed, method].streams for method in METHODS}) != 1 for seed in seeds):
            raise AnalysisError(f"Unaligned random streams: {key}")
    counts = {study: sum(k.study == study for k in groups) for study in {k.study for k in groups}}
    if counts != {"core": 30, "mismatch": 3, "uniform": 3, "grid": 1, "stability": 3}:
        raise AnalysisError(f"Incomplete study inventory: {counts}")
    expected = {(error, shadow, backhaul) for error in RADII for shadow in SHADOWS for backhaul in BACKHAULS}
    actual = {(k.test_epsilon, k.shadow, k.backhaul) for k in groups if k.study == "core"}
    if actual != expected:
        raise AnalysisError("Core requires frozen radii {0,10,25,50,100} crossed with blockage and backhaul")
    return Dataset(MappingProxyType({key: MappingProxyType(rows) for key, rows in groups.items()}))


def summaries(dataset: Dataset) -> tuple[list[Statistic], list[Statistic]]:
    """Summarize methods and paired seed differences without pseudoreplication."""
    summary: list[Statistic] = []
    paired: list[Statistic] = []
    for key, rows in sorted(dataset.groups.items()):
        seeds = sorted({seed for seed, _ in rows})
        for method in METHODS:
            for metric in MEASURES:
                summary.append(Statistic(*key, method, metric, len(seeds), *ci([rows[s, method].value(metric) for s in seeds])))
        for other in ("N", "A"):
            for metric in PAIRED:
                delta = [rows[s, "R"].value(metric) - rows[s, other].value(metric) for s in seeds]
                paired.append(Statistic(*key, f"R-{other}", metric, len(seeds), *ci(delta)))
    return summary, paired


def load_geometry(path: Path, dataset: Dataset) -> list[Geometry]:
    """Parse the sole representative geometry, retaining true coordinates."""
    reference = Condition("core", "main", 50., 50., "heavy", "medium", 200., 21.)
    if reference not in dataset.groups:
        raise AnalysisError("Missing representative core/main/50/heavy/medium condition")
    rows: list[Geometry] = []
    seen: set[tuple[str, int]] = set()
    for raw in read_csv(path, GEOMETRY):
        identity = (raw[9], int(raw[10]))
        if condition(raw) != reference or seed_value(raw[8]) != 2026111600 or identity in seen:
            raise AnalysisError("Geometry must contain unique representative condition entries")
        seen.add(identity)
        rows.append(Geometry(*identity, number(raw[11]), number(raw[12]), number(raw[13])))
    counts = {kind: sum(row.kind == kind for row in rows) for kind in ("BS", "user", "candidate", *METHODS)}
    if counts != {"BS": 1, "user": 10, "candidate": 441, "N": 1, "R": 1, "A": 1} or len(rows) != sum(counts.values()):
        raise AnalysisError(f"Incomplete geometry: {counts}")
    return rows


def write_csv(path: Path, header: list[str], rows: Sequence[Statistic]) -> None:
    """Keep original units and every condition in machine-readable summaries."""
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(rows)
