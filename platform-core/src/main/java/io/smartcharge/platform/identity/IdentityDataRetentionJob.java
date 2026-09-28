package io.smartcharge.platform.identity;

import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class IdentityDataRetentionJob {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TenantJdbcExecutor tenantJdbc;
    private final int loginEventRetentionDays;
    private final int expiredTokenRetentionDays;

    IdentityDataRetentionJob(JdbcTemplate jdbc, TransactionTemplate transactions, TenantJdbcExecutor tenantJdbc,
                             @Value("${charging.identity.admin.login-event-retention-days:180}")
                             int loginEventRetentionDays,
                             @Value("${charging.identity.admin.expired-token-retention-days:7}")
                             int expiredTokenRetentionDays) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tenantJdbc = tenantJdbc;
        this.loginEventRetentionDays = positive(loginEventRetentionDays, "login event retention days");
        this.expiredTokenRetentionDays = positive(expiredTokenRetentionDays, "expired token retention days");
    }

    @Scheduled(cron = "${charging.identity.admin.retention-cron:0 20 3 * * *}")
    void purgeExpiredIdentityData() {
        transactions.executeWithoutResult(status -> {
            Boolean acquired = jdbc.queryForObject(
                    "select pg_try_advisory_xact_lock(8439, 1)", Boolean.class);
            if (!Boolean.TRUE.equals(acquired)) return;
            jdbc.update("""
                    update admin_refresh_token set replaced_by=null
                     where replaced_by in (
                        select id from admin_refresh_token
                         where expires_at < now() - make_interval(days => ?)
                     )
                    """, expiredTokenRetentionDays);
            jdbc.update("""
                    delete from admin_refresh_token
                     where expires_at < now() - make_interval(days => ?)
                    """, expiredTokenRetentionDays);
            jdbc.update("""
                    delete from admin_login_event
                     where occurred_at < now() - make_interval(days => ?)
                    """, loginEventRetentionDays);
        });

        List<UUID> tenantIds = jdbc.query("select id from tenant",
                (result, row) -> result.getObject("id", UUID.class));
        for (UUID tenantId : tenantIds) {
            tenantJdbc.readWriteAs(tenantId, () -> {
                Boolean acquired = jdbc.queryForObject(
                        "select pg_try_advisory_xact_lock(8439, hashtext(?))",
                        Boolean.class, tenantId.toString());
                if (!Boolean.TRUE.equals(acquired)) return null;
                jdbc.update("""
                        update auth_refresh_token set replaced_by=null
                         where tenant_id=? and replaced_by in (
                            select id from auth_refresh_token
                             where tenant_id=? and expires_at < now() - make_interval(days => ?)
                         )
                        """, tenantId, tenantId, expiredTokenRetentionDays);
                jdbc.update("""
                        delete from auth_refresh_token
                         where tenant_id=? and expires_at < now() - make_interval(days => ?)
                        """, tenantId, expiredTokenRetentionDays);
                return null;
            });
        }
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
