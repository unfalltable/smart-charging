package io.smartcharge.platform.billing;

import io.smartcharge.platform.shared.domain.DomainException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public final class TariffCalculator {
    public BillingResult calculate(PriceRule rule, Instant startedAt, Instant stoppedAt,
                                   long meterStartWh, long meterStopWh) {
        if (stoppedAt.isBefore(startedAt)) throw new DomainException("Stop time cannot be before start time");
        if (meterStartWh < 0 || meterStopWh < 0) throw new DomainException("Meter readings cannot be negative");
        if (meterStopWh < meterStartWh) throw new DomainException("Stop meter cannot be below start meter");
        try {
            long energyWh = Math.subtractExact(meterStopWh, meterStartWh);
            Duration duration = Duration.between(startedAt, stoppedAt);
            long seconds = duration.getSeconds();
            long durationMinutes = Math.max(1, Math.addExact(seconds / 60,
                    seconds % 60 != 0 || duration.getNano() != 0 ? 1 : 0));
            long calculated = switch (rule.mode()) {
                case DURATION -> Math.multiplyExact(durationMinutes, rule.durationPriceMinor());
                case ENERGY -> Math.multiplyExact(energyWh, rule.energyPriceMinor());
                case HYBRID -> Math.addExact(Math.multiplyExact(durationMinutes, rule.durationPriceMinor()),
                        Math.multiplyExact(energyWh, rule.energyPriceMinor()));
            };
            return new BillingResult(energyWh, durationMinutes, Math.max(calculated, rule.minimumAmountMinor()));
        } catch (ArithmeticException overflow) {
            throw new DomainException("Billing value exceeds the supported range");
        }
    }

    public enum Mode { DURATION, ENERGY, HYBRID }

    public record PriceRule(Mode mode, long durationPriceMinor, long energyPriceMinor, long minimumAmountMinor) {
        public PriceRule {
            java.util.Objects.requireNonNull(mode, "Tariff billing mode is required");
            if (durationPriceMinor < 0 || energyPriceMinor < 0 || minimumAmountMinor < 0) {
                throw new IllegalArgumentException("Tariff prices cannot be negative");
            }
        }
    }

    public record BillingResult(long energyWh, long durationMinutes, long amountMinor) { }
}
