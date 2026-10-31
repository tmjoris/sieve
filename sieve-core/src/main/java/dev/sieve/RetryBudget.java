package dev.sieve;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Ratio of retries to first attempts over a sliding window, for one client talking to one
 * cluster. A low ratio means failures are scattered (one bad node) and retrying is cheap; a high
 * ratio means the cluster is overloaded and retries only add to it.
 */
public final class RetryBudget {

    private static final class Bucket {
        long epoch = -1;
        long attempts;
        long retries;
    }

    private final long bucketNanos;
    private final int minAttempts;
    private final LongSupplier clock;
    private final Bucket[] buckets;

    public RetryBudget(long windowNanos, int bucketCount, int minAttempts, LongSupplier clock) {
        this.bucketNanos = windowNanos / bucketCount;
        this.minAttempts = minAttempts;
        this.clock = clock;
        this.buckets = new Bucket[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new Bucket();
        }
    }

    /** 10 buckets over 10 seconds, ignoring the ratio until 20 first attempts were seen. */
    public static RetryBudget of(Duration window) {
        return new RetryBudget(window.toNanos(), 10, 20, System::nanoTime);
    }

    /** Count a first attempt (not a retry). */
    public void recordAttempt() {
        Bucket b = current();
        synchronized (b) {
            b.attempts++;
        }
    }

    /** Count a retry that was actually sent. */
    public void recordRetry() {
        Bucket b = current();
        synchronized (b) {
            b.retries++;
        }
    }

    /** Retries per first attempt in the window; 0 until there are enough attempts. */
    public double ratio() {
        long epoch = clock.getAsLong() / bucketNanos;
        long attempts = 0;
        long retries = 0;
        for (Bucket b : buckets) {
            synchronized (b) {
                if (b.epoch > epoch - buckets.length && b.epoch <= epoch) {
                    attempts += b.attempts;
                    retries += b.retries;
                }
            }
        }
        return attempts < minAttempts ? 0 : (double) retries / attempts;
    }

    private Bucket current() {
        long epoch = clock.getAsLong() / bucketNanos;
        Bucket b = buckets[(int) Math.floorMod(epoch, (long) buckets.length)];
        synchronized (b) {
            if (b.epoch != epoch) {
                b.epoch = epoch;
                b.attempts = 0;
                b.retries = 0;
            }
        }
        return b;
    }
}
