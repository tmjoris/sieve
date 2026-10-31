package dev.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static final long SEC = 1_000_000_000L;
    private final AtomicLong now = new AtomicLong(100 * SEC);
    private final RetryBudget budget = new RetryBudget(10 * SEC, 10, 20, now::get);
    private double roll = 0.5;

    private RetryPolicy policy() {
        return new RetryPolicy(2, Duration.ofMillis(100), Duration.ofSeconds(1), 0.5,
                ShedCurve.bands(0.10, 0.50, 0.90), budget, () -> roll);
    }

    private void attempts(RetryPolicy p, int n) {
        for (int i = 0; i < n; i++) {
            p.onRequest();
        }
    }

    @Test
    void retriesWhenBudgetIsEmpty() {
        RetryPolicy p = policy();
        attempts(p, 100);
        assertTrue(p.onFailure(Priority.BULK, 0, null).isPresent());
    }

    @Test
    void stopsAfterMaxRetries() {
        RetryPolicy p = policy();
        attempts(p, 100);
        assertTrue(p.onFailure(Priority.CRITICAL, 1, null).isPresent());
        assertEquals(Optional.empty(), p.onFailure(Priority.CRITICAL, 2, null));
    }

    @Test
    void doesNotRetryWhenServerAsksForLongerThanMaxDelay() {
        RetryPolicy p = policy();
        attempts(p, 100);
        assertEquals(Optional.empty(), p.onFailure(Priority.BULK, 0, Duration.ofSeconds(10)));
        assertTrue(p.onFailure(Priority.CRITICAL, 0, Duration.ofSeconds(1)).isPresent());
    }

    @Test
    void delayIsNeverShorterThanRetryAfter() {
        RetryPolicy p = policy();
        attempts(p, 100);
        roll = 0.0; // jitter would be zero
        assertEquals(Duration.ofMillis(800),
                p.onFailure(Priority.CRITICAL, 0, Duration.ofMillis(800)).orElseThrow());
    }

    @Test
    void backoffGrowsWithAttemptsAndIsCapped() {
        RetryPolicy p = new RetryPolicy(10, Duration.ofMillis(100), Duration.ofSeconds(1), 0.5,
                ShedCurve.bands(0.10, 0.50, 0.90), budget, () -> 0.999);
        attempts(p, 100);
        long first = p.onFailure(Priority.CRITICAL, 0, null).orElseThrow().toMillis();
        long third = p.onFailure(Priority.CRITICAL, 2, null).orElseThrow().toMillis();
        long ninth = p.onFailure(Priority.CRITICAL, 8, null).orElseThrow().toMillis();
        assertTrue(first <= 100 && third <= 400 && third > first);
        assertTrue(ninth <= 1000 && ninth > 900);
    }

    @Test
    void lowPriorityRetriesAreDroppedBeforeCritical() {
        RetryPolicy p = policy();
        attempts(p, 100);
        for (int i = 0; i < 30; i++) {
            budget.recordRetry(); // ratio 0.30 -> utilization 0.6
        }
        roll = 0.0;
        assertEquals(Optional.empty(), p.onFailure(Priority.BULK, 0, null));
        assertEquals(Optional.empty(), p.onFailure(Priority.BEST_EFFORT, 0, null));
        assertEquals(Optional.empty(), p.onFailure(Priority.DEGRADED, 0, null));
        // critical ramps 0.50..0.90, at 0.6 its drop probability is 0.25; a roll of 0 would
        // drop it, a higher roll lets it through
        roll = 0.5;
        assertTrue(p.onFailure(Priority.CRITICAL, 0, null).isPresent());
    }

    @Test
    void nothingRetriesWhenRetriesHalfOfAttempts() {
        RetryPolicy p = policy();
        attempts(p, 100);
        for (int i = 0; i < 60; i++) {
            budget.recordRetry();
        }
        roll = 0.999;
        for (Priority pr : Priority.values()) {
            assertEquals(Optional.empty(), p.onFailure(pr, 0, null), pr.toString());
        }
    }

    @Test
    void budgetIgnoresRatioUntilEnoughAttempts() {
        for (int i = 0; i < 5; i++) {
            budget.recordAttempt();
            budget.recordRetry();
        }
        assertEquals(0.0, budget.ratio());
    }

    @Test
    void budgetForgetsOldTraffic() {
        for (int i = 0; i < 50; i++) {
            budget.recordAttempt();
            budget.recordRetry();
        }
        assertEquals(1.0, budget.ratio());
        now.addAndGet(11 * SEC);
        assertEquals(0.0, budget.ratio());
    }
}
