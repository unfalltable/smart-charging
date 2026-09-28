package io.smartcharge.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OperationsDashboardControllerTest {
    @Test
    void reportsNetCashReceiptsWithoutCountingPreauthorizations() throws Exception {
        UUID tenantId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);
        when(tenantJdbc.readWrite(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());

        ResultSet devices = mock(ResultSet.class);
        when(devices.getLong("online")).thenReturn(4L);
        when(devices.getLong("total")).thenReturn(5L);
        when(jdbc.queryForObject(contains("from device where tenant_id"),
                org.mockito.ArgumentMatchers.<RowMapper<OperationsDashboardController.DeviceCounts>>any(),
                eq(tenantId))).thenAnswer(invocation ->
                invocation.<RowMapper<OperationsDashboardController.DeviceCounts>>getArgument(1)
                        .mapRow(devices, 0));
        when(jdbc.queryForObject(contains("from connector"), eq(Long.class), eq(tenantId))).thenReturn(7L);
        when(jdbc.queryForObject(contains("from charging_order"), eq(Long.class), eq(tenantId))).thenReturn(2L);
        when(jdbc.queryForObject(contains("refund_transaction"), eq(Long.class), eq(tenantId),
                any(Timestamp.class), eq(tenantId), any(Timestamp.class))).thenReturn(850L);

        TenantContext.set(tenantId);
        try {
            var summary = new OperationsDashboardController(jdbc, tenantJdbc).dashboard();
            assertThat(summary.todayRevenueMinor()).isEqualTo(850L);
            assertThat(summary.onlineDevices()).isEqualTo(4L);
        } finally {
            TenantContext.clear();
        }
    }
}
