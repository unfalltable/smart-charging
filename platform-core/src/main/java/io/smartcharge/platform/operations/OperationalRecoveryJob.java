package io.smartcharge.platform.operations;

import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class OperationalRecoveryJob {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;

    OperationalRecoveryJob(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
    }

    @Scheduled(fixedDelayString = "${operations.recovery-delay-ms:30000}")
    void recover() {
        List<UUID> tenants = jdbc.query("select id from tenant where status='ACTIVE'",
                (result, row) -> result.getObject(1, UUID.class));
        for (UUID tenantId : tenants) tenantJdbc.readWriteAs(tenantId, () -> recoverTenant(tenantId));
    }

    private Void recoverTenant(UUID tenantId) {
        List<ExpiredCommand> expired = jdbc.query("""
                select id, order_id, connector_id, command_type
                  from device_command where tenant_id=? and status in ('PENDING','PUBLISHED') and expires_at<=now()
                 for update skip locked
                """, (result, row) -> new ExpiredCommand(
                result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                result.getObject("connector_id", UUID.class), result.getString("command_type")), tenantId);
        for (ExpiredCommand command : expired) {
            jdbc.update("""
                    update device_command set status='EXPIRED', failure_code='COMMAND_TIMEOUT', updated_at=now(),
                                              version=version+1 where tenant_id=? and id=?
                    """, tenantId, command.id());
            jdbc.update("""
                    update outbox_event set published_at=coalesce(published_at,now()), last_error='COMMAND_EXPIRED'
                     where tenant_id=? and aggregate_id=? and published_at is null
                    """, tenantId, command.id());
            if (command.orderId() == null) continue;
            if ("START_CHARGING".equals(command.type())) {
                int changed = jdbc.update("""
                        update charging_order set status='FAILED', updated_at=now(), version=version+1
                         where tenant_id=? and id=? and status='START_PENDING'
                        """, tenantId, command.orderId());
                if (changed == 1) {
                    jdbc.update("""
                            update connector set status='AVAILABLE', updated_at=now(), version=version+1
                             where tenant_id=? and id=? and status='RESERVED'
                            """, tenantId, command.connectorId());
                    history(tenantId, command.orderId(), "START_PENDING", "FAILED", "START_COMMAND_TIMEOUT");
                }
            } else if ("STOP_CHARGING".equals(command.type())) {
                int changed = jdbc.update("""
                        update charging_order set status='CHARGING', updated_at=now(), version=version+1
                         where tenant_id=? and id=? and status='STOP_PENDING'
                        """, tenantId, command.orderId());
                if (changed == 1) history(tenantId, command.orderId(), "STOP_PENDING", "CHARGING", "STOP_COMMAND_TIMEOUT");
            }
        }
        jdbc.update("""
                update device set status='OFFLINE', updated_at=now(), version=version+1
                 where tenant_id=? and status='ONLINE' and last_seen_at < now()-interval '3 minutes'
                """, tenantId);
        jdbc.update("""
                update connector c set status='OFFLINE', updated_at=now(), version=version+1
                  from device d
                 where d.tenant_id=? and d.status='OFFLINE' and c.tenant_id=d.tenant_id and c.device_id=d.id
                   and c.status in ('AVAILABLE','RESERVED')
                """, tenantId);
        return null;
    }

    private void history(UUID tenantId, UUID orderId, String from, String to, String reason) {
        jdbc.update("""
                insert into order_status_history
                    (id, tenant_id, order_id, from_status, to_status, reason, actor_subject)
                values (?, ?, ?, ?, ?, ?, 'system-recovery')
                """, UUID.randomUUID(), tenantId, orderId, from, to, reason);
    }

    record ExpiredCommand(UUID id, UUID orderId, UUID connectorId, String type) { }
}
