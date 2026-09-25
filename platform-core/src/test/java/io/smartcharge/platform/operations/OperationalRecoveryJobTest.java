package io.smartcharge.platform.operations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OperationalRecoveryJobTest {
    @Test
    void qualifiesConnectorVersionInUpdateFromStatement() {
        UUID tenantId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);

        when(jdbc.query(eq("select id from tenant where status='ACTIVE'"),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(tenantId));
        when(jdbc.query(contains("from device_command"),
                org.mockito.ArgumentMatchers.<RowMapper<OperationalRecoveryJob.ExpiredCommand>>any(),
                eq(tenantId))).thenReturn(List.of());
        when(tenantJdbc.readWriteAs(eq(tenantId), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());

        new OperationalRecoveryJob(jdbc, tenantJdbc).recover();

        verify(jdbc, atLeastOnce()).update(contains("version=c.version+1"), eq(tenantId));
    }
}
