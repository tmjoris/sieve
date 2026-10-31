package dev.sieve;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TierSharesTest {

    @Test
    void zeroShareIsAllowed() {
        assertDoesNotThrow(() -> TierShares.of(1.0, 0.0, 0.0, 0.0));
    }

    @Test
    void sumOverOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TierShares.of(0.6, 0.3, 0.2, 0.0));
    }

    @Test
    void negativeShareIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TierShares.of(1.0, -0.1, 0.0, 0.0));
    }

    @Test
    void shareAboveOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TierShares.of(1.5, 0.0, 0.0, 0.0));
    }

    @Test
    void presetsAreValid() {
        assertDoesNotThrow(TierShares::criticalFirst);
        assertDoesNotThrow(TierShares::balanced);
        assertDoesNotThrow(TierShares::defaults);
    }
}
