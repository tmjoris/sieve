package dev.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.netflix.concurrency.limits.limit.FixedLimit;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SieveTest {

    private double util = 0;
    private double roll = 0.5;
    private final AtomicLong now = new AtomicLong(1_000_000_000_000L);

    private Sieve sieve(int limit) {
        return Sieve.builder(new PriorityLimiter(FixedLimit.of(limit), TierShares.criticalFirst()))
                .utilization(() -> util, ShedCurve.cpu(0.50))
                .clock(now::get)
                .random(() -> roll)
                .build();
    }

    private static Admission.Shed shed(Admission a) {
        return assertInstanceOf(Admission.Shed.class, a);
    }

    @Test
    void nothingIsShedBelowTheAutoscaleTarget() {
        util = 0.50;
        Sieve s = sieve(100);
        for (Priority p : Priority.values()) {
            assertInstanceOf(Admission.Admitted.class, s.admit(p));
        }
    }

    @Test
    void lowTiersAreShedFirstAsUtilizationRises() {
        util = 0.70; // bulk fully shed, best-effort partly, degraded and critical untouched
        Sieve s = sieve(100);

        Admission.Shed bulk = shed(s.admit(Priority.BULK));
        assertEquals(Admission.Reason.UTILIZATION, bulk.reason());
        assertEquals(Duration.ofSeconds(10), bulk.retryAfter());

        roll = 0.1; // below best-effort's probability (~0.5)
        shed(s.admit(Priority.BEST_EFFORT));
        roll = 0.9; // above it
        assertInstanceOf(Admission.Admitted.class, s.admit(Priority.BEST_EFFORT));

        roll = 0.0; // worst possible roll
        assertInstanceOf(Admission.Admitted.class, s.admit(Priority.DEGRADED));
        assertInstanceOf(Admission.Admitted.class, s.admit(Priority.CRITICAL));
    }

    @Test
    void criticalIsShedOnlyPastItsThreshold() {
        util = 0.90; // critical ramps 0.80..0.95, so about 0.67 here
        Sieve s = sieve(100);
        roll = 0.5;
        shed(s.admit(Priority.CRITICAL));
        roll = 0.9;
        assertInstanceOf(Admission.Admitted.class, s.admit(Priority.CRITICAL));
    }

    @Test
    void concurrencyLimitIsTheBackstop() {
        util = 0.0;
        Sieve s = sieve(1);
        assertInstanceOf(Admission.Admitted.class, s.admit(Priority.CRITICAL));
        Admission.Shed second = shed(s.admit(Priority.CRITICAL));
        assertEquals(Admission.Reason.CONCURRENCY_LIMIT, second.reason());
    }

    @Test
    void finishedRequestFeedsTheLatencySignal() {
        LatencySloSignal slo =
                new LatencySloSignal(50_000_000L, 5_000_000_000L, 10, 1, now::get);
        Sieve s = Sieve.builder(
                        new PriorityLimiter(FixedLimit.of(10), TierShares.criticalFirst()))
                .latencySlo(slo, ShedCurve.latencySlo())
                .clock(now::get)
                .random(() -> 0.5)
                .build();

        var admitted = assertInstanceOf(Admission.Admitted.class, s.admit(Priority.CRITICAL));
        now.addAndGet(200_000_000L); // took 200 ms, over the 50 ms SLO
        admitted.permit().success();
        assertEquals(1.0, slo.utilization());
    }

    @Test
    void droppedRequestCountsAsSlow() {
        LatencySloSignal slo =
                new LatencySloSignal(50_000_000L, 5_000_000_000L, 10, 1, now::get);
        Sieve s = Sieve.builder(
                        new PriorityLimiter(FixedLimit.of(10), TierShares.criticalFirst()))
                .latencySlo(slo, ShedCurve.latencySlo())
                .clock(now::get)
                .build();
        var admitted = assertInstanceOf(Admission.Admitted.class, s.admit(Priority.CRITICAL));
        admitted.permit().dropped();
        assertEquals(1.0, slo.utilization());
    }

    @Test
    void criticalIsOnlyShedWhenRequestsApproachTheTimeout() {
        // 50 ms target, 500 ms max. Requests at 200 ms breach the target but not the max.
        LatencySloSignal target =
                new LatencySloSignal(50_000_000L, 5_000_000_000L, 10, 1, now::get);
        LatencySloSignal max =
                new LatencySloSignal(500_000_000L, 5_000_000_000L, 10, 1, now::get);
        Sieve s = Sieve.builder(
                        new PriorityLimiter(FixedLimit.of(100), TierShares.criticalFirst()))
                .latencySlo(target, ShedCurve.prioritized(0.10, 0.40))
                .latencySlo(max, ShedCurve.unprioritized(0.10, 0.40))
                .clock(now::get)
                .random(() -> 0.0)
                .build();

        for (int i = 0; i < 10; i++) {
            var a = assertInstanceOf(Admission.Admitted.class, s.admit(Priority.CRITICAL));
            now.addAndGet(200_000_000L);
            a.permit().success();
        }
        assertEquals(1.0, target.utilization());
        assertEquals(0.0, max.utilization());
        shed(s.admit(Priority.BULK));
        assertInstanceOf(Admission.Admitted.class, s.admit(Priority.CRITICAL));

        // Now requests blow past the timeout threshold too.
        var batch = new java.util.ArrayList<Admission.Admitted>();
        for (int i = 0; i < 10; i++) {
            batch.add(assertInstanceOf(Admission.Admitted.class, s.admit(Priority.CRITICAL)));
        }
        now.addAndGet(900_000_000L);
        batch.forEach(a -> a.permit().success());
        shed(s.admit(Priority.CRITICAL));
    }
}
