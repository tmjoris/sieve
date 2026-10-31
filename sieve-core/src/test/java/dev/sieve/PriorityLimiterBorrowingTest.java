package dev.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.FixedLimit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins down how Netflix's partitioned limiter shares capacity. The library only enforces a
 * partition's share once the global limit is full; below that, any tier may borrow.
 */
class PriorityLimiterBorrowingTest {

    private static final int GLOBAL_LIMIT = 10;

    // Partition limits at saturation: critical 6, degraded 2, best-effort 1, bulk 1.
    private final PriorityLimiter limiter =
            new PriorityLimiter(FixedLimit.of(GLOBAL_LIMIT), TierShares.of(0.6, 0.2, 0.1, 0.1));

    /** Acquire up to {@code n} permits for a tier, returning how many were admitted. */
    private int acquire(Priority p, int n, List<Limiter.Listener> held) {
        int admitted = 0;
        for (int i = 0; i < n; i++) {
            Optional<Limiter.Listener> l = limiter.acquire(p);
            if (l.isPresent()) {
                held.add(l.get());
                admitted++;
            }
        }
        return admitted;
    }

    @Test
    void lowTierBorrowsBeyondItsShareWhileGlobalLimitHasRoom() {
        List<Limiter.Listener> held = new ArrayList<>();
        // Bulk's share is 1 slot, but nothing else is running, so it can use all 10.
        assertEquals(GLOBAL_LIMIT, acquire(Priority.BULK, GLOBAL_LIMIT, held));
    }

    @Test
    void borrowingTierIsRejectedOnceGlobalLimitIsFull() {
        List<Limiter.Listener> held = new ArrayList<>();
        acquire(Priority.BULK, GLOBAL_LIMIT, held);
        assertEquals(0, acquire(Priority.BULK, 1, held));
    }

    @Test
    void criticalStealsCapacityFromBorrowingTierWhenSaturated() {
        List<Limiter.Listener> held = new ArrayList<>();
        acquire(Priority.BULK, GLOBAL_LIMIT, held);
        // Global limit is full of bulk work, but critical is under its share of 6.
        assertEquals(6, acquire(Priority.CRITICAL, 10, held));
    }

    @Test
    void tierIsHeldToItsShareWhenSaturated() {
        List<Limiter.Listener> held = new ArrayList<>();
        acquire(Priority.BULK, GLOBAL_LIMIT, held);
        assertEquals(2, acquire(Priority.DEGRADED, 10, held));
        assertEquals(1, acquire(Priority.BEST_EFFORT, 10, held));
    }

    @Test
    void zeroShareTierStillGetsOneSlotWhenSaturated() {
        PriorityLimiter zero =
                new PriorityLimiter(FixedLimit.of(GLOBAL_LIMIT), TierShares.criticalFirst());
        List<Limiter.Listener> held = new ArrayList<>();
        for (int i = 0; i < GLOBAL_LIMIT; i++) {
            held.add(zero.acquire(Priority.CRITICAL).orElseThrow());
        }
        int admitted = 0;
        for (int i = 0; i < 5; i++) {
            if (zero.acquire(Priority.BULK).isPresent()) {
                admitted++;
            }
        }
        assertEquals(1, admitted);
    }

    @Test
    void releasingPermitsFreesCapacity() {
        List<Limiter.Listener> held = new ArrayList<>();
        acquire(Priority.BULK, GLOBAL_LIMIT, held);
        assertEquals(0, acquire(Priority.BULK, 1, held));
        held.forEach(Limiter.Listener::onSuccess);
        held.clear();
        assertTrue(acquire(Priority.BULK, 1, held) == 1);
    }
}
