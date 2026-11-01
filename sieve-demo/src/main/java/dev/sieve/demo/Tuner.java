package dev.sieve.demo;

import dev.sieve.Priority;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Searches for the graded tier thresholds and the critical floor that suit this service, the way
 * Netflix generates a configuration per cluster and validates it with a load test. Each trial
 * starts the demo server as its own process in {@code limiter} mode with a candidate setting and
 * hits it with an overload spike; the score rewards the lower tiers' goodput while requiring that
 * critical traffic stays available and fast.
 *
 * <p>Search is coordinate descent: sweep each parameter over its candidate values with the others
 * held at the best known, keep the best, repeat. Each trial is repeated and averaged, and a new best
 * must beat the incumbent by a margin before it replaces it. Validate the winner with the full
 * Gatling spike ({@code benchmark.yml} with {@code arms}), as Netflix validates generated configs.
 *
 * <p>Run from the repository root after {@code ./gradlew :sieve-demo:installDist}.
 * Usage: Tuner [sweeps=2] [spikeRps=1000] [spikeSeconds=8] [repeats=2] [out=results/tuned.env]
 */
public final class Tuner {

    /** Candidate values per parameter (fractions of the concurrency limit, and floor slots). */
    private static final double[] DEGRADED = {0.95, 0.85, 0.75};
    private static final double[] BEST_EFFORT = {0.80, 0.70, 0.60, 0.50};
    private static final double[] BULK = {0.60, 0.50, 0.40, 0.30};
    private static final int[] FLOOR = {0, 8, 16, 32};

    /** A point in the search space, as indexes into the candidate arrays. */
    private record Point(int degraded, int bestEffort, int bulk, int floor) {
        static final int[] SIZES = {DEGRADED.length, BEST_EFFORT.length, BULK.length, FLOOR.length};
        static final String[] NAMES = {"degraded", "bestEffort", "bulk", "floor"};

        /** A lower tier may not have a higher threshold than a tier above it. */
        boolean valid() {
            return BULK[bulk] <= BEST_EFFORT[bestEffort] && BEST_EFFORT[bestEffort] <= DEGRADED[degraded];
        }

        Point with(int param, int value) {
            return switch (param) {
                case 0 -> new Point(value, bestEffort, bulk, floor);
                case 1 -> new Point(degraded, value, bulk, floor);
                case 2 -> new Point(degraded, bestEffort, value, floor);
                default -> new Point(degraded, bestEffort, bulk, value);
            };
        }

        String thresholds() {
            return "1.0:" + DEGRADED[degraded] + ":" + BEST_EFFORT[bestEffort] + ":" + BULK[bulk];
        }

        int floorSlots() {
            return FLOOR[floor];
        }

        @Override
        public String toString() {
            return "thresholds " + thresholds() + " floor " + floorSlots();
        }
    }

    private record Trial(Point point, double score, Map<Priority, LoadProbe.TierResult> res) {}

    /** A candidate must beat the incumbent by this many score points to replace it. */
    private static final double MARGIN = 5.0;

    private static final String LAUNCHER = "sieve-demo/build/install/sieve-demo/bin/sieve-demo";

    private static int port = 18080;

    public static void main(String[] args) throws Exception {
        int sweeps = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int rps = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int repeats = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        Path out = Path.of(args.length > 4 ? args[4] : "results/tuned.env");

        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5)).build();

        Map<Point, Trial> seen = new HashMap<>();
        Point best = new Point(1, 1, 1, 0); // the library's graded defaults, no floor
        Trial bestTrial = record(seen, evaluate(client, best, rps, seconds, repeats), "default");
        for (int sweep = 1; sweep <= sweeps; sweep++) {
            boolean improved = false;
            for (int param = 0; param < Point.SIZES.length; param++) {
                for (int v = 0; v < Point.SIZES[param]; v++) {
                    Point candidate = best.with(param, v);
                    if (!candidate.valid() || seen.containsKey(candidate)) {
                        continue;
                    }
                    Trial t = record(seen, evaluate(client, candidate, rps, seconds, repeats),
                            "sweep " + sweep + " " + Point.NAMES[param]);
                    if (t.score() > bestTrial.score() + MARGIN) {
                        bestTrial = t;
                        best = candidate;
                        improved = true;
                    }
                }
            }
            if (!improved) {
                System.out.println("no improvement in sweep " + sweep + ", stopping");
                break;
            }
        }

        System.out.println();
        System.out.printf("BEST %s score=%.1f%n", best, bestTrial.score());
        summary(bestTrial.res());
        List<String> lines = new ArrayList<>(List.of(
                "export SIEVE_TIERS=graded",
                "export SIEVE_TIER_THRESHOLDS=" + best.thresholds(),
                "export SIEVE_CRITICAL_FLOOR=" + best.floorSlots()));
        lines.forEach(System.out::println);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, lines);
        System.out.println("written to " + out);
        System.exit(0);
    }

    private static Trial evaluate(HttpClient client, Point p, int rps, int seconds, int repeats)
            throws Exception {
        List<Map<Priority, LoadProbe.TierResult>> runs = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            runs.add(runOnce(client, p, port++, rps, seconds));
        }
        Map<Priority, LoadProbe.TierResult> mean = average(runs);
        return new Trial(p, score(mean, p), mean);
    }

    /** Start the server as its own process, warm it up, spike it, stop it. */
    private static Map<Priority, LoadProbe.TierResult> runOnce(
            HttpClient client, Point p, int port, int rps, int seconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(LAUNCHER, "limiter", String.valueOf(port));
        pb.environment().put("SIEVE_TIERS", "graded");
        pb.environment().put("SIEVE_TIER_THRESHOLDS", p.thresholds());
        pb.environment().put("SIEVE_CRITICAL_FLOOR", String.valueOf(p.floorSlots()));
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        try {
            awaitUp(client, port);
            LoadProbe.run(client, port, 100, 6, "none"); // warm up the JVM, discard
            return LoadProbe.run(client, port, rps, seconds, "none");
        } finally {
            proc.destroy();
            proc.waitFor();
        }
    }

    private static void awaitUp(HttpClient client, int port) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics")).build();
        for (int i = 0; i < 100; i++) {
            try {
                if (client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("server on port " + port + " did not start");
    }

    /** Mean of each tier's numbers across runs. */
    private static Map<Priority, LoadProbe.TierResult> average(
            List<Map<Priority, LoadProbe.TierResult>> runs) {
        Map<Priority, LoadProbe.TierResult> out = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) {
            long sent = 0;
            long ok = 0;
            long attempts = 0;
            double p50 = 0;
            double p99 = 0;
            for (var r : runs) {
                LoadProbe.TierResult t = r.get(p);
                sent += t.sent();
                ok += t.ok();
                attempts += t.attempts();
                p50 += t.p50Ms();
                p99 += t.p99Ms();
            }
            out.put(p, new LoadProbe.TierResult(sent, ok, attempts, p50 / runs.size(), p99 / runs.size()));
        }
        return out;
    }

    /**
     * Critical traffic must stay at least 99% available with p99 under 400 ms, otherwise the trial
     * is rejected (its score stays far below any valid one). Among valid trials, reward the lower
     * tiers' goodput, weighted by tier, and lightly punish critical tail latency and large floors
     * (a floor lets the service run above its limit).
     */
    static double score(Map<Priority, LoadProbe.TierResult> r, Point p) {
        LoadProbe.TierResult crit = r.get(Priority.CRITICAL);
        if (crit.okPct() < 99.0 || crit.p99Ms() > 400 || Double.isNaN(crit.p99Ms())) {
            return -1000 + crit.okPct() - crit.p99Ms() / 100;
        }
        return 4 * r.get(Priority.DEGRADED).okPct()
                + 2 * r.get(Priority.BEST_EFFORT).okPct()
                + r.get(Priority.BULK).okPct()
                - 0.05 * crit.p99Ms()
                - 0.1 * p.floorSlots();
    }

    private static Trial record(Map<Point, Trial> seen, Trial t, String label) {
        seen.put(t.point(), t);
        LoadProbe.TierResult c = t.res().get(Priority.CRITICAL);
        System.out.printf("%-18s %-44s score=%8.1f crit=%5.1f%%/%4.0fms deg=%4.1f%% be=%4.1f%% bulk=%4.1f%%%n",
                label, t.point(), t.score(), c.okPct(), c.p99Ms(),
                t.res().get(Priority.DEGRADED).okPct(), t.res().get(Priority.BEST_EFFORT).okPct(),
                t.res().get(Priority.BULK).okPct());
        return t;
    }

    private static void summary(Map<Priority, LoadProbe.TierResult> res) {
        for (Priority p : Priority.values()) {
            LoadProbe.TierResult t = res.get(p);
            System.out.printf("  %-12s ok=%5.1f%% p50=%6.1fms p99=%6.1fms%n",
                    p.id(), t.okPct(), t.p50Ms(), t.p99Ms());
        }
    }
}
