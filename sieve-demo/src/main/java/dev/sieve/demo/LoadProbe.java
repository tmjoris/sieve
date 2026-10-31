package dev.sieve.demo;

import dev.sieve.Priority;
import dev.sieve.RetryPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load: fires original requests at a fixed rate regardless of responses, so overload is
 * real. Mix: 20% critical, 30% degraded, 30% best-effort, 20% bulk.
 *
 * <p>Retry modes: {@code none}, {@code naive} (two immediate retries, ignoring Retry-After) and
 * {@code budget} (Sieve's {@link RetryPolicy}).
 *
 * <p>Usage: LoadProbe port rps1,rps2,... seconds [none|naive|budget]
 */
public final class LoadProbe {

    static final double[] MIX = {0.20, 0.30, 0.30, 0.20};

    /** Outcome of one phase for one tier. {@code attempts} counts every call sent to the server. */
    record TierResult(long sent, long ok, long attempts, double p50Ms, double p99Ms) {
        double okPct() {
            return 100.0 * ok / Math.max(1, sent);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String[] rates = args[1].split(",");
        int seconds = Integer.parseInt(args[2]);
        String retry = args.length > 3 ? args[3] : "none";
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5)).build();

        System.out.printf("%-6s %-12s %6s %6s %9s %8s %8s%n",
                "rps", "tier", "sent", "ok%", "attempts", "p50ms", "p99ms");
        for (String r : rates) {
            int rps = Integer.parseInt(r);
            Map<Priority, TierResult> res = run(client, port, rps, seconds, retry);
            for (Priority p : Priority.values()) {
                TierResult t = res.get(p);
                System.out.printf("%-6d %-12s %6d %5.1f%% %9d %8.1f %8.1f%n",
                        rps, p.id(), t.sent(), t.okPct(), t.attempts(), t.p50Ms(), t.p99Ms());
            }
            System.out.println();
        }
    }

    static Map<Priority, TierResult> run(HttpClient client, int port, int rps, int seconds,
            String retry) throws Exception {
        int total = rps * seconds;
        Map<Priority, long[]> lat = new EnumMap<>(Priority.class);
        Map<Priority, AtomicInteger> idx = new EnumMap<>(Priority.class);
        Map<Priority, LongAdder> sent = new EnumMap<>(Priority.class);
        Map<Priority, LongAdder> ok = new EnumMap<>(Priority.class);
        Map<Priority, LongAdder> attempts = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) {
            lat.put(p, new long[total]);
            idx.put(p, new AtomicInteger());
            sent.put(p, new LongAdder());
            ok.put(p, new LongAdder());
            attempts.put(p, new LongAdder());
        }

        URI uri = URI.create("http://localhost:" + port + "/work");
        RetryPolicy policy = retry.equals("budget") ? RetryPolicy.defaults() : null;
        long gapNanos = 1_000_000_000L / rps;
        long next = System.nanoTime();
        double[] cum = new double[4];
        double acc = 0;
        for (int i = 0; i < 4; i++) {
            acc += MIX[i];
            cum[i] = acc;
        }
        Random rnd = new Random(42);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                double x = rnd.nextDouble();
                Priority p = Priority.values()[x < cum[0] ? 0 : x < cum[1] ? 1 : x < cum[2] ? 2 : 3];
                sent.get(p).increment();
                exec.submit(() -> one(client, uri, p, retry, policy, ok.get(p), attempts.get(p),
                        lat.get(p), idx.get(p)));
                next += gapNanos;
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
            }
        } // closing the executor waits for every request, including retries

        Map<Priority, TierResult> out = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) {
            int n = idx.get(p).get();
            long[] a = Arrays.copyOf(lat.get(p), n);
            Arrays.sort(a);
            double p50 = n == 0 ? Double.NaN : a[n / 2] / 1e6;
            double p99 = n == 0 ? Double.NaN : a[Math.min(n - 1, (int) (n * 0.99))] / 1e6;
            out.put(p, new TierResult(sent.get(p).sum(), ok.get(p).sum(), attempts.get(p).sum(),
                    p50, p99));
        }
        return out;
    }

    private static void one(HttpClient client, URI uri, Priority p, String retry,
            RetryPolicy policy, LongAdder ok, LongAdder attempts, long[] lat, AtomicInteger idx) {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header(Priority.HEADER, p.name()).GET().build();
        long t0 = System.nanoTime();
        int retries = 0;
        if (policy != null) {
            policy.onRequest();
        }
        while (true) {
            attempts.increment();
            int code = -1;
            Duration hint = Duration.ZERO;
            try {
                HttpResponse<Void> r = client.send(req, HttpResponse.BodyHandlers.discarding());
                code = r.statusCode();
                String ra = r.headers().firstValue("Retry-After").orElse(null);
                if (ra != null) {
                    hint = Duration.ofSeconds(Long.parseLong(ra));
                }
            } catch (IOException e) {
                code = -1; // timeout or connection error
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (code == 200) {
                ok.increment();
                lat[idx.getAndIncrement()] = System.nanoTime() - t0;
                return;
            }
            Duration wait = switch (retry) {
                case "naive" -> retries < 2 ? Duration.ZERO : null;
                case "budget" -> policy.onFailure(p, retries, hint).orElse(null);
                default -> null;
            };
            if (wait == null) {
                return;
            }
            retries++;
            try {
                if (!wait.isZero()) {
                    Thread.sleep(wait);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
