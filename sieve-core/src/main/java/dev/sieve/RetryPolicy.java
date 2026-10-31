package dev.sieve;

import java.time.Duration;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side counterpart to shedding. A failed call is retried only if all of these hold:
 *
 * <ul>
 *   <li>fewer than {@code maxRetries} retries were already made;
 *   <li>the server's Retry-After hint is not longer than we are willing to wait;
 *   <li>the retry budget lets it through: as the ratio of retries to first attempts rises, retries
 *       of low-priority calls are dropped first and critical ones last, mirroring server-side
 *       shedding (Netflix's "prioritized attempt budget").
 * </ul>
 *
 * The delay is full-jitter exponential backoff, and never shorter than Retry-After.
 */
public final class RetryPolicy {

    private final int maxRetries;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double saturationRatio;
    private final ShedCurve throttle;
    private final RetryBudget budget;
    private final DoubleSupplier random;

    public RetryPolicy(int maxRetries, Duration baseDelay, Duration maxDelay,
            double saturationRatio, ShedCurve throttle, RetryBudget budget, DoubleSupplier random) {
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.saturationRatio = saturationRatio;
        this.throttle = throttle;
        this.budget = budget;
        this.random = random;
    }

    /**
     * Defaults from the Netflix re:Invent 2024 talk: at most 2 retries, full-jitter exponential
     * backoff from 100 ms up to 1 s. The budget starts dropping bulk retries when retries reach
     * 5% of first attempts and has dropped them all at 50%.
     */
    public static RetryPolicy defaults() {
        return new RetryPolicy(2, Duration.ofMillis(100), Duration.ofSeconds(1), 0.5,
                ShedCurve.bands(0.10, 0.50, 0.90), RetryBudget.of(Duration.ofSeconds(10)),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Call once per original request, before its first attempt. */
    public void onRequest() {
        budget.recordAttempt();
    }

    /**
     * Decide whether to retry a call that was shed or failed.
     *
     * @param retriesDone retries already made for this request (0 after the first failure)
     * @param retryAfter the server's hint, or null/zero if none
     * @return how long to wait before retrying, or empty to give up
     */
    public Optional<Duration> onFailure(Priority priority, int retriesDone, Duration retryAfter) {
        if (retriesDone >= maxRetries) {
            return Optional.empty();
        }
        Duration hint = retryAfter == null ? Duration.ZERO : retryAfter;
        if (hint.compareTo(maxDelay) > 0) {
            return Optional.empty();
        }
        double utilization = Math.min(1.0, budget.ratio() / saturationRatio);
        if (random.getAsDouble() < throttle.probability(priority, utilization)) {
            return Optional.empty();
        }
        budget.recordRetry();

        double cap = Math.min(maxDelay.toNanos(), baseDelay.toNanos() * Math.pow(2, retriesDone));
        Duration jittered = Duration.ofNanos((long) (random.getAsDouble() * cap));
        return Optional.of(jittered.compareTo(hint) < 0 ? hint : jittered);
    }
}
