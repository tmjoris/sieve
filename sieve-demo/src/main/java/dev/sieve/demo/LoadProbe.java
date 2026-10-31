package dev.sieve.demo;

import dev.sieve.Priority;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load: fires requests at a fixed rate regardless of responses, so overload is real.
 * Mix: 20% critical, 30% degraded, 30% best-effort, 20% bulk.
 *
 * <p>Usage: LoadProbe port rps1,rps2,... seconds
 */
public final class LoadProbe {

    static final double[] MIX = {0.20, 0.30, 0.30, 0.20};

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String[] rates = args[1].split(",");
        int seconds = Integer.parseInt(args[2]);
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5)).build();

        System.out.printf("%-6s %-12s %6s %6s %8s %8s%n",
                "rps", "tier", "sent", "ok%", "p50ms", "p99ms");
        for (String r : rates) {
            runPhase(client, port, Integer.parseInt(r), seconds);
        }
    }

    static void runPhase(HttpClient client, int port, int rps, int seconds) throws Exception {
        Map<Priority, long[]> lat = new EnumMap<>(Priority.class);
        Map<Priority, AtomicLong> idx = new EnumMap<>(Priority.class);
        Map<Priority, LongAdder> sent = new EnumMap<>(Priority.class);
        Map<Priority, LongAdder> ok = new EnumMap<>(Priority.class);
        int total = rps * seconds;
        for (Priority p : Priority.values()) {
            lat.put(p, new long[total]);
            idx.put(p, new AtomicLong());
            sent.put(p, new LongAdder());
            ok.put(p, new LongAdder());
        }

        URI uri = URI.create("http://localhost:" + port + "/work");
        long gapNanos = 1_000_000_000L / rps;
        long next = System.nanoTime();
        double[] cum = new double[4];
        double acc = 0;
        for (int i = 0; i < 4; i++) {
            acc += MIX[i];
            cum[i] = acc;
        }
        java.util.Random rnd = new java.util.Random(42);

        try (ExecutorService done = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                double x = rnd.nextDouble();
                Priority p = Priority.values()[x < cum[0] ? 0 : x < cum[1] ? 1 : x < cum[2] ? 2 : 3];
                sent.get(p).increment();
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                        .header(Priority.HEADER, p.name()).GET().build();
                long t0 = System.nanoTime();
                client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                        .whenComplete((resp, err) -> {
                            if (resp != null && resp.statusCode() == 200) {
                                ok.get(p).increment();
                                long k = idx.get(p).getAndIncrement();
                                lat.get(p)[(int) k] = System.nanoTime() - t0;
                            }
                        });
                next += gapNanos;
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
            }
            Thread.sleep(3000); // let stragglers finish
        }

        for (Priority p : Priority.values()) {
            int n = (int) idx.get(p).get();
            long[] a = Arrays.copyOf(lat.get(p), n);
            Arrays.sort(a);
            double p50 = n == 0 ? Double.NaN : a[n / 2] / 1e6;
            double p99 = n == 0 ? Double.NaN : a[Math.min(n - 1, (int) (n * 0.99))] / 1e6;
            long s = sent.get(p).sum();
            System.out.printf("%-6d %-12s %6d %5.1f%% %8.1f %8.1f%n",
                    rps, p.id(), s, 100.0 * ok.get(p).sum() / Math.max(1, s), p50, p99);
        }
        System.out.println();
    }
}
