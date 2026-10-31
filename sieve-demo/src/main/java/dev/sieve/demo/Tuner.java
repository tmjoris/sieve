package dev.sieve.demo;

import dev.sieve.Priority;
import java.net.http.HttpClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Searches for the shedding thresholds that suit this service, the way Netflix generates a
 * unique configuration per cluster. Each trial starts a real server in this JVM with a candidate
 * configuration and hits it with a load spike; the score rewards goodput of the lower tiers
 * while hard-requiring that critical traffic stays available and fast.
 *
 * <p>Search is coordinate descent: sweep each parameter over its candidate values with the others
 * held at the best known, keep the best, repeat. The winner should then be validated with the
 * full Gatling spike (scripts/spike.sh), as Netflix validates generated configs with load tests.
 *
 * <p>Each trial runs the server as a separate process (so the load generator does not steal its
 * CPU), is repeated and averaged to tame noise, and a new best must beat the incumbent by a
 * margin before it replaces it.
 *
 * <p>Run from the repository root after {@code ./gradlew :sieve-demo:installDist}.
 * Usage: Tuner [sweeps=2] [spikeRps=600] [spikeSeconds=8] [repeats=2] [out=results/tuned.env]
 */
public final class Tuner {

    /** Candidate values per parameter. */
    private static final int[] TARGET_MS = {40, 60, 90, 120};
    private static final double[] TARGET_LO = {0.02, 0.05, 0.10, 0.20};
    private static final double[] TARGET_WIDTH = {0.15, 0.30, 0.60};
    private static final int[] MAX_MS = {200, 300, 500, 800};
    private static final double[] MAX_LO = {0.05, 0.10, 0.20, 0.40};
    private static final double MAX_WIDTH = 0.30;

    /** A point in the search space, as indexes into the candidate arrays. */
    private record Point(int targetMs, int targetLo, int targetWidth, int maxMs, int maxLo) {
        ServerConfig apply(ServerConfig base) {
            double tLo = TARGET_LO[targetLo];
            double mLo = MAX_LO[maxLo];
            return base.withCurves(TARGET_MS[targetMs], tLo, Math.min(1.0, tLo + TARGET_WIDTH[targetWidth]),
                    MAX_MS[maxMs], mLo, Math.min(1.0, mLo + MAX_WIDTH));
        }

        Point with(int param, int value) {
            return switch (param) {
                case 0 -> new Point(value, targetLo, targetWidth, maxMs, maxLo);
                case 1 -> new Point(targetMs, value, targetWidth, maxMs, maxLo);
                case 2 -> new Point(targetMs, targetLo, value, maxMs, maxLo);
                case 3 -> new Point(targetMs, targetLo, targetWidth, value, maxLo);
                default -> new Point(targetMs, targetLo, targetWidth, maxMs, value);
            };
        }

        static final int[] SIZES = {TARGET_MS.length, TARGET_LO.length, TARGET_WIDTH.length,
                MAX_MS.length, MAX_LO.length};
        static final String[] NAMES = {"targetMs", "targetLo", "targetWidth", "maxMs", "maxLo"};
    }

    private record Trial(Point point, double score, Map<Priority, LoadProbe.TierResult> res) {}

    /** A candidate must beat the incumbent by this many score points to replace it. */
    private static final double MARGIN = 5.0;

    private static final String LAUNCHER = "sieve-demo/build/install/sieve-demo/bin/sieve-demo";

    private static int port = 18080;

    public static void main(String[] args) throws Exception {
        int sweeps = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int rps = args.length > 1 ? Integer.parseInt(args[1]) : 600;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int repeats = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        Path out = Path.of(args.length > 4 ? args[4] : "results/tuned.env");

        ServerConfig base = ServerConfig.defaults("shed", 0);
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5)).build();

        Map<Point, Trial> seen = new HashMap<>();
        Point best = new Point(1, 2, 1, 1, 1); // the demo's hand-picked defaults
        Trial bestTrial = record(seen, evaluate(client, base, best, rps, seconds, repeats), "default");
        for (int sweep = 1; sweep <= sweeps; sweep++) {
            boolean improved = false;
            for (int param = 0; param < Point.SIZES.length; param++) {
                for (int v = 0; v < Point.SIZES[param]; v++) {
                    Point candidate = best.with(param, v);
                    if (seen.containsKey(candidate)) {
                        continue;
                    }
                    Trial t = record(seen, evaluate(client, base, candidate, rps, seconds, repeats),
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

        ServerConfig win = best.apply(base);
        System.out.println();
        System.out.println("BEST score=" + String.format("%.1f", bestTrial.score()));
        summary(bestTrial.res());
        List<String> lines = new ArrayList<>(List.of(
                "export SIEVE_TARGET_MS=" + win.targetMs(),
                "export SIEVE_TARGET_LO=" + win.targetLo(),
                "export SIEVE_TARGET_HI=" + win.targetHi(),
                "export SIEVE_MAX_MS=" + win.maxMs(),
                "export SIEVE_MAX_LO=" + win.maxLo(),
                "export SIEVE_MAX_HI=" + win.maxHi()));
        lines.forEach(System.out::println);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, lines);
        System.out.println("written to " + out);
        System.exit(0);
    }

    private static Trial evaluate(HttpClient client, ServerConfig base, Point p, int rps,
            int seconds, int repeats) throws Exception {
        List<Map<Priority, LoadProbe.TierResult>> runs = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            ServerConfig c = p.apply(base).withPort(port++);
            runs.add(runOnce(client, c, rps, seconds));
        }
        Map<Priority, LoadProbe.TierResult> mean = average(runs);
        return new Trial(p, score(mean), mean);
    }

    /** Start the server as its own process, warm it up, spike it, stop it. */
    private static Map<Priority, LoadProbe.TierResult> runOnce(HttpClient client, ServerConfig c,
            int rps, int seconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(LAUNCHER, "shed", String.valueOf(c.port()));
        pb.environment().put("SIEVE_TARGET_MS", String.valueOf(c.targetMs()));
        pb.environment().put("SIEVE_TARGET_LO", String.valueOf(c.targetLo()));
        pb.environment().put("SIEVE_TARGET_HI", String.valueOf(c.targetHi()));
        pb.environment().put("SIEVE_MAX_MS", String.valueOf(c.maxMs()));
        pb.environment().put("SIEVE_MAX_LO", String.valueOf(c.maxLo()));
        pb.environment().put("SIEVE_MAX_HI", String.valueOf(c.maxHi()));
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        try {
            awaitUp(client, c.port());
            LoadProbe.run(client, c.port(), 100, 6, "none"); // warm up the JVM, discard
            return LoadProbe.run(client, c.port(), rps, seconds, "none");
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
        Map<Priority, LoadProbe.TierResult> out = new java.util.EnumMap<>(Priority.class);
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
     * Critical traffic must stay at least 99% available with p99 under 400 ms, otherwise the
     * trial is rejected (its score stays far below any valid one). Among valid trials, reward the
     * lower tiers' goodput, weighted by tier, and lightly punish critical tail latency.
     */
    static double score(Map<Priority, LoadProbe.TierResult> r) {
        LoadProbe.TierResult crit = r.get(Priority.CRITICAL);
        if (crit.okPct() < 99.0 || crit.p99Ms() > 400 || Double.isNaN(crit.p99Ms())) {
            return -1000 + crit.okPct() - crit.p99Ms() / 100;
        }
        return 4 * r.get(Priority.DEGRADED).okPct()
                + 2 * r.get(Priority.BEST_EFFORT).okPct()
                + r.get(Priority.BULK).okPct()
                - 0.05 * crit.p99Ms();
    }

    private static Trial record(Map<Point, Trial> seen, Trial t, String label) {
        seen.put(t.point(), t);
        LoadProbe.TierResult c = t.res().get(Priority.CRITICAL);
        System.out.printf("%-18s %-60s score=%8.1f crit=%5.1f%%/%4.0fms deg=%4.1f%% be=%4.1f%% bulk=%4.1f%%%n",
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
