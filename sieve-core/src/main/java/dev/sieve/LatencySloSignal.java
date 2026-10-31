package dev.sieve;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Fraction of recent requests that took longer than a latency threshold, over a sliding window.
 * This is the utilization measure Netflix uses for IO-bound services. It reports 0 until it has
 * seen enough requests, so a cold service never sheds on a handful of samples.
 *
 * <p>The request path is cheap on both sides. Recording is lock-free (two {@link LongAdder}s).
 * Reading returns a cached value that is recomputed at most once per refresh interval, so
 * {@link #utilization()} costs a clock read and a volatile read, like Netflix's out-of-band
 * utilization files. A request racing with a bucket rollover can be counted in the neighbouring
 * bucket; that is a rounding error in a ratio over thousands of samples.
 */
public final class LatencySloSignal implements UtilizationSignal {

    private static final class Bucket {
        volatile long epoch = -1;
        final LongAdder total = new LongAdder();
        final LongAdder slow = new LongAdder();
    }

    private static final long NEVER = Long.MIN_VALUE;

    private final long sloNanos;
    private final long bucketNanos;
    private final int minSamples;
    private final long refreshNanos;
    private final LongSupplier clock;
    private final Bucket[] buckets;

    private final AtomicLong lastRefresh = new AtomicLong(NEVER);
    private volatile double cached;

    public LatencySloSignal(long sloNanos, long windowNanos, int bucketCount, int minSamples,
            long refreshNanos, LongSupplier clock) {
        this.sloNanos = sloNanos;
        this.bucketNanos = windowNanos / bucketCount;
        this.minSamples = minSamples;
        this.refreshNanos = refreshNanos;
        this.clock = clock;
        this.buckets = new Bucket[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new Bucket();
        }
    }

    /** Recomputes on every read: exact, and what the unit tests want. */
    public LatencySloSignal(long sloNanos, long windowNanos, int bucketCount, int minSamples,
            LongSupplier clock) {
        this(sloNanos, windowNanos, bucketCount, minSamples, 0, clock);
    }

    /** 10 buckets over 5 seconds, at least 20 samples, refreshed every 50 ms, real clock. */
    public static LatencySloSignal of(java.time.Duration slo) {
        return new LatencySloSignal(
                slo.toNanos(), 5_000_000_000L, 10, 20, 50_000_000L, System::nanoTime);
    }

    /** Record one finished request. Pass a very large latency for failures and timeouts. */
    public void record(long latencyNanos) {
        long epoch = clock.getAsLong() / bucketNanos;
        Bucket b = buckets[(int) Math.floorMod(epoch, (long) buckets.length)];
        if (b.epoch != epoch) {
            synchronized (b) { // once per bucket per rotation
                if (b.epoch != epoch) {
                    b.total.reset();
                    b.slow.reset();
                    b.epoch = epoch;
                }
            }
        }
        b.total.increment();
        if (latencyNanos > sloNanos) {
            b.slow.increment();
        }
    }

    @Override
    public double utilization() {
        long now = clock.getAsLong();
        if (refreshNanos > 0) {
            long last = lastRefresh.get();
            // NEVER is checked first: "now - Long.MIN_VALUE" would overflow to a negative number.
            if (last != NEVER && now - last < refreshNanos) {
                return cached;
            }
            if (!lastRefresh.compareAndSet(last, now)) {
                return cached; // another thread is refreshing
            }
        }
        double value = compute(now);
        cached = value;
        return value;
    }

    private double compute(long now) {
        long epoch = now / bucketNanos;
        long total = 0;
        long slow = 0;
        for (Bucket b : buckets) {
            long e = b.epoch;
            if (e > epoch - buckets.length && e <= epoch) {
                total += b.total.sum();
                slow += b.slow.sum();
            }
        }
        return total < minSamples ? 0 : (double) slow / total;
    }
}
