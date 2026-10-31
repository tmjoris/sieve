package dev.sieve;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * CPU utilization sampled in the background and smoothed with an exponential moving average, so
 * reading it on the request path costs one volatile read.
 */
public final class CpuSignal implements UtilizationSignal, AutoCloseable {

    private final ScheduledExecutorService sampler;
    private final double alpha;
    private volatile double value;
    private boolean first = true;

    public CpuSignal(DoubleSupplier source, long periodMillis, double alpha) {
        this.alpha = alpha;
        this.sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sieve-cpu-sampler");
            t.setDaemon(true);
            return t;
        });
        sampler.scheduleAtFixedRate(() -> sample(source), 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    /** System-wide CPU load, sampled every 250 ms with smoothing factor 0.5. */
    public static CpuSignal system() {
        var os = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        return new CpuSignal(os::getCpuLoad, 250, 0.5);
    }

    /**
     * CPU used by this JVM process alone, as a fraction of the machine. Unlike {@link #system()}
     * it ignores other processes on the box (a load generator, a sidecar, a noisy neighbour).
     */
    public static CpuSignal process() {
        var os = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        return new CpuSignal(os::getProcessCpuLoad, 250, 0.5);
    }

    private void sample(DoubleSupplier source) {
        double v = source.getAsDouble();
        if (v < 0 || Double.isNaN(v)) {
            return; // JVM has no reading yet
        }
        value = first ? v : alpha * v + (1 - alpha) * value;
        first = false;
    }

    @Override
    public double utilization() {
        return value;
    }

    @Override
    public void close() {
        sampler.shutdownNow();
    }
}
