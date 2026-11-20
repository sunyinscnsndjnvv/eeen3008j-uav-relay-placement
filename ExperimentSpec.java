/** A block shares layout and selection samples across backhaul levels and test radii. */
public record ExperimentSpec(String study, String scenario, int assumedEpsilon, int[] testEpsilons,
                             String shadow, String[] backhauls, int selectionSamples, int gridSide, long seed) {
    public static final long FIRST_SEED = 2026111600L;
    public static final int TEST_SAMPLES = 1000;
    public long layoutSeed() { return seed + ("uniform".equals(scenario) ? 300_000 : 0); }
    public long selectionSeed() { return seed + 100_000; }
    public long testSeed() { return seed + 200_000; }
}
