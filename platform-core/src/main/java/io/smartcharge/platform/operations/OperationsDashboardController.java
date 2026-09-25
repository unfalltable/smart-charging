package io.smartcharge.platform.operations;

import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
final class OperationsDashboardController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;

    OperationsDashboardController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
    }

    @GetMapping("/dashboard")
    DashboardSummary dashboard() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            DeviceCounts devices = jdbc.queryForObject("""
                    select count(*) filter (where status = 'ONLINE') as online,
                           count(*) as total
                      from device where tenant_id = ?
                    """, (result, row) -> new DeviceCounts(result.getLong("online"), result.getLong("total")),
                    tenantId);
            Long available = jdbc.queryForObject(
                    "select count(*) from connector where tenant_id = ? and status = 'AVAILABLE'",
                    Long.class, tenantId);
            Long activeOrders = jdbc.queryForObject("""
                    select count(*) from charging_order
                     where tenant_id = ? and status in ('START_PENDING', 'CHARGING', 'STOP_PENDING')
                    """, Long.class, tenantId);
            Instant todayUtc = ZonedDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            Long revenue = jdbc.queryForObject("""
                    select coalesce(sum(amount_minor), 0) from payment_transaction
                     where tenant_id = ? and status = 'SUCCEEDED' and completed_at >= ?
                    """, Long.class, tenantId, JdbcTimes.timestamp(todayUtc));
            return new DashboardSummary(devices.online(), devices.total(), value(available),
                    value(activeOrders), value(revenue));
        });
    }

    private static long value(Long value) { return value == null ? 0 : value; }

    record DeviceCounts(long online, long total) { }
    record DashboardSummary(long onlineDevices, long totalDevices, long availableConnectors,
                            long activeOrders, long todayRevenueMinor) { }
}
