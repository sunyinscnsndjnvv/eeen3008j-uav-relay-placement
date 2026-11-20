Access margin experiment: reproduction guide

This folder extends the original N/R/A experiment with a 2 x 2 comparison (N/R x grid order/access margin). The radio model and held-out test evaluator are the Java files at archive root. Threshold: strictly below 500,000 bit/s; equality counts as service. Records in this folder supplement, rather than replace, results/metrics.csv.

From the extracted archive root, using a recent JDK (17+), Python 3, NumPy and matplotlib:
  mkdir classes
  javac -encoding UTF-8 -d classes UAVRelaySim.java PlacementMethods.java EvaluationMetrics.java margin_study/MarginSweep.java
  java -cp classes MarginSweep margin_study/rebuilt
  python margin_study/figures.py margin_study/rebuilt results margin_study/rebuilt_figures

MarginSweep produces margin_sweep.csv (4,800 layout-method rows) and example_map.csv (441 sites). The Python script writes contrasts.csv (720 comparisons) and four vector figures. Compare the rebuilt CSV files with the included CSVs. Assertions check the original 1,200 grid-order controls against the archived model, and that every one of the 2,400 within-method comparisons preserves its primary score. Use the existing CSV files as input to the Python script to redraw the figures without rerunning Java. Matplotlib 3.8 or later is recommended for outside-legend positioning. Layout seed identifies the replicate, not each individual user or test draw.

Example: in contrasts.csv filter cohort=original, error_m=100, shadow=heavy, backhaul=weak, metric=outage, left=N-grid, right=N-margin. The reduction is 9.4185 percentage points [7.92037, 10.91663]. All CSV contrast signs are left minus right. For a sampled-minus-nominal comparison with margin ties, negate the saved N-minus-R estimate and reverse the confidence limits. The original archive also provides the course parameter sheet and access-only comparator.
