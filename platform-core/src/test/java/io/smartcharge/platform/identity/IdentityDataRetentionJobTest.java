package io.smartcharge.platform.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class IdentityDataRetentionJobTest {
    @Test
    void removesOnlyDataOlderThanTheConfiguredRetentionWindows() {
        UUID tenantId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> work = invocation.getArgument(0);
            work.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(jdbc.queryForObject(contains("pg_try_advisory_xact_lock(8439, 1)"), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForObject(contains("hashtext"), eq(Boolean.class), eq(tenantId.toString())))
                .thenReturn(true);
        when(jdbc.query(eq("select id from tenant"), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of(tenantId));
        when(tenantJdbc.readWriteAs(eq(tenantId), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());

        new IdentityDataRetentionJob(jdbc, transactions, tenantJdbc, 180, 7)
                .purgeExpiredIdentityData();

        verify(jdbc).update(contains("delete from admin_refresh_token"), eq(7));
        verify(jdbc).update(contains("delete from admin_login_event"), eq(180));
        verify(jdbc).update(contains("delete from auth_refresh_token"), eq(tenantId), eq(7));
    }

    @Test
    void rejectsRetentionWindowsThatCouldDeleteCurrentSecurityData() {
        assertThatThrownBy(() -> new IdentityDataRetentionJob(mock(JdbcTemplate.class),
                mock(TransactionTemplate.class), mock(TenantJdbcExecutor.class), 0, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }
}
