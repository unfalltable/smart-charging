package io.smartcharge.platform.finance;

import java.math.BigInteger;

final class SettlementCalculator {
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000);

    private SettlementCalculator() { }

    static Amounts calculate(long grossAmountMinor, int platformFeeBasisPoints,
                             long fixedServiceFeeMinor, int beneficiaryShareBasisPoints) {
        if (grossAmountMinor < 0 || fixedServiceFeeMinor < 0
                || platformFeeBasisPoints < 0 || platformFeeBasisPoints > 10_000
                || beneficiaryShareBasisPoints < 0 || beneficiaryShareBasisPoints > 10_000) {
            throw new IllegalArgumentException("Invalid settlement values");
        }
        BigInteger gross = BigInteger.valueOf(grossAmountMinor);
        BigInteger percentageFee = gross.multiply(BigInteger.valueOf(platformFeeBasisPoints))
                .divide(BASIS_POINTS);
        BigInteger platformFee = percentageFee.add(BigInteger.valueOf(fixedServiceFeeMinor)).min(gross);
        BigInteger distributable = gross.subtract(platformFee);
        BigInteger beneficiaryAmount = distributable.multiply(BigInteger.valueOf(beneficiaryShareBasisPoints))
                .divide(BASIS_POINTS);
        return new Amounts(platformFee.longValueExact(), beneficiaryAmount.longValueExact());
    }

    record Amounts(long platformServiceFeeMinor, long beneficiarySettlementMinor) { }
}
