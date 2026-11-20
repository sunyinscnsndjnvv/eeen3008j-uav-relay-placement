/** Metrics of a fixed placement on independent user-location realizations. */
public record EvaluationMetrics(double networkQ05, double worstUserQ05, double outage,
                                double accessFraction, double backhaulFraction, double tieFraction,
                                double mean, double elapsedMillis) {
    public static final double OUTAGE_THRESHOLD_BPS = 500_000.0;
    public record TestCase(double[] position, double[][][] samples, PlacementMethods.Condition condition) {}

    public static EvaluationMetrics evaluate(UAVRelaySim model, TestCase test) {
        long start = System.nanoTime();
        int users = test.samples()[0].length;
        int scenarios = test.samples().length;
        double[][] perUser = new double[users][scenarios];
        double[] pooled = new double[users * scenarios];
        int outages = 0;
        int access = 0;
        int backhaul = 0;
        int ties = 0;
        int index = 0;
        double sum = 0.0;
        for (int s = 0; s < scenarios; s++) {
            var rates = model.computeRates(test.position(), test.samples()[s],
                    test.condition().shadow(), test.condition().backhaul());
            for (int i = 0; i < users; i++) {
                double rate = rates.rE2E[i];
                perUser[i][s] = rate;
                pooled[index++] = rate;
                sum += rate;
                if (rate < OUTAGE_THRESHOLD_BPS) outages++;
                if (rates.rAcc[i] < rates.rBh[i]) access++;
                else if (rates.rBh[i] < rates.rAcc[i]) backhaul++;
                else ties++;
            }
        }
        double worst = Double.POSITIVE_INFINITY;
        for (double[] user : perUser) {
            worst = Math.min(worst, PlacementMethods.quantile(user, 0.05));
        }
        return new EvaluationMetrics(PlacementMethods.quantile(pooled, 0.05), worst,
                (double) outages / index, (double) access / index, (double) backhaul / index,
                (double) ties / index, sum / index, (System.nanoTime() - start) / 1e6);
    }
}
