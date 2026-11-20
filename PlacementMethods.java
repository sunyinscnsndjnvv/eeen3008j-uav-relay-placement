import java.util.Arrays;

/** Sample-based placement. All physical rates come from the authoritative B model. */
public final class PlacementMethods {
    public record Condition(String shadow, String backhaul) {}
    public record Scores(double[] worstAccessQ05, long elapsedNanos) {}
    public record Selection(UAVRelaySim.PlacementResult placement, double elapsedMillis) {}
    public record RobustPair(Selection robust, Selection access) {}

    private final UAVRelaySim model;
    private final double[][] estimated;
    private final double[][] candidates;

    public PlacementMethods(UAVRelaySim model, double[][] estimated, double[][] candidates) {
        this.model = model;
        this.estimated = estimated;
        this.candidates = candidates;
    }

    /** Empirical inverse CDF: sorted[ceil(p*n)-1], with no interpolation. */
    public static double quantile(double[] values, double probability) {
        if (values.length == 0 || probability <= 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("A nonempty sample and 0 < probability <= 1 are required");
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(probability * sorted.length) - 1];
    }

    public Selection nominal(Condition condition) {
        long start = System.nanoTime();
        var placement = model.selectNominalPlacement(
                estimated, condition.shadow(), condition.backhaul(), candidates);
        return new Selection(placement, (System.nanoTime() - start) / 1e6);
    }

    /**
     * Backhaul-independent access scoring is reused across all three backhaul regimes.
     * The fixed per-candidate backhaul cap commutes with a nearest-rank quantile:
     * Q_p(min(X,b)) = min(Q_p(X),b). Thus this is the exact sampled R objective.
     */
    public Scores scoreAccess(double[][][] selectionSamples, String shadow) {
        long start = System.nanoTime();
        int users = estimated.length;
        double[] scores = new double[candidates.length];
        double[][] accessSamples = new double[users][selectionSamples.length];
        for (int q = 0; q < candidates.length; q++) {
            for (int s = 0; s < selectionSamples.length; s++) {
                var rates = model.computeRates(candidates[q], selectionSamples[s], shadow, "strong");
                for (int i = 0; i < users; i++) {
                    accessSamples[i][s] = rates.rAcc[i];
                }
            }
            double worst = Double.POSITIVE_INFINITY;
            for (double[] sample : accessSamples) {
                worst = Math.min(worst, quantile(sample, 0.05));
            }
            scores[q] = worst;
        }
        return new Scores(scores, System.nanoTime() - start);
    }

    public RobustPair select(Scores scores, Condition condition) {
        long start = System.nanoTime();
        int robustIndex = 0;
        int accessIndex = 0;
        double robustObjective = -1.0;
        double accessObjective = -1.0;
        for (int q = 0; q < candidates.length; q++) {
            var rates = model.computeRates(candidates[q], estimated, condition.shadow(), condition.backhaul());
            double accessScore = scores.worstAccessQ05()[q];
            double robustScore = Math.min(accessScore, rates.rBh[0]);
            // Strict comparison preserves the first (smallest x, then smallest y) index.
            if (robustScore > robustObjective) {
                robustObjective = robustScore;
                robustIndex = q;
            }
            if (accessScore > accessObjective) {
                accessObjective = accessScore;
                accessIndex = q;
            }
        }
        double sharedMillis = (scores.elapsedNanos() + System.nanoTime() - start) / 1e6;
        return new RobustPair(
                new Selection(new UAVRelaySim.PlacementResult(
                        robustIndex, candidates[robustIndex], robustObjective), sharedMillis),
                new Selection(new UAVRelaySim.PlacementResult(
                        accessIndex, candidates[accessIndex], accessObjective), sharedMillis));
    }

    public static double[][] grid(int side) {
        if (side < 2) throw new IllegalArgumentException("Grid side must be at least two");
        double[][] grid = new double[side * side][3];
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) {
                grid[x * side + y] = new double[] {500.0 * x / (side - 1), 500.0 * y / (side - 1), 100.0};
            }
        }
        return grid;
    }
}
