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
        if (meterStopWh < meterStartWh) throw new DomainException("Stop meter cannot be below start meter");
        try {
            long energyWh = Math.subtractExact(meterStopWh, meterStartWh);
            long seconds = Math.max(1, Duration.between(startedAt, stoppedAt).getSeconds());
            long durationMinutes = Math.max(1, Math.addExact(seconds, 59) / 60);
            long durationAmount = Math.multiplyExact(durationMinutes, rule.durationPriceMinor());
            long energyAmount = Math.multiplyExact(energyWh, rule.energyPriceMinor());
            long calculated = switch (rule.mode()) {
                case DURATION -> durationAmount;
                case ENERGY -> energyAmount;
                case HYBRID -> Math.addExact(durationAmount, energyAmount);
            };
            return new BillingResult(energyWh, durationMinutes, Math.max(calculated, rule.minimumAmountMinor()));
        } catch (ArithmeticException overflow) {
            throw new DomainException("Billing value exceeds the supported range");
        }
    }

    public enum Mode { DURATION, ENERGY, HYBRID }

    public record PriceRule(Mode mode, long durationPriceMinor, long energyPriceMinor, long minimumAmountMinor) {
        public PriceRule {
            if (durationPriceMinor < 0 || energyPriceMinor < 0 || minimumAmountMinor < 0) {
                throw new IllegalArgumentException("Tariff prices cannot be negative");
            }
        }
    }

    public record BillingResult(long energyWh, long durationMinutes, long amountMinor) { }
}
