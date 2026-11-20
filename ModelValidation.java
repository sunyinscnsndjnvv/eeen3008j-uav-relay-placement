import java.util.Arrays;
import java.util.Random;

/** Independent public-contract checks; no reflection or production test hooks. */
public final class ModelValidation {
    private static int checks;

    private ModelValidation() {}

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void close(double actual, double expected, String message) {
        require(Double.isFinite(actual)
                && Math.abs(actual - expected) <= 1e-7 + Math.abs(expected) * 1e-11,
                message + ": expected " + expected + ", got " + actual);
    }

    // Independent linear-domain link budget: 20 dBm = .1 W, 30 dBm = 1 W,
    // -174 dBm/Hz = 10^-20.4 W/Hz; 7 dB NF multiplies noise by 10^.7.
    // 40 dB loss at 1 m gives gain 1e-4; metres are raised directly to -2.2.
    private static double capacity(double distance, double powerW, double lossDb) {
        double noiseW = Math.pow(10.0, -20.4) * 10_000_000 * Math.pow(10.0, 0.7);
        double gain = 0.0001 * Math.pow(Math.max(1.0, distance), -2.2)
                / Math.pow(10.0, lossDb / 10.0);
        return 10_000_000 * Math.log1p(powerW * gain / noiseW) / Math.log(2.0);
    }

    private static double separation(double[] a, double[] b) {
        return Math.hypot(Math.hypot(a[0] - b[0], a[1] - b[1]), a[2] - b[2]);
    }

    private static void numericOracle(UAVRelaySim model) {
        double[][] users = {{50, 250, 0}, {350, 250, 0}, {50, 450, 0}, {100, 50, 0}};
        double[][] placements = {{50, 250, 100}, {500, 500, 100}};
        String[] shadows = {"none", "light", "heavy"};
        double[] losses = {0, 10, 25};
        String[] backhauls = {"weak", "medium", "strong"};
        double[] caps = {10_000_000, 35_000_000, 100_000_000};
        for (double[] q : placements) {
            for (int s = 0; s < shadows.length; s++) {
                for (int b = 0; b < backhauls.length; b++) {
                    for (double tau : new double[]{0, 0.2, 0.5, 1}) {
                        UAVRelaySim.RateResult result =
                                model.computeRates(q, users, shadows[s], backhauls[b], tau);
                        double expectedB = Math.min(caps[b],
                                capacity(separation(q, new double[]{50, 250, 25}), 1, 0));
                        close(result.cB, expectedB, "oracle backhaul bit/s");
                        for (int i = 0; i < users.length; i++) {
                            double expectedA = capacity(separation(q, users[i]), 0.1,
                                    i < 3 ? losses[s] : 0);
                            close(result.cA[i], expectedA, "oracle access bit/s user " + i);
                            close(result.rAcc[i], tau * expectedA / users.length,
                                    "oracle access time and user divisor");
                            close(result.rBh[i], (1 - tau) * expectedB / users.length,
                                    "oracle backhaul time and user divisor");
                            close(result.rE2E[i], Math.min(tau * expectedA,
                                    (1 - tau) * expectedB) / users.length, "oracle delivered bit/s");
                            if (tau == 0 || tau == 1) {
                                require(result.rE2E[i] == 0, "tau endpoint must deliver exact zero");
                            }
                        }
                    }
                }
            }
        }
        System.out.println("PASS independent linear link-budget oracle, units and tau endpoints");
    }

    private static void allocationAndBlockage(UAVRelaySim model) {
        double[][] users = new double[10][3];
        for (double[] user : users) System.arraycopy(new double[]{250, 250, 0}, 0, user, 0, 3);
        for (double[] q : new double[][]{{0, 0, 100}, {250, 250, 100}, {500, 500, 100}}) {
            for (String shadow : new String[]{"none", "light", "heavy"}) {
                UAVRelaySim.RateResult weak = model.computeRates(q, users, shadow, "weak");
                UAVRelaySim.RateResult medium = model.computeRates(q, users, shadow, "medium");
                UAVRelaySim.RateResult strong = model.computeRates(q, users, shadow, "strong");
                for (int i = 0; i < users.length; i++) {
                    require(weak.rE2E[i] <= medium.rE2E[i]
                            && medium.rE2E[i] <= strong.rE2E[i],
                            "weak <= medium <= strong user " + i);
                }
                for (UAVRelaySim.RateResult r : new UAVRelaySim.RateResult[]{weak, medium, strong}) {
                    close(Arrays.stream(r.rBh).sum(), 0.5 * r.cB, "sum backhaul allocations");
                    require(Arrays.stream(r.rE2E).sum() <= 0.5 * r.cB + 1e-7,
                            "delivered sum exceeds allocated backhaul");
                }
            }
        }
        double[] q = {250, 250, 100};
        UAVRelaySim.RateResult none = model.computeRates(q, users, "none", "strong");
        UAVRelaySim.RateResult light = model.computeRates(q, users, "light", "strong");
        UAVRelaySim.RateResult heavy = model.computeRates(q, users, "heavy", "strong");
        for (int i = 0; i < users.length; i++) {
            if (i < 3) {
                require(heavy.cA[i] < light.cA[i] && light.cA[i] < none.cA[i],
                        "blocked indices 0, 1, 2 must lose access capacity");
            } else {
                close(light.cA[i], none.cA[i], "unblocked light user " + i);
                close(heavy.cA[i], none.cA[i], "unblocked heavy user " + i);
            }
        }
        System.out.println("PASS allocation conservation, all backhaul levels and blocked indices");
    }

    private static void diskSampling(UAVRelaySim model) {
        double[][] centre = {{250, 250, 0}};
        double[][][] samples = model.sampleUserPositions(centre, 100, 12_000, new Random(8271));
        double[][][] repeat = model.sampleUserPositions(centre, 100, 12_000, new Random(8271));
        require(Arrays.deepEquals(samples, repeat), "identical seeds must reproduce samples");
        require(!Arrays.deepEquals(samples,
                model.sampleUserPositions(centre, 100, 12_000, new Random(8272))),
                "different seeds must change samples");
        double sumSquaredRadius = 0;
        double sumX = 0;
        double sumY = 0;
        int[] quadrants = new int[4];
        for (double[][] sample : samples) {
            double dx = sample[0][0] - 250;
            double dy = sample[0][1] - 250;
            double normalizedSquaredRadius = (dx * dx + dy * dy) / 10_000;
            require(normalizedSquaredRadius <= 1 + 1e-12 && sample[0][2] == 0,
                    "unclipped disk support and ground height");
            sumSquaredRadius += normalizedSquaredRadius;
            sumX += dx;
            sumY += dy;
            quadrants[(dx < 0 ? 2 : 0) + (dy < 0 ? 1 : 0)]++;
        }
        require(Math.abs(sumSquaredRadius / samples.length - 0.5) < 0.015,
                "uniform disk area requires E[r^2/epsilon^2]=1/2, not 1/3");
        require(Math.abs(sumX / samples.length) < 2 && Math.abs(sumY / samples.length) < 2,
                "disk angular mean must remain near centre");
        for (int count : quadrants) require(Math.abs(count - 3000) < 180, "angular quadrant balance");
        Random scripted = new Random(0) {
            private static final long serialVersionUID = 1L;
            private final double[] draws = {0.81, 0, 0.81, 0.5, 0.81, 0.25, 0.81, 0.75};
            private int index;
            @Override public double nextDouble() { return draws[index++]; }
        };
        double[][] edges = {{499, 250, 0}, {1, 250, 0}, {250, 499, 0}, {250, 1, 0}};
        double[][] clipped = model.sampleUserPositions(edges, 10, 1, scripted)[0];
        double[][] expected = {{500, 250, 0}, {0, 250, 0}, {250, 500, 0}, {250, 0, 0}};
        for (int i = 0; i < edges.length; i++) {
            for (int c = 0; c < 3; c++) close(clipped[i][c], expected[i][c], "boundary clamp");
        }
        System.out.printf("PASS disk sampling and clipping; mean normalized r^2=%.6f; quadrants=%s%n",
                sumSquaredRadius / samples.length, Arrays.toString(quadrants));
    }

    private static void zeroErrorAndNominalTie(UAVRelaySim model) {
        double[][] users = model.generateEstimatedUsers("main", new Random(42));
        Random rng = new Random(904);
        double[][][] zero = model.sampleUserPositions(users, 0, 20, rng);
        for (double[][] sample : zero) {
            require(Arrays.deepEquals(sample, users), "zero epsilon must preserve every coordinate");
            require(sample != users && sample[0] != users[0], "samples must own coordinate arrays");
            UAVRelaySim.PlacementResult nominal = model.selectNominalPlacement(users, "heavy", "medium");
            UAVRelaySim.PlacementResult sampled = model.selectNominalPlacement(sample, "heavy", "medium");
            close(sampled.bestObj, nominal.bestObj, "zero-error nominal objective");
            require(sampled.bestIdx == nominal.bestIdx, "zero-error deterministic nominal location");
        }
        require(rng.nextDouble() == new Random(904).nextDouble(), "zero epsilon consumes no RNG draws");
        double[][] coLocated = new double[10][3];
        for (double[] user : coLocated) { user[0] = 250; user[1] = 250; }
        for (double[] q : model.gridPoints) {
            UAVRelaySim.RateResult result = model.computeRates(q, coLocated, "none", "weak");
            for (double delivered : result.rE2E) close(delivered, 500_000, "all-grid tie fixture");
        }
        UAVRelaySim.PlacementResult tie = model.selectNominalPlacement(coLocated, "none", "weak");
        require(tie.bestIdx == 0 && Arrays.equals(tie.bestPos, new double[]{0, 0, 100}),
                "exact objective tie must select smallest grid index");
        close(tie.bestObj, 500_000, "tie objective bit/s (0.5 Mbps)");
        System.out.println("PASS zero epsilon identity and deterministic nominal all-grid tie");
    }

    public static void main(String[] args) {
        UAVRelaySim model = new UAVRelaySim();
        numericOracle(model);
        allocationAndBlockage(model);
        diskSampling(model);
        zeroErrorAndNominalTie(model);
        System.out.println("PASS ModelValidation: " + checks + " assertions");
    }
}
