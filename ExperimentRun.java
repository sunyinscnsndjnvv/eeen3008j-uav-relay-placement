import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Executes one layout/selection block; workers never write shared output files. */
public final class ExperimentRun {
    public record Output(List<String> metrics, List<String> geometry, List<String> rawRates) {}
    public static final String METRICS_HEADER =
            "study,scenario,assumed_epsilon,test_epsilon,shadow,backhaul,selection_samples,grid_side,"
            + "seed,layout_seed,selection_seed,test_seed,method,x,y,objective_bps,network_q05_bps,"
            + "worst_user_q05_bps,outage_fraction,access_fraction,backhaul_fraction,tie_fraction,"
            + "mean_bps,selection_ms,evaluation_ms";
    public static final String GEOMETRY_HEADER =
            "study,scenario,assumed_epsilon,test_epsilon,shadow,backhaul,selection_samples,grid_side,"
            + "seed,kind,index,x,y,z";
    public static final String RAW_HEADER = "seed,method,sample,user,access_bps,backhaul_bps,delivered_bps";

    private final ExperimentSpec spec;
    private final UAVRelaySim model = new UAVRelaySim();

    public ExperimentRun(ExperimentSpec spec) { this.spec = spec; }

    public Output execute() {
        double[][] estimated = model.generateEstimatedUsers(spec.scenario(), new Random(spec.layoutSeed()));
        double[][] candidates = spec.gridSide() == 21 ? model.gridPoints : PlacementMethods.grid(spec.gridSide());
        double[][][] selection = model.sampleUserPositions(estimated, spec.assumedEpsilon(),
                spec.selectionSamples(), new Random(spec.selectionSeed()));
        var methods = new PlacementMethods(model, estimated, candidates);
        var scores = methods.scoreAccess(selection, spec.shadow());
        var metricRows = new ArrayList<String>();
        var geometryRows = new ArrayList<String>();
        var rawRows = new ArrayList<String>();
        for (String backhaul : spec.backhauls()) {
            var condition = new PlacementMethods.Condition(spec.shadow(), backhaul);
            var nominal = methods.nominal(condition);
            var pair = methods.select(scores, condition);
            List<PlacementMethods.Selection> choices = List.of(nominal, pair.robust(), pair.access());
            List<String> names = List.of("N", "R", "A");
            for (int actualEpsilon : spec.testEpsilons()) {
                double[][][] test = model.sampleUserPositions(estimated, actualEpsilon,
                        ExperimentSpec.TEST_SAMPLES, new Random(spec.testSeed()));
                boolean representative = "core".equals(spec.study()) && spec.seed() == ExperimentSpec.FIRST_SEED
                        && actualEpsilon == 50 && "heavy".equals(spec.shadow()) && "medium".equals(backhaul);
                for (int m = 0; m < choices.size(); m++) {
                    var choice = choices.get(m);
                    var metrics = EvaluationMetrics.evaluate(model, new EvaluationMetrics.TestCase(
                            choice.placement().bestPos, test, condition));
                    metricRows.add(metricRow(actualEpsilon, condition, names.get(m), choice, metrics));
                    if (representative) {
                        geometryRows.add(geometryRow(names.get(m), 0, choice.placement().bestPos));
                        for (int s = 0; s < test.length; s++) {
                            var rates = model.computeRates(choice.placement().bestPos, test[s], spec.shadow(), backhaul);
                            for (int i = 0; i < estimated.length; i++) {
                                rawRows.add(String.format(Locale.ROOT, "%d,%s,%d,%d,%.12g,%.12g,%.12g",
                                        spec.seed(), names.get(m), s, i, rates.rAcc[i], rates.rBh[i], rates.rE2E[i]));
                            }
                        }
                    }
                }
                if (representative) {
                    geometryRows.add(geometryRow("BS", 0, model.bsPos));
                    for (int i = 0; i < estimated.length; i++) {
                        geometryRows.add(geometryRow("user", i, estimated[i]));
                    }
                    for (int q = 0; q < candidates.length; q++) {
                        geometryRows.add(geometryRow("candidate", q, candidates[q]));
                    }
                }
            }
        }
        return new Output(metricRows, geometryRows, rawRows);
    }

    private String metricRow(int actualEpsilon, PlacementMethods.Condition condition, String name,
                             PlacementMethods.Selection choice, EvaluationMetrics metrics) {
        var point = choice.placement();
        return String.format(Locale.ROOT,
                "%s,%s,%d,%d,%s,%s,%d,%d,%d,%d,%d,%d,%s,"
                + "%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.9g,%.9g",
                spec.study(), spec.scenario(), spec.assumedEpsilon(), actualEpsilon,
                condition.shadow(), condition.backhaul(), spec.selectionSamples(), spec.gridSide(),
                spec.seed(), spec.layoutSeed(), spec.selectionSeed(), spec.testSeed(), name,
                point.bestPos[0], point.bestPos[1], point.bestObj, metrics.networkQ05(),
                metrics.worstUserQ05(), metrics.outage(), metrics.accessFraction(), metrics.backhaulFraction(),
                metrics.tieFraction(), metrics.mean(), choice.elapsedMillis(), metrics.elapsedMillis());
    }

    private String geometryRow(String kind, int index, double[] point) {
        return String.format(Locale.ROOT, "%s,%s,%d,50,%s,medium,%d,%d,%d,%s,%d,%.12g,%.12g,%.12g",
                spec.study(), spec.scenario(), spec.assumedEpsilon(), spec.shadow(), spec.selectionSamples(),
                spec.gridSide(), spec.seed(), kind, index, point[0], point[1], point[2]);
    }
}
