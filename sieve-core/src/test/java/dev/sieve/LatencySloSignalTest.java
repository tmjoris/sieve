package dev.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LatencySloSignalTest {

    private static final long MS = 1_000_000L;
    private final AtomicLong now = new AtomicLong(1_000_000_000_000L);
    // 50 ms SLO, 5 s window in 10 buckets, needs 20 samples.
    private final LatencySloSignal signal =
            new LatencySloSignal(50 * MS, 5_000 * MS, 10, 20, now::get);

    @Test
    void reportsZeroUntilEnoughSamples() {
        for (int i = 0; i < 19; i++) {
            signal.record(100 * MS);
        }
        assertEquals(0.0, signal.utilization());
        signal.record(100 * MS);
        assertEquals(1.0, signal.utilization());
    }

    @Test
    void reportsFractionOverSlo() {
        for (int i = 0; i < 80; i++) {
            signal.record(10 * MS);
        }
        for (int i = 0; i < 20; i++) {
            signal.record(200 * MS);
        }
        assertEquals(0.2, signal.utilization(), 1e-9);
    }

    @Test
    void oldSamplesExpireWhenWindowPasses() {
        for (int i = 0; i < 50; i++) {
            signal.record(200 * MS);
        }
        assertEquals(1.0, signal.utilization());
        now.addAndGet(6_000 * MS);
        assertEquals(0.0, signal.utilization());
    }

    @Test
    void oldBucketsAgeOutWhileNewOnesCount() {
        for (int i = 0; i < 50; i++) {
            signal.record(200 * MS); // all slow
        }
        now.addAndGet(3_000 * MS);
        for (int i = 0; i < 50; i++) {
            signal.record(10 * MS); // all fast
        }
        assertEquals(0.5, signal.utilization(), 1e-9);
        now.addAndGet(3_000 * MS); // slow batch is now older than the window
        assertEquals(0.0, signal.utilization(), 1e-9);
    }

    @Test
    void failuresCountAsSlow() {
        for (int i = 0; i < 20; i++) {
            signal.record(Long.MAX_VALUE);
        }
        assertEquals(1.0, signal.utilization());
    }
}
