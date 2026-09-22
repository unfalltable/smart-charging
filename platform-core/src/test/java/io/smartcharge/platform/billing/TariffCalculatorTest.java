package io.smartcharge.platform.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.smartcharge.platform.billing.TariffCalculator.Mode;
import io.smartcharge.platform.billing.TariffCalculator.PriceRule;
import io.smartcharge.platform.shared.domain.DomainException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TariffCalculatorTest {
    private final TariffCalculator calculator = new TariffCalculator();
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void roundsDurationUpByStartedMinute() {
        var result = calculator.calculate(new PriceRule(Mode.DURATION, 2, 0, 1),
                start, start.plusSeconds(61), 1000, 1100);
        assertEquals(2, result.durationMinutes());
        assertEquals(4, result.amountMinor());
    }

    @Test
    void calculatesHybridAndMinimumAmount() {
        var result = calculator.calculate(new PriceRule(Mode.HYBRID, 1, 2, 500),
                start, start.plusSeconds(60), 1000, 1100);
        assertEquals(500, result.amountMinor());
    }

    @Test
    void rejectsMeterRollback() {
        assertThrows(DomainException.class, () -> calculator.calculate(
                new PriceRule(Mode.ENERGY, 0, 1, 0), start, start.plusSeconds(1), 1000, 999));
    }
}
