# UAV relay placement under location uncertainty

Simulation and evaluation code for the EEEN3008J study comparing nominal (N), sample-based (R), and access-only (A) relay placement. The four-way follow-up crosses N/R with grid-order and access-margin tie rules. Data are synthetic. Report files, course documents, and student identifiers are excluded.

## Reproduce the main result

Run in PowerShell at repository root. Java 21+ is required for the complete simulation; Python 3.11+, NumPy, Matplotlib 3.8+ and SciPy are used by the analysis tools. The supplementary audit plot also needs ReportLab.

```powershell
New-Item -ItemType Directory -Force build/classes | Out-Null
javac -encoding UTF-8 -d build/classes (Get-ChildItem *.java).Name margin_study/MarginSweep.java
java -cp build/classes ModelValidation
java -cp build/classes PlacementValidation
java -cp build/classes AuditResults
java -Xmx1500m -cp build/classes MarginSweep margin_study/rebuilt
python margin_study/figures.py margin_study/rebuilt results margin_study/rebuilt_figures
```

For a quick check with the archived rows (no Java recomputation), run `python margin_study/figures.py margin_study results build/figures`. This regenerates `margin_study/contrasts.csv` and the four figures. For a self-contained check of the site's tie rule and the reported interval using Python's standard library, run `python appendix_check.py`.

`RunExperiments smoke` (after `java -cp build/classes`) generates a short run in `build/smoke-results/`. For the complete original design, rename `results/` to `results_archived/` in a fresh disposable checkout, then run `java -Xmx1500m -cp build/classes RunExperiments all`. The runner refuses to overwrite an existing results file. Run `python analyze_results.py` to generate summaries and the original figures. The repository runner fingerprints this README in place of the course-only parameter workbook and protocol; the original simulation and metric calculations are unchanged.

## Data and interpretation

- `results/metrics.csv`: 2,400 original and sensitivity records; selection time and evaluation time depend on hardware.
- `margin_study/margin_sweep.csv`: 4,800 records (30 conditions, 40 layouts, two information sets, two tie rules). `example_map.csv`: the 441 candidates for the illustrated layout.
- `margin_study/contrasts.csv`: 720 paired comparisons. For the original cohort at 100 m error, heavy blockage, weak backhaul, nominal grid-order minus nominal access-margin outage is **9.4185 percentage points** (95% paired interval 7.92037–10.91663). Rates are raw bit/s; outages in per-layout CSVs are fractions. An outage is strictly below 500,000 bit/s.
