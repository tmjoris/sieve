package dev.sieve.demo;

/**
 * Everything that shapes a demo server. {@code mode} is noshed, limiter or shed. {@code backend}
 * is "sleep" (IO-like: a request holds a worker slot while sleeping) or "cpu" (a request burns CPU
 * on a worker slot).
 */
record ServerConfig(
        String mode,
        int port,
        String backend,
        int workers,
        int serviceMs,
        int targetMs,
        double targetLo,
        double targetHi,
        int maxMs,
        double maxLo,
        double maxHi,
        int initialLimit,
        int minLimit,
        int maxLimit,
        boolean windowed,
        Double cpuAutoscaleTarget) {

    static ServerConfig defaults(String mode, int port) {
        return new ServerConfig(mode, port, "sleep", 4, 20, 60, 0.10, 0.40, 300, 0.10, 0.40,
                10, 4, 200, true, null);
    }

    /** Defaults overridden by SIEVE_* environment variables. */
    static ServerConfig fromEnv(String mode, int port) {
        ServerConfig d = defaults(mode, port);
        String cpu = System.getenv("SIEVE_CPU_TARGET");
        return new ServerConfig(mode, port,
                str("SIEVE_BACKEND", d.backend), integer("SIEVE_WORKERS", d.workers),
                integer("SIEVE_SERVICE_MS", d.serviceMs), integer("SIEVE_TARGET_MS", d.targetMs),
                dbl("SIEVE_TARGET_LO", d.targetLo), dbl("SIEVE_TARGET_HI", d.targetHi),
                integer("SIEVE_MAX_MS", d.maxMs), dbl("SIEVE_MAX_LO", d.maxLo),
                dbl("SIEVE_MAX_HI", d.maxHi), integer("SIEVE_INITIAL_LIMIT", d.initialLimit),
                integer("SIEVE_MIN_LIMIT", d.minLimit), integer("SIEVE_MAX_LIMIT", d.maxLimit),
                !"0".equals(System.getenv("SIEVE_WINDOWED")),
                cpu == null ? null : Double.parseDouble(cpu));
    }

    ServerConfig withCurves(int targetMs, double targetLo, double targetHi, int maxMs,
            double maxLo, double maxHi) {
        return new ServerConfig(mode, port, backend, workers, serviceMs, targetMs, targetLo,
                targetHi, maxMs, maxLo, maxHi, initialLimit, minLimit, maxLimit, windowed,
                cpuAutoscaleTarget);
    }

    ServerConfig withPort(int newPort) {
        return new ServerConfig(mode, newPort, backend, workers, serviceMs, targetMs, targetLo,
                targetHi, maxMs, maxLo, maxHi, initialLimit, minLimit, maxLimit, windowed,
                cpuAutoscaleTarget);
    }

    private static String str(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v;
    }

    private static int integer(String name, int fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : Integer.parseInt(v);
    }

    private static double dbl(String name, double fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : Double.parseDouble(v);
    }
}
