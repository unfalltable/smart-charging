package io.smartcharge.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class TenantJdbcExecutorTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void establishesAndClearsContextForTrustedWork() {
        stubTransaction();
        UUID tenantId = UUID.randomUUID();
        TenantJdbcExecutor executor = new TenantJdbcExecutor(jdbc, transactions);

        UUID observed = executor.readWriteAs(tenantId, TenantContext::requireTenantId);

        assertThat(observed).isEqualTo(tenantId);
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void restoresPreviousContextWhenWorkFails() {
        stubTransaction();
        UUID previous = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        TenantContext.set(previous);
        TenantJdbcExecutor executor = new TenantJdbcExecutor(jdbc, transactions);

        assertThatThrownBy(() -> executor.readWriteAs(trusted, () -> {
            assertThat(TenantContext.requireTenantId()).isEqualTo(trusted);
            throw new IllegalStateException("expected");
        })).isInstanceOf(IllegalStateException.class).hasMessage("expected");

        assertThat(TenantContext.requireTenantId()).isEqualTo(previous);
    }

    @SuppressWarnings("unchecked")
    private void stubTransaction() {
        when(jdbc.queryForObject(eq("select set_config('app.tenant_id', ?, true)"), eq(String.class), any()))
                .thenReturn("ok");
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0))
                        .doInTransaction(mock(TransactionStatus.class)));
    }
}
