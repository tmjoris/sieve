package dev.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CpuSignalTest {

    @Test
    void smoothsReadingsWithAnExponentialAverage() throws Exception {
        double[] readings = {0.2, 0.8, 0.8, 0.8};
        int[] i = {0};
        try (CpuSignal s = new CpuSignal(() -> readings[Math.min(i[0]++, 3)], 10, 0.5)) {
            Thread.sleep(200);
            // first reading taken as is, then converging towards 0.8
            assertTrue(s.utilization() > 0.7 && s.utilization() <= 0.8, "" + s.utilization());
        }
    }

    @Test
    void ignoresMissingReadings() throws Exception {
        try (CpuSignal s = new CpuSignal(() -> -1.0, 10, 0.5)) {
            Thread.sleep(50);
            assertEquals(0.0, s.utilization());
        }
    }

    @Test
    void processAndSystemSignalsStayInRange() throws Exception {
        try (CpuSignal p = CpuSignal.process(); CpuSignal sys = CpuSignal.system()) {
            Thread.sleep(600);
            assertTrue(p.utilization() >= 0 && p.utilization() <= 1, "process " + p.utilization());
            assertTrue(sys.utilization() >= 0 && sys.utilization() <= 1, "system " + sys.utilization());
        }
    }
}
