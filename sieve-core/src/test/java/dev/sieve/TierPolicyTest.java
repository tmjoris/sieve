package dev.sieve;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.FixedLimit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TierPolicyTest {

    private final List<Limiter.Listener> held = new ArrayList<>();

    private boolean take(PriorityLimiter l, Priority p) {
        Optional<Limiter.Listener> r = l.acquire(p);
        r.ifPresent(held::add);
        return r.isPresent();
    }

    private void takeCritical(PriorityLimiter l, int n) {
        for (int i = 0; i < n; i++) {
            assertTrue(take(l, Priority.CRITICAL));
        }
    }

    @Test
    void validation() {
        assertDoesNotThrow(TierPolicy::none);
        assertDoesNotThrow(TierPolicy::graded);
        assertThrows(IllegalArgumentException.class,
                () -> new TierPolicy(new double[] {1.0, 0.5, 0.7, 0.4}, 0)); // best-effort above degraded
        assertThrows(IllegalArgumentException.class,
                () -> new TierPolicy(new double[] {0.9, 0.8, 0.7, 0.6}, 0)); // critical must be 1.0
        assertThrows(IllegalArgumentException.class,
                () -> new TierPolicy(new double[] {1.0, 0.8, 0.7, 0.0}, 0)); // zero threshold
        assertThrows(IllegalArgumentException.class, () -> TierPolicy.none().withCriticalFloor(-1));
        assertThrows(IllegalArgumentException.class, () -> new TierPolicy(new double[] {1, 1, 1}, 0));
    }

    @Test
    void lowerTiersGiveWayInOrderAsTheLimitFillsUp() {
        PriorityLimiter l = new PriorityLimiter(
                FixedLimit.of(10), TierShares.criticalFirst(), TierPolicy.graded());

        takeCritical(l, 4);                                   // 4 in flight
        assertTrue(take(l, Priority.BULK));                   // 4 < 5: bulk still welcome (now 5)
        assertEquals(false, take(l, Priority.BULK));          // 5 >= 50% of 10: bulk refused
        assertTrue(take(l, Priority.BEST_EFFORT));            // 5 < 7: best-effort fine (now 6)
        assertTrue(take(l, Priority.BEST_EFFORT));            // now 7
        assertEquals(false, take(l, Priority.BEST_EFFORT));   // 7 >= 70%: refused
        assertTrue(take(l, Priority.DEGRADED));               // 7 < 9 (85% rounded up): fine (now 8)
        assertTrue(take(l, Priority.DEGRADED));               // now 9
        assertEquals(false, take(l, Priority.DEGRADED));      // 9 >= 9: refused
        assertTrue(take(l, Priority.CRITICAL));               // critical still admitted (now 10)
    }

    @Test
    void thresholdsFollowTheAdaptiveLimit() {
        // limit 4: bulk is refused from 2 in flight, best-effort from 3 (70% of 4, rounded up)
        PriorityLimiter l = new PriorityLimiter(
                FixedLimit.of(4), TierShares.criticalFirst(), TierPolicy.graded());
        takeCritical(l, 2);
        assertEquals(false, take(l, Priority.BULK));
        assertTrue(take(l, Priority.BEST_EFFORT));
    }

    @Test
    void releasingRequestsReopensLowerTiers() {
        PriorityLimiter l = new PriorityLimiter(
                FixedLimit.of(10), TierShares.criticalFirst(), TierPolicy.graded());
        takeCritical(l, 5);
        assertEquals(false, take(l, Priority.BULK));
        held.get(0).onSuccess();
        assertTrue(take(l, Priority.BULK));
    }

    @Test
    void noPolicyLeavesLowerTiersToBorrowTheWholeLimit() {
        PriorityLimiter l = new PriorityLimiter(FixedLimit.of(10), TierShares.criticalFirst());
        for (int i = 0; i < 10; i++) {
            assertTrue(take(l, Priority.BULK));
        }
    }

    @Test
    void criticalFloorAdmitsCriticalWhenTheLimitIsFull() {
        PriorityLimiter noFloor = new PriorityLimiter(FixedLimit.of(2), TierShares.criticalFirst());
        takeCritical(noFloor, 2);
        assertEquals(false, take(noFloor, Priority.CRITICAL));

        held.clear();
        PriorityLimiter floored = new PriorityLimiter(FixedLimit.of(2), TierShares.criticalFirst(),
                TierPolicy.none().withCriticalFloor(1));
        takeCritical(floored, 2);                                  // limit full
        assertTrue(take(floored, Priority.CRITICAL));              // the floor slot
        assertEquals(false, take(floored, Priority.CRITICAL));     // floor used up
        held.get(2).onSuccess();                                   // release the floor slot
        assertTrue(take(floored, Priority.CRITICAL));
    }

    @Test
    void criticalFloorDoesNotHelpOtherTiers() {
        PriorityLimiter l = new PriorityLimiter(FixedLimit.of(2), TierShares.criticalFirst(),
                TierPolicy.none().withCriticalFloor(5));
        takeCritical(l, 2);
        assertTrue(take(l, Priority.BULK));        // the zero share tier's one guaranteed slot
        assertEquals(false, take(l, Priority.BULK));
    }

    @Test
    void vegasLimitWorksAsADropInReplacement() {
        PriorityLimiter l = new PriorityLimiter(Limits.windowedVegas(10, 100), TierShares.criticalFirst());
        for (int round = 0; round < 50; round++) {
            List<Limiter.Listener> batch = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                l.acquire(Priority.CRITICAL).ifPresent(batch::add);
            }
            batch.forEach(Limiter.Listener::onSuccess);
        }
        assertEquals(0, l.inflight());
        assertTrue(l.limit() >= 1);
    }
}
