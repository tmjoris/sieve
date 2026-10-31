package dev.sieve.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sieve.Admission;
import dev.sieve.CpuSignal;
import dev.sieve.LatencySloSignal;
import dev.sieve.Priority;
import dev.sieve.PriorityLimiter;
import dev.sieve.ShedCurve;
import dev.sieve.Sieve;
import dev.sieve.TierShares;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Demo backend with fixed capacity: {@code workers} slots, each request holds one for
 * {@code serviceMs}. Capacity is workers * 1000 / serviceMs requests per second. Excess requests
 * queue for a slot, so latency climbs under overload unless Sieve sheds.
 *
 * <p>Usage: DemoServer [noshed|limiter|shed] [port]; see {@link ServerConfig#fromEnv} for the
 * SIEVE_* environment variables.
 * <ul>
 *   <li>noshed: no protection at all
 *   <li>limiter: only the partitioned adaptive concurrency limit
 *   <li>shed: shed curves on target and max latency (and CPU if SIEVE_CPU_TARGET is set), plus
 *       the limiter as backstop
 * </ul>
 */
public final class DemoServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final CpuSignal cpu;
    final Metrics metrics;
    final ServerConfig config;

    private DemoServer(HttpServer server, ExecutorService executor, CpuSignal cpu,
            Metrics metrics, ServerConfig config) {
        this.server = server;
        this.executor = executor;
        this.cpu = cpu;
        this.metrics = metrics;
        this.config = config;
    }

    public static void main(String[] args) throws IOException {
        String mode = args.length < 1 ? "shed" : args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        ServerConfig c = ServerConfig.fromEnv(mode, port);
        start(c);
        System.out.println("listening on " + port + " mode=" + mode + " backend=" + c.backend()
                + " capacity~" + (c.workers() * 1000 / c.serviceMs()) + "rps");
    }

    static DemoServer start(ServerConfig c) throws IOException {
        CpuSignal cpu = null;
        Sieve sieve = switch (c.mode()) {
            case "noshed" -> null;
            case "limiter" -> Sieve.builder(limiter(c)).build();
            case "shed" -> {
                Sieve.Builder b = Sieve.builder(limiter(c));
                // SIEVE_LATENCY=0 turns latency shedding off, to test the CPU signal alone.
                if (!"0".equals(System.getenv("SIEVE_LATENCY"))) {
                    // Target: shed lower tiers, never critical, as requests pass the SLO.
                    b.latencySlo(LatencySloSignal.of(Duration.ofMillis(c.targetMs())),
                            ShedCurve.prioritized(c.targetLo(), c.targetHi()));
                    // Max: when requests approach timing out, shed everything.
                    b.latencySlo(LatencySloSignal.of(Duration.ofMillis(c.maxMs())),
                            ShedCurve.unprioritized(c.maxLo(), c.maxHi()));
                }
                if (c.cpuAutoscaleTarget() != null) {
                    cpu = CpuSignal.system();
                    b.utilization(cpu, ShedCurve.cpu(c.cpuAutoscaleTarget()));
                }
                yield b.build();
            }
            default -> throw new IllegalArgumentException("mode: noshed|limiter|shed");
        };

        Semaphore workers = new Semaphore(c.workers());
        Metrics metrics = new Metrics();
        // CPU-burning handlers must not run on virtual threads: they would pin the carriers.
        ExecutorService executor = c.backend().equals("cpu")
                ? Executors.newCachedThreadPool() : Executors.newVirtualThreadPerTaskExecutor();

        HttpServer server = HttpServer.create(new InetSocketAddress(c.port()), 4096);
        server.setExecutor(executor);
        server.createContext("/work", ex -> handle(ex, c, sieve, workers, metrics));
        server.createContext("/metrics", ex -> {
            byte[] body = metrics.render().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return new DemoServer(server, executor, cpu, metrics, c);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        if (cpu != null) {
            cpu.close();
        }
    }

    private static PriorityLimiter limiter(ServerConfig c) {
        return c.windowed()
                ? PriorityLimiter.withWindowedGradient2(
                        TierShares.criticalFirst(), c.initialLimit(), c.minLimit(), c.maxLimit())
                : PriorityLimiter.withGradient2(
                        TierShares.criticalFirst(), c.initialLimit(), c.minLimit(), c.maxLimit());
    }

    private static void work(ServerConfig c) throws InterruptedException {
        if (c.backend().equals("cpu")) {
            long end = System.nanoTime() + c.serviceMs() * 1_000_000L;
            long x = 0;
            while (System.nanoTime() < end) {
                x += Long.numberOfTrailingZeros(x + 1);
            }
            if (x == 42) {
                System.out.print(""); // keep the loop from being optimized away
            }
        } else {
            Thread.sleep(c.serviceMs());
        }
    }

    private static void handle(HttpExchange ex, ServerConfig c, Sieve sieve, Semaphore workers,
            Metrics metrics) throws IOException {
        Priority priority = Priority.parse(ex.getRequestHeaders().getFirst(Priority.HEADER))
                .orElse(Priority.BEST_EFFORT);

        long start = System.nanoTime();
        metrics.arrived(priority);
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
                work(c);
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
