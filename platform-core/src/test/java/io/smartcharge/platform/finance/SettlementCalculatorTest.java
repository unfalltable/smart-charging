package io.smartcharge.platform.finance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {
    @Test
    void deductsPlatformFeesBeforeApplyingTheBeneficiaryShare() {
        var amounts = SettlementCalculator.calculate(100_000, 500, 1_000, 8_000);

        assertThat(amounts.platformServiceFeeMinor()).isEqualTo(6_000);
        assertThat(amounts.beneficiarySettlementMinor()).isEqualTo(75_200);
    }

    @Test
    void capsPlatformFeesAtGrossRevenue() {
        var amounts = SettlementCalculator.calculate(500, 1_000, 1_000, 10_000);

        assertThat(amounts.platformServiceFeeMinor()).isEqualTo(500);
        assertThat(amounts.beneficiarySettlementMinor()).isZero();
    }

    @Test
    void handlesMaximumLongGrossWithoutOverflow() {
        var amounts = SettlementCalculator.calculate(Long.MAX_VALUE, 10_000, Long.MAX_VALUE, 10_000);

        assertThat(amounts.platformServiceFeeMinor()).isEqualTo(Long.MAX_VALUE);
        assertThat(amounts.beneficiarySettlementMinor()).isZero();
    }
}
