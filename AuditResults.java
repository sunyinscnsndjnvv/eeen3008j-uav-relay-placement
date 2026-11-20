import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Audits the real CSV output and independently replays one frozen experiment block. */
public final class AuditResults {
    private AuditResults() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String key(String[] row) {
        return String.join(",", Arrays.copyOf(row, 9));
    }

    private static void audit(Path directory) throws IOException {
        require(Files.exists(directory.resolve("COMPLETE.txt")), "Run has not completed");
        List<String> lines = Files.readAllLines(directory.resolve("metrics.csv"));
        require(lines.get(0).equals(ExperimentRun.METRICS_HEADER), "Unexpected metrics schema");
        require(lines.size() == 2401, "Expected 2400 method-level rows");
        Map<String, Integer> studyCounts = new HashMap<>();
        Map<String, Map<String, String[]>> groups = new HashMap<>();
        Map<String, String[]> rowsByIdentity = new HashMap<>();
        Map<String, Set<Long>> seedsByCondition = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split(",");
            require(row.length == 25, "CSV column count");
            studyCounts.merge(row[0], 1, Integer::sum);
            String group = key(row);
            require(groups.computeIfAbsent(group, unused -> new HashMap<>()).put(row[12], row) == null,
                    "Duplicate method in paired run");
            require(rowsByIdentity.put(group + "," + row[12], row) == null, "Duplicate row identity");
            seedsByCondition.computeIfAbsent(String.join(",", Arrays.copyOf(row, 8)),
                    unused -> new HashSet<>()).add(Long.parseLong(row[8]));
            for (int i = 13; i < 25; i++) {
                require(Double.isFinite(Double.parseDouble(row[i])), "Nonfinite metric");
            }
            for (int i = 18; i <= 21; i++) {
                double value = Double.parseDouble(row[i]);
                require(value >= 0 && value <= 1, "Fraction outside [0,1]");
            }
            double fractions = Double.parseDouble(row[19]) + Double.parseDouble(row[20])
                    + Double.parseDouble(row[21]);
            require(Math.abs(fractions - 1.0) < 1e-9, "Bottleneck partition");
            require(Long.parseLong(row[10]) != Long.parseLong(row[11]), "Selection and test seeds coincide");
            require(Double.parseDouble(row[13]) >= 0 && Double.parseDouble(row[13]) <= 500
                    && Double.parseDouble(row[14]) >= 0 && Double.parseDouble(row[14]) <= 500,
                    "Deployment outside area");
            double cap = switch (row[5]) {
                case "weak" -> 500_000;
                case "medium" -> 1_750_000;
                case "strong" -> 5_000_000;
                default -> throw new AssertionError("Unknown backhaul regime");
            };
            require(Double.parseDouble(row[16]) <= cap + 1e-5
                    && Double.parseDouble(row[22]) <= cap + 1e-5, "Delivered rate exceeds per-user cap");
        }
        require(studyCounts.equals(Map.of("core", 1800, "mismatch", 180, "uniform", 180,
                "grid", 60, "stability", 180)), "Study matrix incomplete: " + studyCounts);
        for (var seeds : seedsByCondition.values()) require(seeds.size() == 20, "Condition needs 20 seeds");
        for (var entry : groups.entrySet()) {
            var group = entry.getValue();
            require(group.keySet().equals(Set.of("N", "R", "A")), "Incomplete paired comparison");
            String[] nominal = group.get("N");
            String[] robust = group.get("R");
            if ("0".equals(nominal[2]) && "0".equals(nominal[3])) {
                for (int i = 13; i <= 22; i++) {
                    require(nominal[i].equals(robust[i]), "Zero-error N/R mismatch at column " + i);
                }
            }
            if ("grid".equals(nominal[0])) {
                for (String method : List.of("N", "R", "A")) {
                    String[] dense = group.get(method);
                    String[] lookup = dense.clone();
                    lookup[0] = "core";
                    lookup[7] = "21";
                    String[] coarse = rowsByIdentity.get(key(lookup) + "," + method);
                    require(coarse != null, "Missing coarse-grid comparison");
                    require(Double.parseDouble(dense[15]) + 1e-5 >= Double.parseDouble(coarse[15]),
                            "Refinement reduced selection objective");
                }
            }
        }
        var spec = new ExperimentSpec("core", "main", 50, new int[] {50}, "heavy",
                new String[] {"weak", "medium", "strong"}, 200, 21, ExperimentSpec.FIRST_SEED);
        var replay = new ExperimentRun(spec).execute();
        for (String line : replay.metrics()) {
            String[] row = line.split(",");
            String[] original = rowsByIdentity.get(key(row) + "," + row[12]);
            require(original != null, "Replay identity absent");
            require(Arrays.equals(Arrays.copyOf(row, 23), Arrays.copyOf(original, 23)),
                    "Independent-process replay changed non-timing outputs");
        }
        require(Files.readAllLines(directory.resolve("geometry.csv")).size() == 456, "Geometry incomplete");
        require(Files.readAllLines(directory.resolve("representative_rates.csv")).size() == 30001,
                "Representative raw rates incomplete");
        System.out.println("PASS AuditResults: 2400 rows, 800 paired runs, 20 seeds/condition, "
                + "zero-error agreement, nested-grid objectives, 9-row replay, geometry and 30000 raw rates");
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("Usage: java -cp build/classes AuditResults");
        audit(Path.of("results"));
    }
}
