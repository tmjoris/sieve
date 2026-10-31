package dev.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShedCurveTest {

    @Test
    void cpuCurveMatchesNetflixExampleForFiftyPercentTarget() {
        ShedCurve c = ShedCurve.cpu(0.50);
        assertEquals(0.60, c.ramp(Priority.BULK).start(), 1e-9);
        assertEquals(0.80, c.ramp(Priority.CRITICAL).start(), 1e-9);
        // Nothing is shed at or below the autoscaling target.
        for (Priority p : Priority.values()) {
            assertEquals(0, c.probability(p, 0.50));
        }
    }

    @Test
    void rampIsLinearBetweenStartAndFull() {
        ShedCurve c = ShedCurve.bands(0.0, 0.6, 1.0);
        // Bulk ramps 0.0 .. 0.2
        assertEquals(0.5, c.probability(Priority.BULK, 0.1), 1e-9);
        assertEquals(1.0, c.probability(Priority.BULK, 0.2), 1e-9);
    }

    @Test
    void lowerTierIsFullyShedBeforeHigherTierIsAtRisk() {
        ShedCurve c = ShedCurve.cpu(0.50);
        Priority[] order = Priority.values();
        for (double u = 0; u <= 1.0; u += 0.001) {
            for (int i = 0; i < order.length - 1; i++) {
                double higher = c.probability(order[i], u);
                double lower = c.probability(order[i + 1], u);
                assertTrue(higher <= lower, "higher tier shed more than lower at u=" + u);
                if (higher > 0) {
                    assertEquals(1.0, lower, 1e-9, "lower tier not fully shed at u=" + u);
                }
            }
        }
    }

    @Test
    void probabilityNeverDecreasesAsUtilizationRises() {
        ShedCurve c = ShedCurve.latencySlo();
        for (Priority p : Priority.values()) {
            double prev = 0;
            for (double u = 0; u <= 1.0; u += 0.001) {
                double now = c.probability(p, u);
                assertTrue(now >= prev - 1e-12);
                prev = now;
            }
        }
    }

    @Test
    void everythingIsShedAtFullUtilization() {
        for (Priority p : Priority.values()) {
            assertEquals(1.0, ShedCurve.cpu(0.5).probability(p, 1.0));
        }
    }

    @Test
    void overlappingRampsAreRejected() {
        Map<Priority, ShedCurve.Ramp> m = new EnumMap<>(Priority.class);
        m.put(Priority.CRITICAL, new ShedCurve.Ramp(0.5, 0.9));
        m.put(Priority.DEGRADED, new ShedCurve.Ramp(0.4, 0.6)); // full 0.6 > critical start 0.5
        m.put(Priority.BEST_EFFORT, new ShedCurve.Ramp(0.2, 0.3));
        m.put(Priority.BULK, new ShedCurve.Ramp(0.1, 0.2));
        assertThrows(IllegalArgumentException.class, () -> new ShedCurve(m));
    }

    @Test
    void invalidRampIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShedCurve.Ramp(0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new ShedCurve.Ramp(-0.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new ShedCurve.Ramp(0.5, 1.1));
    }

    @Test
    void cpuCurveRejectsTargetThatLeavesNoRoom() {
        assertThrows(IllegalArgumentException.class, () -> ShedCurve.cpu(0.75));
    }
}
