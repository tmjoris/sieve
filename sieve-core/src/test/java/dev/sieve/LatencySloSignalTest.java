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

    @Test
    void cachedReadsOnlyRefreshAfterTheInterval() {
        // refresh every 100 ms
        LatencySloSignal cachedSignal =
                new LatencySloSignal(50 * MS, 5_000 * MS, 10, 1, 100 * MS, now::get);
        cachedSignal.record(200 * MS);
        assertEquals(1.0, cachedSignal.utilization()); // first read computes
        for (int i = 0; i < 50; i++) {
            cachedSignal.record(10 * MS); // now 1 slow of 51
        }
        assertEquals(1.0, cachedSignal.utilization(), "still the cached value");
        now.addAndGet(100 * MS);
        assertEquals(1.0 / 51, cachedSignal.utilization(), 1e-9);
    }

    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        LatencySloSignal s = new LatencySloSignal(50 * MS, 5_000 * MS, 10, 1, now::get);
        int threads = 8;
        int each = 50_000;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var done = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < each; i++) {
                    s.record(i % 4 == 0 ? 200 * MS : 10 * MS); // a quarter are slow
                }
                done.countDown();
            });
        }
        done.await();
        pool.shutdown();
        assertEquals(0.25, s.utilization(), 1e-9);
    }
}
