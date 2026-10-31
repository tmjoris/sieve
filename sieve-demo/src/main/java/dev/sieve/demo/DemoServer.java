package dev.sieve.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sieve.Admission;
import dev.sieve.LatencySloSignal;
import dev.sieve.Priority;
import dev.sieve.PriorityLimiter;
import dev.sieve.ShedCurve;
import dev.sieve.Sieve;
import dev.sieve.TierShares;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Demo backend with fixed capacity: WORKERS slots, each request holds one for SERVICE_MS.
 * Capacity is therefore WORKERS * 1000 / SERVICE_MS requests per second. Excess requests queue
 * for a slot, so latency climbs under overload unless the limiter sheds.
 *
 * <p>Usage: DemoServer [noshed|limiter|shed] [port]
 * <ul>
 *   <li>noshed: no protection at all
 *   <li>limiter: only the partitioned adaptive concurrency limit
 *   <li>shed: shed curves on target and max latency utilization, plus the limiter as backstop
 * </ul>
 */
public final class DemoServer {

    static final int WORKERS = 4;
    static final int SERVICE_MS = 20;
    static final int SLO_MS = 60;
    static final int MAX_MS = 300;

    public static void main(String[] args) throws IOException {
        String mode = args.length < 1 ? "shed" : args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        Sieve sieve = switch (mode) {
            case "noshed" -> null;
            case "limiter" -> Sieve.builder(
                    limiter()).build();
            case "shed" -> Sieve.builder(
                            limiter())
                    // Target: shed lower tiers, never critical, as requests pass the SLO.
                    .latencySlo(LatencySloSignal.of(Duration.ofMillis(SLO_MS)),
                            ShedCurve.prioritized(0.10, 0.40))
                    // Max: when requests approach timing out, shed everything.
                    .latencySlo(LatencySloSignal.of(Duration.ofMillis(MAX_MS)),
                            ShedCurve.unprioritized(0.10, 0.40))
                    .build();
            default -> throw new IllegalArgumentException("mode: noshed|limiter|shed");
        };
        Semaphore workers = new Semaphore(WORKERS);
        Metrics metrics = new Metrics();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 4096);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/work", ex -> handle(ex, sieve, workers, metrics));
        server.createContext("/metrics", ex -> {
            byte[] body = metrics.render().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        System.out.println("listening on " + port + " mode=" + mode
                + " capacity~" + (WORKERS * 1000 / SERVICE_MS) + "rps");
    }

    /**
     * Windowed Gradient2 sized for this backend (4 workers): start at 10, never below 4. Override
     * with SIEVE_INITIAL_LIMIT, SIEVE_MIN_LIMIT, SIEVE_MAX_LIMIT; set SIEVE_WINDOWED=0 to use
     * bare Gradient2 instead.
     */
    private static PriorityLimiter limiter() {
        int initial = intEnv("SIEVE_INITIAL_LIMIT", 10);
        int min = intEnv("SIEVE_MIN_LIMIT", 4);
        int max = intEnv("SIEVE_MAX_LIMIT", 200);
        boolean windowed = !"0".equals(System.getenv("SIEVE_WINDOWED"));
        return windowed
                ? PriorityLimiter.withWindowedGradient2(
                        TierShares.criticalFirst(), initial, min, max)
                : PriorityLimiter.withGradient2(TierShares.criticalFirst(), initial, min, max);
    }

    private static int intEnv(String name, int fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : Integer.parseInt(v);
    }

    private static void handle(
            HttpExchange ex, Sieve sieve, Semaphore workers, Metrics metrics)
            throws IOException {
        Priority priority = Priority.parse(ex.getRequestHeaders().getFirst(Priority.HEADER))
                .orElse(Priority.BEST_EFFORT);

        long start = System.nanoTime();
        Admission.Permit permit = null;
        if (sieve != null) {
            switch (sieve.admit(priority)) {
                case Admission.Shed shed -> {
                    metrics.count(priority, shed.reason() == Admission.Reason.UTILIZATION
                            ? Metrics.Outcome.SHED_UTILIZATION : Metrics.Outcome.SHED_LIMIT);
                    ex.getResponseHeaders().add(
                            "Retry-After", Long.toString(shed.retryAfter().toSeconds()));
                    ex.sendResponseHeaders(503, -1);
                    ex.close();
                    return;
                }
                case Admission.Admitted admitted -> permit = admitted.permit();
            }
        }

        try {
            workers.acquire();
            try {
                Thread.sleep(SERVICE_MS);
            } finally {
                workers.release();
            }
            ex.sendResponseHeaders(200, -1);
            metrics.served(priority, System.nanoTime() - start);
            if (permit != null) {
                permit.success();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (permit != null) {
                permit.dropped();
            }
        } finally {
            ex.close();
        }
    }
}
