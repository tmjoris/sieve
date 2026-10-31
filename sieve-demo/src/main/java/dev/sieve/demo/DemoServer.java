package dev.sieve.demo;

import com.netflix.concurrency.limits.Limiter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sieve.Priority;
import dev.sieve.PriorityLimiter;
import dev.sieve.TierShares;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Demo backend with fixed capacity: WORKERS slots, each request holds one for SERVICE_MS.
 * Capacity is therefore WORKERS * 1000 / SERVICE_MS requests per second. Excess requests queue
 * for a slot, so latency climbs under overload unless the limiter sheds.
 *
 * <p>Usage: DemoServer [shed|noshed] [port]
 */
public final class DemoServer {

    static final int WORKERS = 4;
    static final int SERVICE_MS = 20;

    public static void main(String[] args) throws IOException {
        boolean shed = args.length < 1 || args[0].equals("shed");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        PriorityLimiter limiter = PriorityLimiter.withGradient2(TierShares.criticalFirst());
        Semaphore workers = new Semaphore(WORKERS);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 4096);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/work", ex -> handle(ex, shed ? limiter : null, workers));
        server.start();
        System.out.println("listening on " + port + " shedding=" + shed
                + " capacity~" + (WORKERS * 1000 / SERVICE_MS) + "rps");
    }

    private static void handle(HttpExchange ex, PriorityLimiter limiter, Semaphore workers)
            throws IOException {
        Priority priority = Priority.parse(ex.getRequestHeaders().getFirst(Priority.HEADER))
                .orElse(Priority.BEST_EFFORT);

        Limiter.Listener listener = null;
        if (limiter != null) {
            Optional<Limiter.Listener> l = limiter.acquire(priority);
            if (l.isEmpty()) {
                ex.getResponseHeaders().add("Retry-After", "1");
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
            }
            listener = l.get();
        }

        try {
            workers.acquire();
            try {
                Thread.sleep(SERVICE_MS);
            } finally {
                workers.release();
            }
            ex.sendResponseHeaders(200, -1);
            if (listener != null) {
                listener.onSuccess();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (listener != null) {
                listener.onDropped();
            }
        } finally {
            ex.close();
        }
    }
}
