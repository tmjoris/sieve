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
 *   <li>shed: shed curve on latency-SLO utilization, plus the limiter as backstop
 * </ul>
 */
public final class DemoServer {

    static final int WORKERS = 4;
    static final int SERVICE_MS = 20;
    static final int SLO_MS = 60;

    public static void main(String[] args) throws IOException {
        String mode = args.length < 1 ? "shed" : args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        Sieve sieve = switch (mode) {
            case "noshed" -> null;
            case "limiter" -> Sieve.builder(
                    PriorityLimiter.withGradient2(TierShares.criticalFirst())).build();
            case "shed" -> Sieve.builder(
                            PriorityLimiter.withGradient2(TierShares.criticalFirst()))
                    .latencySlo(LatencySloSignal.of(Duration.ofMillis(SLO_MS)),
                            ShedCurve.latencySlo())
                    .build();
            default -> throw new IllegalArgumentException("mode: noshed|limiter|shed");
        };
        Semaphore workers = new Semaphore(WORKERS);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 4096);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/work", ex -> handle(ex, sieve, workers));
        server.start();
        System.out.println("listening on " + port + " mode=" + mode
                + " capacity~" + (WORKERS * 1000 / SERVICE_MS) + "rps");
    }

    private static void handle(HttpExchange ex, Sieve sieve, Semaphore workers)
            throws IOException {
        Priority priority = Priority.parse(ex.getRequestHeaders().getFirst(Priority.HEADER))
                .orElse(Priority.BEST_EFFORT);

        Admission.Permit permit = null;
        if (sieve != null) {
            switch (sieve.admit(priority)) {
                case Admission.Shed shed -> {
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
