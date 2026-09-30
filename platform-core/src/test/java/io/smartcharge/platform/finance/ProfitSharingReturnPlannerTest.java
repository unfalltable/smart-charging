package io.smartcharge.platform.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProfitSharingReturnPlannerTest {
    @Test
    void refundBeforeSharingDoesNotGetReturnedTwice() {
        // 100 yuan payment, 20 refunded before sharing: the actual 30% allocation is 24 yuan.
        assertThat(ProfitSharingReturnPlanner.cumulativeReturn(10_000, 2_000, 2_400, 3_000)).isZero();
        assertThat(ProfitSharingReturnPlanner.cumulativeReturn(10_000, 3_000, 2_400, 3_000)).isEqualTo(300);
        assertThat(ProfitSharingReturnPlanner.cumulativeReturn(10_000, 10_000, 2_400, 3_000)).isEqualTo(2_400);
    }

    @Test
    void splitRefundsRecoverTheRoundingRemainderWithoutExceedingActualAllocation() {
        assertThat(ProfitSharingReturnPlanner.cumulativeReturn(101, 1, 30, 3_000)).isZero();
        assertThat(ProfitSharingReturnPlanner.cumulativeReturn(101, 2, 30, 3_000)).isEqualTo(1);
        assertThat(ProfitSharingReturnPlanner.cumulativeReturn(101, 101, 30, 3_000)).isEqualTo(30);
        assertThatThrownBy(() -> ProfitSharingReturnPlanner.cumulativeReturn(100, 101, 30, 3_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
