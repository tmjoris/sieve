package dev.sieve.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sieve.Admission;
import dev.sieve.CpuSignal;
import dev.sieve.Limits;
import dev.sieve.TierPolicy;
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
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.FixedLimit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Demo backend with fixed capacity: {@code workers} slots, each request holds one for
 * {@code serviceMs}. Capacity is workers * 1000 / serviceMs requests per second. Excess requests
 * queue for a slot, so latency climbs under overload unless Sieve sheds.
 *
 * <p>Usage: DemoServer [noshed|fixed|limiter|shed] [port]; see {@link ServerConfig#fromEnv} for the
 * SIEVE_* environment variables.
 * <ul>
 *   <li>noshed: no protection at all
 *   <li>fixed: one static concurrency limit (SIEVE_FIXED_LIMIT, default 32) and no priorities, like
 *       a bounded request queue that fails fast when full
 *   <li>limiter: only the partitioned adaptive concurrency limit
 *   <li>shed: shed curves on target and max latency (and CPU if SIEVE_CPU_TARGET is set), plus
 *       the limiter as backstop
 * </ul>
 */
public final class DemoServer implements AutoCloseable {

    /**
     * Test hook: SIEVE_STALL_MS and SIEVE_STALL_EVERY_S freeze request processing for that long,
     * that often, like a GC pause or a CPU starvation burst would.
     */
    static volatile long stallUntilNanos;

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
        String warm = System.getenv("SIEVE_WARMUP_S");
        if (warm != null && Integer.parseInt(warm) > 0) {
            warmUp(c, Integer.parseInt(warm));
        }
        start(c);
        System.out.println("listening on " + port + " mode=" + mode + " backend=" + c.backend()
                + " capacity~" + (c.workers() * 1000 / c.serviceMs()) + "rps");
    }

    /**
     * Serve a short synthetic load on a throwaway instance before the real port opens, so the JIT
     * has compiled the request path when real traffic arrives. Without this the first seconds are
     * slow, the latency signals read high, and the lower tiers are shed. The throwaway server is
     * discarded, so its samples do not leak into the real one's signals or metrics.
     */
    static void warmUp(ServerConfig c, int seconds) {
        try (DemoServer s = start(c.withPort(0))) {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
            LoadProbe.run(client, s.port(), 150, seconds, "none");
            LoadProbe.run(client, s.port(), 400, 1, "none"); // overload, to warm the shed paths
        } catch (Exception e) {
            System.err.println("warm-up failed: " + e);
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    static DemoServer start(ServerConfig c) throws IOException {
        CpuSignal cpu = null;
        PriorityLimiter lim = switch (c.mode()) {
            case "limiter", "shed" -> limiter(c);
            default -> null;
        };
        Sieve sieve = switch (c.mode()) {
            case "noshed", "fixed" -> null;
            case "limiter" -> Sieve.builder(lim).build();
            case "shed" -> {
                Sieve.Builder b = Sieve.builder(lim);
                // SIEVE_LATENCY=0 turns latency shedding off, to test the CPU signal alone.
                if (!"0".equals(System.getenv("SIEVE_LATENCY"))) {
                    // Target: shed lower tiers, never critical, as requests pass the SLO.
                    Duration window = Duration.ofMillis(Long.parseLong(
                            System.getenv().getOrDefault("SIEVE_SIGNAL_WINDOW_MS", "5000")));
                    b.latencySlo(LatencySloSignal.of(Duration.ofMillis(c.targetMs()), window),
                            ShedCurve.prioritized(c.targetLo(), c.targetHi()));
                    // Max: when requests approach timing out, shed everything.
                    b.latencySlo(LatencySloSignal.of(Duration.ofMillis(c.maxMs()), window),
                            ShedCurve.unprioritized(c.maxLo(), c.maxHi()));
                }
                if (c.cpuAutoscaleTarget() != null) {
                    // SIEVE_CPU_SCOPE=process ignores other processes on the machine.
                    cpu = "process".equals(System.getenv("SIEVE_CPU_SCOPE"))
                            ? CpuSignal.process() : CpuSignal.system();
                    b.utilization(cpu, ShedCurve.cpu(c.cpuAutoscaleTarget()));
                }
                yield b.build();
            }
            default -> throw new IllegalArgumentException("mode: noshed|fixed|limiter|shed");
        };

        Limiter<Void> fixed = c.mode().equals("fixed")
                ? SimpleLimiter.newBuilder().limit(FixedLimit.of(
                        System.getenv("SIEVE_FIXED_LIMIT") == null ? 32
                                : Integer.parseInt(System.getenv("SIEVE_FIXED_LIMIT")))).build()
                : null;
        Semaphore workers = new Semaphore(c.workers());
        Metrics metrics = new Metrics();
        metrics.limiter(lim);
        // CPU-burning handlers must not run on virtual threads: they would pin the carriers.
        ExecutorService executor = c.backend().equals("cpu")
                ? Executors.newCachedThreadPool() : Executors.newVirtualThreadPerTaskExecutor();

        HttpServer server = HttpServer.create(new InetSocketAddress(c.port()), 4096);
        server.setExecutor(executor);
        server.createContext("/work", ex -> handle(ex, c, sieve, fixed, workers, metrics));
        server.createContext("/metrics", ex -> {
            byte[] body = metrics.render().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        String stallMs = System.getenv("SIEVE_STALL_MS");
        if (stallMs != null && Integer.parseInt(stallMs) > 0) {
            long every = Long.parseLong(System.getenv().getOrDefault("SIEVE_STALL_EVERY_S", "8"));
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(every * 1000);
                        stallUntilNanos = System.nanoTime() + Long.parseLong(stallMs) * 1_000_000L;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "sieve-stall-injector");
            t.setDaemon(true);
            t.start();
        }
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

    /**
     * The adaptive limiter. SIEVE_LIMIT_ALGO picks gradient2 (default) or vegas, SIEVE_TIERS=graded
     * adds the graded tier thresholds, and SIEVE_CRITICAL_FLOOR reserves slots for critical.
     */
    private static PriorityLimiter limiter(ServerConfig c) {
        String algo = System.getenv("SIEVE_LIMIT_ALGO") == null ? "gradient2"
                : System.getenv("SIEVE_LIMIT_ALGO");
        com.netflix.concurrency.limits.Limit limit = algo.equals("vegas")
                ? Limits.windowedVegas(c.initialLimit(), c.maxLimit())
                : c.windowed()
                        ? Limits.windowedGradient2(c.initialLimit(), c.minLimit(), c.maxLimit())
                        : com.netflix.concurrency.limits.limit.Gradient2Limit.newBuilder()
                                .initialLimit(c.initialLimit()).minLimit(c.minLimit())
                                .maxConcurrency(c.maxLimit()).build();
        TierPolicy policy = "graded".equals(System.getenv("SIEVE_TIERS"))
                ? TierPolicy.graded() : TierPolicy.none();
        // SIEVE_TIER_THRESHOLDS="1.0,0.85,0.70,0.50" overrides the graded fractions of the limit
        // (critical, degraded, best-effort, bulk).
        String custom = System.getenv("SIEVE_TIER_THRESHOLDS");
        if (custom != null) {
            policy = new TierPolicy(
                    java.util.Arrays.stream(custom.split(",")).mapToDouble(Double::parseDouble).toArray(), 0);
        }
        String floor = System.getenv("SIEVE_CRITICAL_FLOOR");
        if (floor != null) {
            policy = policy.withCriticalFloor(Integer.parseInt(floor));
        }
        return new PriorityLimiter(limit, TierShares.criticalFirst(), policy);
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

    private static void handle(HttpExchange ex, ServerConfig c, Sieve sieve,
            Limiter<Void> fixed, Semaphore workers, Metrics metrics) throws IOException {
        Priority priority = Priority.parse(ex.getRequestHeaders().getFirst(Priority.HEADER))
                .orElse(Priority.BEST_EFFORT);

        long start = System.nanoTime();
        metrics.arrived(priority);
        Admission.Permit permit = null;
        if (fixed != null) {
            Optional<Limiter.Listener> l = fixed.acquire(null);
            if (l.isEmpty()) {
                metrics.count(priority, Metrics.Outcome.SHED_LIMIT);
                ex.getResponseHeaders().add("Retry-After", "1");
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
            }
            Limiter.Listener listener = l.get();
            permit = new Admission.Permit() {
                public void success() { listener.onSuccess(); }
                public void dropped() { listener.onDropped(); }
                public void ignore() { listener.onIgnore(); }
            };
        } else if (sieve != null) {
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
            long stall = stallUntilNanos - System.nanoTime();
            if (stall > 0) {
                Thread.sleep(stall / 1_000_000L, (int) (stall % 1_000_000L));
            }
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
