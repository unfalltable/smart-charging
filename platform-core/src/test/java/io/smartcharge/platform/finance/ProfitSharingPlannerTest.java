package io.smartcharge.platform.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.shared.domain.DomainException;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ProfitSharingPlannerTest {
    @Test
    @SuppressWarnings("unchecked")
    void snapshotsEveryApprovedReceiverForThePayment() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("profit_sharing_required"), eq(Boolean.class),
                eq(tenantId), eq("WECHAT"))).thenReturn(true);

        ResultSet platform = receiver(UUID.randomUUID(), 500, "PLATFORM", null,
                "1900000100", "Platform Company");
        ResultSet partner = receiver(UUID.randomUUID(), 1_000, "ORGANIZATION", UUID.randomUUID(),
                "1900000200", "Regional Partner");
        when(jdbc.query(contains("from profit_sharing_policy"), any(RowMapper.class),
                eq(tenantId), eq(organizationId), eq("WECHAT"), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(platform, 0), mapper.mapRow(partner, 1));
                });

        boolean planned = new ProfitSharingPlanner(jdbc, 3_000)
                .createPlan(tenantId, paymentId, organizationId, "WECHAT", 10_001);

        assertThat(planned).isTrue();
        verify(jdbc).update(contains("insert into payment_profit_sharing_order"), any(Object[].class));
        verify(jdbc, times(2)).update(contains("insert into payment_profit_sharing_detail"), any(Object[].class));
    }

    @Test
    void refusesPaymentWhenMandatorySharingHasNoActivePolicy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("profit_sharing_required"), eq(Boolean.class),
                eq(tenantId), eq("WECHAT"))).thenReturn(true);
        when(jdbc.query(contains("from profit_sharing_policy"), any(RowMapper.class),
                eq(tenantId), eq(organizationId), eq("WECHAT"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> new ProfitSharingPlanner(jdbc, 3_000)
                .createPlan(tenantId, UUID.randomUUID(), organizationId, "WECHAT", 10_000))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("no active policy");
    }

    @Test
    void rejectsAConfiguredLimitAboveOneHundredPercent() {
        assertThatThrownBy(() -> new ProfitSharingPlanner(mock(JdbcTemplate.class), 10_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 10000");
    }

    private static ResultSet receiver(UUID receiverId, int basisPoints, String ownerType,
                                      UUID organizationId, String account, String name) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("receiver_id", UUID.class)).thenReturn(receiverId);
        when(result.getInt("basis_points")).thenReturn(basisPoints);
        when(result.getString("owner_type")).thenReturn(ownerType);
        when(result.getObject("organization_id", UUID.class)).thenReturn(organizationId);
        when(result.getString("receiver_type")).thenReturn("MERCHANT_ID");
        when(result.getString("receiver_account")).thenReturn(account);
        when(result.getString("receiver_name")).thenReturn(name);
        return result;
    }
}
