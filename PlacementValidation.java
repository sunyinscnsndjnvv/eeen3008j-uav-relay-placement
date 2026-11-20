import java.util.Arrays;
import java.util.Random;

/** Deterministic tests of scoring, placement and independent-test metrics. */
public final class PlacementValidation {
    private PlacementValidation() {}

    private static void close(double actual, double expected, String contract) {
        if (Math.abs(actual - expected) > 1e-8 * Math.max(1.0, Math.abs(expected))) {
            throw new AssertionError(contract + ": expected " + expected + ", got " + actual);
        }
    }

    private static void quantileUsesEmpiricalInverseCdf() {
        double[] values = {40, 10, 30, 20};
        close(PlacementMethods.quantile(values, 0.05), 10, "low quantile");
        close(PlacementMethods.quantile(values, 0.5), 20, "nearest-rank median");
        close(PlacementMethods.quantile(values, 1.0), 40, "upper endpoint");
        close(values[0], 40, "quantile preserves input");
    }

    private static void robustCapTiesUseFirstCandidateWhileAccessUsesItsMaximum() {
        var model = new UAVRelaySim();
        double[][] users = new double[10][3];
        for (double[] user : users) {
            user[0] = 400;
            user[1] = 250;
        }
        double[][] grid = {{50, 250, 100}, {400, 250, 100}};
        var methods = new PlacementMethods(model, users, grid);
        var pair = methods.select(new PlacementMethods.Scores(new double[] {600_000, 2_000_000}, 0),
                new PlacementMethods.Condition("light", "weak"));
        close(pair.robust().placement().bestIdx, 0, "R capped tie chooses first");
        close(pair.robust().placement().bestObj, 500_000, "R includes backhaul allocation");
        close(pair.access().placement().bestIdx, 1, "A maximizes access");
        close(pair.access().placement().bestObj, 2_000_000, "A objective excludes backhaul");
    }

    private static void optimizedScoringMatchesDirectEndToEndQuantiles() {
        var model = new UAVRelaySim();
        double[][] users = model.generateEstimatedUsers("main", new Random(93));
        double[][] grid = PlacementMethods.grid(3);
        var methods = new PlacementMethods(model, users, grid);
        double[][][] samples = model.sampleUserPositions(users, 100, 40, new Random(94));
        var scores = methods.scoreAccess(samples, "heavy");
        for (String backhaul : new String[] {"weak", "medium", "strong"}) {
            var condition = new PlacementMethods.Condition("heavy", backhaul);
            var pair = methods.select(scores, condition);
            double bestR = -1;
            double bestA = -1;
            int indexR = -1;
            int indexA = -1;
            for (int q = 0; q < grid.length; q++) {
                double[][] endToEnd = new double[10][40];
                double[][] access = new double[10][40];
                for (int s = 0; s < samples.length; s++) {
                    var rate = model.computeRates(grid[q], samples[s], "heavy", backhaul);
                    for (int i = 0; i < 10; i++) {
                        endToEnd[i][s] = rate.rE2E[i];
                        access[i][s] = rate.rAcc[i];
                    }
                }
                double directR = Double.POSITIVE_INFINITY;
                double directA = Double.POSITIVE_INFINITY;
                for (int i = 0; i < 10; i++) {
                    Arrays.sort(endToEnd[i]);
                    Arrays.sort(access[i]);
                    directR = Math.min(directR, endToEnd[i][1]);
                    directA = Math.min(directA, access[i][1]);
                }
                if (directR > bestR) {
                    bestR = directR;
                    indexR = q;
                }
                if (directA > bestA) {
                    bestA = directA;
                    indexA = q;
                }
            }
            close(pair.robust().placement().bestObj, bestR, "direct R objective " + backhaul);
            close(pair.robust().placement().bestIdx, indexR, "direct R position " + backhaul);
            close(pair.access().placement().bestObj, bestA, "direct A objective " + backhaul);
            close(pair.access().placement().bestIdx, indexA, "direct A position " + backhaul);
        }
    }

    private static void zeroErrorMatchesNominalIncludingTieRule() {
        var model = new UAVRelaySim();
        double[][] users = model.generateEstimatedUsers("main", new Random(42));
        var methods = new PlacementMethods(model, users, model.gridPoints);
        var scores = methods.scoreAccess(model.sampleUserPositions(users, 0, 20, new Random(43)), "light");
        for (String backhaul : new String[] {"weak", "medium", "strong"}) {
            var condition = new PlacementMethods.Condition("light", backhaul);
            var nominal = methods.nominal(condition);
            var robust = methods.select(scores, condition).robust();
            close(robust.placement().bestObj, nominal.placement().bestObj, "zero error objective");
            close(robust.placement().bestIdx, nominal.placement().bestIdx, "zero error tie rule");
        }
    }

    /** Known two-user matrix isolates pooled quantiles from worst-user quantiles. */
    private static final class MatrixModel extends UAVRelaySim {
        @Override
        public RateResult computeRates(double[] position, double[][] users, String shadow, String backhaul) {
            var result = new RateResult(2);
            double first = users[0][0] == 0.0 ? 0.0 : 1_000_000.0;
            result.rE2E = new double[] {first, 500_000};
            result.rAcc = new double[] {first, 500_000};
            result.rBh = new double[] {1_000_000, 1_000_000};
            return result;
        }
    }

    private static void metricsSeparateQuantilesAndRespectStrictOutageThreshold() {
        double[][][] samples = new double[20][2][3];
        for (int s = 0; s < 20; s++) samples[s][0][0] = s;
        var metrics = EvaluationMetrics.evaluate(new MatrixModel(), new EvaluationMetrics.TestCase(
                new double[] {0, 0, 100}, samples, new PlacementMethods.Condition("light", "weak")));
        close(metrics.networkQ05(), 500_000, "network Q05 pools 40 observations");
        close(metrics.worstUserQ05(), 0, "worst-user Q05 is a different metric");
        close(metrics.outage(), 1.0 / 40, "equal threshold is not outage");
        close(metrics.accessFraction(), 21.0 / 40, "strict access bottleneck");
        close(metrics.backhaulFraction(), 0, "strict backhaul bottleneck");
        close(metrics.tieFraction(), 19.0 / 40, "exact tie accounted separately");
        close(metrics.mean(), 725_000, "mean of user-scenario outcomes");
    }

    private static void refinedGridIncludesEveryCoarseCandidate() {
        double[][] coarse = PlacementMethods.grid(21);
        double[][] dense = PlacementMethods.grid(41);
        for (int x = 0; x < 21; x++) {
            for (int y = 0; y < 21; y++) {
                for (int axis = 0; axis < 3; axis++) {
                    close(dense[(2 * x) * 41 + 2 * y][axis], coarse[x * 21 + y][axis],
                            "nested grid coordinate");
                }
            }
        }
    }

    public static void main(String[] args) {
        quantileUsesEmpiricalInverseCdf();
        robustCapTiesUseFirstCandidateWhileAccessUsesItsMaximum();
        optimizedScoringMatchesDirectEndToEndQuantiles();
        zeroErrorMatchesNominalIncludingTieRule();
        metricsSeparateQuantilesAndRespectStrictOutageThreshold();
        refinedGridIncludesEveryCoarseCandidate();
        System.out.println("PASS PlacementValidation: 6 contracts (quantiles, R/A, direct oracle, zero error, metrics, grid)");
    }
}
