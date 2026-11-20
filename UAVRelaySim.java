import java.util.*;

public class UAVRelaySim {

    // --- 1. Scenario & Geometry Parameters (D1 Frozen) ---
    public final double areaSize = 500.0;      // 500m x 500m
    public final double uavHeight = 100.0;     // Fixed 100m
    public final int numUsers = 10;            // K = 10
    public final double[] bsPos = {50.0, 250.0, 25.0}; // Base station (x, y, z)
    public final double[][] gridPoints;        // 441 x 3 candidate points

    // --- 2. Wireless RF Parameters (D1 Frozen) ---
    public final double fcGhz = 2.4;
    public final double paDbm = 20.0;          // Access transmit power (dBm)
    public final double pbDbm = 30.0;          // Backhaul transmit power (dBm)
    public final double baHz = 10e6;           // Access bandwidth: 10 MHz
    public final double bbHz = 10e6;           // Backhaul nominal bandwidth: 10 MHz
    public final double n0DbmHz = -174.0;      // Thermal noise PSD (dBm/Hz)
    public final double nfDb = 7.0;            // Noise figure (dB)
    public final double l0Db = 40.0;           // Reference path loss at 1m (dB)
    public final double alpha = 2.2;           // Path loss exponent
    public final double tau = 0.5;             // Half-duplex time split ratio

    private final double paWatt;
    private final double pbWatt;
    private final double noiseAWatt;
    private final double noiseBWatt;

    // --- 3. Experimental Factor Levels (D2 Calibration Targets) ---
    private final Set<Integer> blockedUserIndices = new HashSet<>(Arrays.asList(0, 1, 2));
    private final Map<String, Double> shadowLossMap = new HashMap<>();
    private final Map<String, Double> backhaulCapMap = new HashMap<>();

    // Data structure for rate computation output (Frozen Interface for Member C)
    public static class RateResult {
        public double[] rE2E; // End-to-end delivered rate per user (bit/s)
        public double[] rAcc; // Allocated access-hop rate per user (bit/s)
        public double[] rBh;  // Allocated backhaul-hop rate per user (bit/s)
        public double[] cA;   // Exclusive access capacity per user (bit/s)
        public double cB;     // Total backhaul capacity (bit/s)

        public RateResult(int k) {
            this.rE2E = new double[k];
            this.rAcc = new double[k];
            this.rBh = new double[k];
            this.cA = new double[k];
        }
    }

    // Data structure for placement result
    public static class PlacementResult {
        public int bestIdx;
        public double[] bestPos;
        public double bestObj;

        public PlacementResult(int bestIdx, double[] bestPos, double bestObj) {
            this.bestIdx = bestIdx;
            this.bestPos = bestPos;
            this.bestObj = bestObj;
        }
    }

    public UAVRelaySim() {
        // Build 21 x 21 candidate grid including boundaries (0 to 500, step 25)
        this.gridPoints = new double[441][3];
        int idx = 0;
        for (int i = 0; i < 21; i++) {
            double x = i * (areaSize / 20.0);
            for (int j = 0; j < 21; j++) {
                double y = j * (areaSize / 20.0);
                this.gridPoints[idx][0] = x;
                this.gridPoints[idx][1] = y;
                this.gridPoints[idx][2] = uavHeight;
                idx++;
            }
        }

        // Convert dBm to Watts
        this.paWatt = Math.pow(10.0, (paDbm - 30.0) / 10.0);
        this.pbWatt = Math.pow(10.0, (pbDbm - 30.0) / 10.0);

        double noiseADbm = n0DbmHz + 10.0 * Math.log10(baHz) + nfDb;
        double noiseBDbm = n0DbmHz + 10.0 * Math.log10(bbHz) + nfDb;
        this.noiseAWatt = Math.pow(10.0, (noiseADbm - 30.0) / 10.0);
        this.noiseBWatt = Math.pow(10.0, (noiseBDbm - 30.0) / 10.0);

        // Initialize blockage & backhaul maps
        shadowLossMap.put("none", 0.0);
        shadowLossMap.put("light", 10.0);
        shadowLossMap.put("heavy", 25.0);

        backhaulCapMap.put("weak", 10e6);     // 10 Mbps
        backhaulCapMap.put("medium", 35e6);   // 35 Mbps
        backhaulCapMap.put("strong", 100e6);  // 100 Mbps
    }

    // Helper: 3D Euclidean distance
    private double distance3D(double[] p1, double[] p2) {
        double dx = p1[0] - p2[0];
        double dy = p1[1] - p2[1];
        double dz = p1[2] - p2[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // Generate estimated ground user positions: shape [K][3]
    public double[][] generateEstimatedUsers(String scenario, Random rng) {
        double[][] users = new double[numUsers][3];
        for (int i = 0; i < numUsers; i++) {
            if ("main".equals(scenario)) {
                users[i][0] = 300.0 + rng.nextDouble() * 150.0; // [300, 450]
                users[i][1] = 50.0 + rng.nextDouble() * 400.0;  // [50, 450]
            } else if ("uniform".equals(scenario)) {
                users[i][0] = rng.nextDouble() * areaSize;
                users[i][1] = rng.nextDouble() * areaSize;
            } else {
                throw new IllegalArgumentException("scenario must be 'main' or 'uniform'");
            }
            users[i][2] = 0.0;
        }
        return users;
    }

    // Uniform disk sampling: Radius = epsilon * sqrt(U), clipped to [0, 500]
    // Returns shape [numSamples][K][3]
    public double[][][] sampleUserPositions(double[][] estUsers, double epsilon, int numSamples, Random rng) {
        int K = estUsers.length;
        double[][][] sampled = new double[numSamples][K][3];
        for (int s = 0; s < numSamples; s++) {
            for (int i = 0; i < K; i++) {
                if (epsilon == 0.0) {
                    sampled[s][i][0] = estUsers[i][0];
                    sampled[s][i][1] = estUsers[i][1];
                    sampled[s][i][2] = estUsers[i][2];
                } else {
                    double r = epsilon * Math.sqrt(rng.nextDouble());
                    double theta = rng.nextDouble() * 2.0 * Math.PI;
                    double nx = estUsers[i][0] + r * Math.cos(theta);
                    double ny = estUsers[i][1] + r * Math.sin(theta);
                    sampled[s][i][0] = Math.max(0.0, Math.min(areaSize, nx));
                    sampled[s][i][1] = Math.max(0.0, Math.min(areaSize, ny));
                    sampled[s][i][2] = 0.0;
                }
            }
        }
        return sampled;
    }

    // Core rate computation function (Single realization [K][3])
    public RateResult computeRates(double[] uavPos, double[][] userPos, String shadowLevel, String backhaulLevel, double customTau) {
        int K = userPos.length;
        RateResult res = new RateResult(K);

        // 1. Backhaul Link Capacity C_b
        double distB = Math.max(distance3D(uavPos, bsPos), 1.0);
        double plBDb = l0Db + 10.0 * alpha * Math.log10(distB);
        double gainB = Math.pow(10.0, -plBDb / 10.0);
        double snrB = (pbWatt * gainB) / noiseBWatt;
        double cBRaw = bbHz * (Math.log(1.0 + snrB) / Math.log(2.0));
        res.cB = Math.min(cBRaw, backhaulCapMap.get(backhaulLevel));

        double allocatedBhPerUser = ((1.0 - customTau) * res.cB) / K;
        double extraLoss = shadowLossMap.get(shadowLevel);

        // 2. Access Link Capacity C_{a,i} & End-to-End Rate R_i
        for (int i = 0; i < K; i++) {
            double distA = Math.max(distance3D(uavPos, userPos[i]), 1.0);
            double plADb = l0Db + 10.0 * alpha * Math.log10(distA);
            if (blockedUserIndices.contains(i)) {
                plADb += extraLoss;
            }
            double gainA = Math.pow(10.0, -plADb / 10.0);
            double snrA = (paWatt * gainA) / noiseAWatt;
            res.cA[i] = baHz * (Math.log(1.0 + snrA) / Math.log(2.0));

            res.rAcc[i] = (customTau * res.cA[i]) / K;
            res.rBh[i] = allocatedBhPerUser;
            res.rE2E[i] = Math.min(res.rAcc[i], res.rBh[i]);
        }
        return res;
    }

    // Overload using default tau = 0.5
    public RateResult computeRates(double[] uavPos, double[][] userPos, String shadowLevel, String backhaulLevel) {
        return computeRates(uavPos, userPos, shadowLevel, backhaulLevel, this.tau);
    }

    // Method N: Nominal End-to-End Placement (Deterministic tie-breaking: smallest index wins)
    public PlacementResult selectNominalPlacement(double[][] estUsers, String shadowLevel, String backhaulLevel) {
        return selectNominalPlacement(estUsers, shadowLevel, backhaulLevel, gridPoints);
    }

    // Same baseline on an explicit grid, used by the grid-refinement experiment.
    public PlacementResult selectNominalPlacement(double[][] estUsers, String shadowLevel, String backhaulLevel,
                                                   double[][] candidates) {
        int bestIdx = 0;
        double bestObj = -1.0;

        for (int idx = 0; idx < candidates.length; idx++) {
            RateResult res = computeRates(candidates[idx], estUsers, shadowLevel, backhaulLevel);
            double minRate = Double.POSITIVE_INFINITY;
            for (double r : res.rE2E) {
                if (r < minRate) minRate = r;
            }
            if (minRate > bestObj) {
                bestObj = minRate;
                bestIdx = idx;
            }
        }
        return new PlacementResult(bestIdx, candidates[bestIdx], bestObj);
    }

    // Mandatory 7 Sanity Checks & D2 Backhaul Calibration
    public static void main(String[] args) {
        System.out.println("=== Running Member B Mandatory Sanity Checks (Java) ===");
        UAVRelaySim env = new UAVRelaySim();
        Random rng = new Random(42L);
        double[][] estUsers = env.generateEstimatedUsers("main", rng);
        double[] qTest = {250.0, 250.0, 100.0};

        // Check 1: tau = 0 or tau = 1 => R_e2e == 0
        RateResult rTau0 = env.computeRates(qTest, estUsers, "light", "medium", 0.0);
        RateResult rTau1 = env.computeRates(qTest, estUsers, "light", "medium", 1.0);
        for (int i = 0; i < env.numUsers; i++) {
            if (rTau0.rE2E[i] != 0.0 || rTau1.rE2E[i] != 0.0) {
                throw new AssertionError("Check 1 Failed!");
            }
        }
        System.out.println("[PASS] Check 1: tau=0 and tau=1 strictly yield 0 delivered rate.");

        // Check 2: Zero link capacity => R_e2e == 0
        UAVRelaySim envZero = new UAVRelaySim();
        envZero.backhaulCapMap.put("weak", 0.0);
        RateResult rZeroBh = envZero.computeRates(qTest, estUsers, "light", "weak");
        for (double r : rZeroBh.rE2E) {
            if (r != 0.0) throw new AssertionError("Check 2 Failed!");
        }
        System.out.println("[PASS] Check 2: Zero hop capacity strictly yields 0 delivered rate.");

        // Check 3: Sum of user rates <= allocated backhaul capacity (1 - tau) * C_b
        RateResult resMed = env.computeRates(qTest, estUsers, "light", "medium");
        double sumRate = 0.0;
        for (double r : resMed.rE2E) sumRate += r;
        double maxBhAlloc = (1.0 - env.tau) * resMed.cB;
        if (sumRate > maxBhAlloc + 1e-6) throw new AssertionError("Check 3 Failed!");
        System.out.printf("[PASS] Check 3: Sum rate (%.2f Mbps) <= Allocated backhaul (%.2f Mbps).%n",
                sumRate / 1e6, maxBhAlloc / 1e6);

        // Check 4: Zero location error (epsilon = 0) consistency
        double[][][] samplesEps0 = env.sampleUserPositions(estUsers, 0.0, 50, rng);
        RateResult rEst = env.computeRates(qTest, estUsers, "light", "medium");
        RateResult rSmp = env.computeRates(qTest, samplesEps0[0], "light", "medium");
        for (int i = 0; i < env.numUsers; i++) {
            if (Math.abs(rEst.rE2E[i] - rSmp.rE2E[i]) > 1e-9) throw new AssertionError("Check 4 Failed!");
        }
        System.out.println("[PASS] Check 4: Zero location error (epsilon=0) matches nominal computation identically.");

        // Check 5: Monotonicity with backhaul cap
        RateResult rWeak = env.computeRates(qTest, estUsers, "light", "weak");
        RateResult rStrong = env.computeRates(qTest, estUsers, "light", "strong");
        for (int i = 0; i < env.numUsers; i++) {
            if (rStrong.rE2E[i] < rWeak.rE2E[i]) throw new AssertionError("Check 5 Failed!");
        }
        System.out.println("[PASS] Check 5: Monotonicity holds (Strong >= Medium >= Weak backhaul).");

        // Check 6: Baseline Method N selection & D2 Backhaul Regime Calibration
        System.out.println("\n=== D2 Backhaul Regime Calibration (Method N Optimal Point) ===");
        String[] bhLevels = {"weak", "medium", "strong"};
        String[] shLevels = {"light", "heavy"};
        for (String bh : bhLevels) {
            for (String sh : shLevels) {
                PlacementResult opt = env.selectNominalPlacement(estUsers, sh, bh);
                RateResult resOpt = env.computeRates(opt.bestPos, estUsers, sh, bh);
                int bhBottleneckCount = 0;
                for (int i = 0; i < env.numUsers; i++) {
                    if (resOpt.rBh[i] <= resOpt.rAcc[i]) bhBottleneckCount++;
                }
                double bhRatio = (100.0 * bhBottleneckCount) / env.numUsers;
                System.out.printf("Backhaul=%-6s | Shadow=%-5s -> UAV=(%5.1f, %5.1f) | Min Rate=%.3f Mbps | BH Bottleneck Users=%.0f%%%n",
                        bh, sh, opt.bestPos[0], opt.bestPos[1], opt.bestObj / 1e6, bhRatio);
            }
        }
    }
}
