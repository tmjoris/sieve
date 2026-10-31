package dev.sieve;

import java.util.function.LongSupplier;

/**
 * Fraction of recent requests that took longer than a latency SLO, over a sliding window.
 * This is the utilization measure Netflix uses for IO-bound services. It reports 0 until it has
 * seen enough requests, so a cold service never sheds on a handful of samples.
 */
public final class LatencySloSignal implements UtilizationSignal {

    private static final class Bucket {
        long epoch = -1;
        long total;
        long slow;
    }

    private final long sloNanos;
    private final long bucketNanos;
    private final int minSamples;
    private final LongSupplier clock;
    private final Bucket[] buckets;

    public LatencySloSignal(long sloNanos, long windowNanos, int bucketCount, int minSamples,
            LongSupplier clock) {
        this.sloNanos = sloNanos;
        this.bucketNanos = windowNanos / bucketCount;
        this.minSamples = minSamples;
        this.clock = clock;
        this.buckets = new Bucket[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new Bucket();
        }
    }

    /** 10 buckets over 5 seconds, at least 20 samples, real clock. */
    public static LatencySloSignal of(java.time.Duration slo) {
        return new LatencySloSignal(slo.toNanos(), 5_000_000_000L, 10, 20, System::nanoTime);
    }

    /** Record one finished request. Pass a very large latency for failures and timeouts. */
    public void record(long latencyNanos) {
        long epoch = clock.getAsLong() / bucketNanos;
        Bucket b = buckets[(int) Math.floorMod(epoch, (long) buckets.length)];
        synchronized (b) {
            if (b.epoch != epoch) {
                b.epoch = epoch;
                b.total = 0;
                b.slow = 0;
            }
            b.total++;
            if (latencyNanos > sloNanos) {
                b.slow++;
            }
        }
    }

    @Override
    public double utilization() {
        long epoch = clock.getAsLong() / bucketNanos;
        long total = 0;
        long slow = 0;
        for (Bucket b : buckets) {
            synchronized (b) {
                if (b.epoch > epoch - buckets.length && b.epoch <= epoch) {
                    total += b.total;
                    slow += b.slow;
                }
            }
        }
        return total < minSamples ? 0 : (double) slow / total;
    }
}
