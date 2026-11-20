import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Reproducible study entry point. No final-test result is used to tune the frozen design. */
public final class RunExperiments {
    private RunExperiments() {}

    private static List<ExperimentSpec> specifications(boolean smoke) {
        var specs = new ArrayList<ExperimentSpec>();
        int seeds = smoke ? 2 : 20;
        int[] epsilons = smoke ? new int[] {0, 50} : new int[] {0, 10, 25, 50, 100};
        for (int i = 0; i < seeds; i++) {
            long seed = ExperimentSpec.FIRST_SEED + i;
            for (int epsilon : epsilons) {
                for (String shadow : new String[] {"light", "heavy"}) {
                    specs.add(new ExperimentSpec("core", "main", epsilon, new int[] {epsilon}, shadow,
                            new String[] {"weak", "medium", "strong"}, 200, 21, seed));
                }
            }
            if (smoke) continue;
            specs.add(new ExperimentSpec("mismatch", "main", 50, new int[] {25, 50, 100}, "heavy",
                    new String[] {"medium"}, 200, 21, seed));
            for (String[] condition : new String[][] {
                    {"light", "weak"}, {"heavy", "medium"}, {"heavy", "strong"}}) {
                specs.add(new ExperimentSpec("uniform", "uniform", 50, new int[] {50}, condition[0],
                        new String[] {condition[1]}, 200, 21, seed));
            }
            specs.add(new ExperimentSpec("grid", "main", 50, new int[] {50}, "heavy",
                    new String[] {"medium"}, 200, 41, seed));
            for (int samples : new int[] {100, 400, 800}) {
                specs.add(new ExperimentSpec("stability", "main", 50, new int[] {50}, "heavy",
                        new String[] {"medium"}, samples, 21, seed));
            }
        }
        return specs;
    }

    private static String sourceHash(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void writeConfiguration(Path directory, boolean smoke, String started)
            throws IOException, NoSuchAlgorithmException {
        var config = new StringBuilder();
        config.append("version=C-2026-11-16-v1\nstarted_utc=").append(started);
        config.append("\nmode=").append(smoke ? "smoke" : "all");
        config.append("\njava_version=").append(System.getProperty("java.version"));
        config.append("\nseeds=").append(smoke ? 2 : 20);
        config.append("\nfirst_seed=").append(ExperimentSpec.FIRST_SEED);
        config.append("\nselection_samples=200\ntest_samples=1000\noutage_threshold_bps=500000");
        config.append("\nquantile=empirical_inverse_cdf_ceil_pn\nboundary=coordinate_clipping");
        config.append("\nci=seed_level_Student_t_95_percent\npaired=within_condition_seed");
        config.append("\nlayout_seed=seed(main),seed+300000(uniform)");
        config.append("\nselection_seed=seed+100000\ntest_seed=seed+200000");
        config.append("\nselection_time=shared_access_scoring_plus_joint_R_A_selection");
        config.append("\nworkers=4\n");
        for (String file : new String[] {"UAVRelaySim.java", "PlacementMethods.java", "EvaluationMetrics.java",
                "ExperimentSpec.java", "ExperimentRun.java", "RunExperiments.java",
                "README.md"}) {
            config.append("sha256.").append(file).append("=").append(sourceHash(Path.of(file))).append("\n");
        }
        Files.writeString(directory.resolve("configuration.txt"), config, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !"all".equals(args[0]) && !"smoke".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: java -cp build/classes RunExperiments [all|smoke]");
        }
        boolean smoke = args.length == 1 && "smoke".equals(args[0]);
        Path directory = Path.of(smoke ? "build/smoke-results" : "results");
        Files.createDirectories(directory);
        // Avoid silently replacing a previous run whose data may already be in a poster.
        if (Files.exists(directory.resolve("metrics.csv"))) {
            throw new IOException("Output exists; archive the previous results before running: " + directory);
        }
        String started = Instant.now().toString();
        writeConfiguration(directory, smoke, started);
        var specs = specifications(smoke);
        System.out.println("START blocks=" + specs.size() + " mode=" + (smoke ? "smoke" : "all"));
        long start = System.nanoTime();
        int metricCount = 0;
        try (var pool = Executors.newFixedThreadPool(4);
             BufferedWriter metrics = Files.newBufferedWriter(directory.resolve("metrics.csv"), StandardCharsets.UTF_8);
             BufferedWriter geometry = Files.newBufferedWriter(directory.resolve("geometry.csv"), StandardCharsets.UTF_8);
             BufferedWriter raw = Files.newBufferedWriter(directory.resolve("representative_rates.csv"), StandardCharsets.UTF_8)) {
            metrics.write(ExperimentRun.METRICS_HEADER + "\n");
            geometry.write(ExperimentRun.GEOMETRY_HEADER + "\n");
            raw.write(ExperimentRun.RAW_HEADER + "\n");
            List<Future<ExperimentRun.Output>> futures = new ArrayList<>();
            for (ExperimentSpec spec : specs) {
                Callable<ExperimentRun.Output> work = () -> new ExperimentRun(spec).execute();
                futures.add(pool.submit(work));
            }
            for (int i = 0; i < futures.size(); i++) {
                var result = futures.get(i).get();
                for (String row : result.metrics()) metrics.write(row + "\n");
                for (String row : result.geometry()) geometry.write(row + "\n");
                for (String row : result.rawRates()) raw.write(row + "\n");
                metricCount += result.metrics().size();
                if ((i + 1) % 20 == 0 || i + 1 == futures.size()) {
                    metrics.flush();
                    System.out.println("PROGRESS blocks=" + (i + 1) + "/" + futures.size() + " rows=" + metricCount);
                }
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        Files.writeString(directory.resolve("COMPLETE.txt"),
                "started_utc=" + started + "\nfinished_utc=" + Instant.now() + "\nrows=" + metricCount
                + "\nelapsed_seconds=" + seconds + "\n", StandardCharsets.UTF_8);
        System.out.printf(java.util.Locale.ROOT, "COMPLETE rows=%d elapsed_seconds=%.3f%n", metricCount, seconds);
    }
}
