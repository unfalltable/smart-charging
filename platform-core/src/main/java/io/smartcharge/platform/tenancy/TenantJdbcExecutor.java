package io.smartcharge.platform.tenancy;

import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class TenantJdbcExecutor {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public TenantJdbcExecutor(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public <T> T readWrite(Supplier<T> work) {
        return readWriteAs(TenantContext.requireTenantId(), work);
    }

    public <T> T readWriteAs(java.util.UUID trustedTenantId, Supplier<T> work) {
        java.util.Optional<java.util.UUID> previous = TenantContext.currentTenantId();
        TenantContext.set(trustedTenantId);
        try {
            return transactions.execute(status -> {
                jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class,
                        trustedTenantId.toString());
                return work.get();
            });
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }
}
