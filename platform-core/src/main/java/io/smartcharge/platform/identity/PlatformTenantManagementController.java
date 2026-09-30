package io.smartcharge.platform.identity;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.PlatformAuthority;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/tenants")
final class PlatformTenantManagementController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PlatformAuthority platformAuthority;
    private final AuditService audit;

    PlatformTenantManagementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                                       PlatformAuthority platformAuthority, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.platformAuthority = platformAuthority;
        this.audit = audit;
    }

    @GetMapping
    List<PlatformTenantView> list(Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        Instant todayStart = java.time.LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE).toInstant();
        return jdbc.query("""
                select id, code, display_name, status, created_at, updated_at
                  from tenant order by created_at desc, code
                """, (result, row) -> {
            UUID tenantId = result.getObject("id", UUID.class);
            TenantCounts counts = tenantJdbc.readWriteAs(tenantId, () -> tenantCounts(tenantId, todayStart));
            return new PlatformTenantView(tenantId, result.getString("code"), result.getString("display_name"),
                    result.getString("status"), counts.activeMembers(), counts.stations(), counts.devices(),
                    counts.onlineDevices(), counts.successfulPaymentsToday(), counts.todayNetRevenueMinor(),
                    counts.confirmedPlatformServiceFeeMinor(), counts.pendingPlatformServiceFeeMinor(),
                    result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant());
        });
    }

    private TenantCounts tenantCounts(UUID tenantId, Instant todayStart) {
        return jdbc.queryForObject("""
                with context as (
                    select cast(? as uuid) tenant_id, cast(? as timestamptz) today_start
                )
                select
                    (select count(distinct user_id) from tenant_membership, context
                      where tenant_membership.tenant_id=context.tenant_id and status='ACTIVE'
                    ) as active_members,
                    (select count(*) from station, context
                      where station.tenant_id=context.tenant_id and status <> 'CLOSED') as stations,
                    (select count(*) from device, context
                      where device.tenant_id=context.tenant_id and status <> 'RETIRED') as devices,
                    (select count(*) from device, context
                      where device.tenant_id=context.tenant_id and status='ONLINE') as online_devices,
                    (select count(*) from payment_transaction, context
                      where payment_transaction.tenant_id=context.tenant_id and status='SUCCEEDED'
                        and transaction_type in ('PAY', 'CAPTURE') and completed_at >= context.today_start)
                        as successful_payments_today,
                    (select coalesce(sum(amount_minor), 0) from payment_transaction, context
                      where payment_transaction.tenant_id=context.tenant_id and status='SUCCEEDED'
                        and transaction_type in ('PAY', 'CAPTURE') and completed_at >= context.today_start)
                    - (select coalesce(sum(amount_minor), 0) from refund_transaction, context
                      where refund_transaction.tenant_id=context.tenant_id and status='SUCCEEDED'
                        and completed_at >= context.today_start) as today_net_revenue_minor,
                    (select coalesce(sum(amount_minor), 0) from payment_profit_sharing_detail, context
                      where payment_profit_sharing_detail.tenant_id=context.tenant_id
                        and owner_type='PLATFORM' and status='SUCCESS')
                    - (select coalesce(sum(r.amount_minor), 0)
                         from payment_profit_sharing_return r
                         join payment_profit_sharing_detail d
                           on d.tenant_id=r.tenant_id and d.id=r.sharing_detail_id
                         cross join context
                        where r.tenant_id=context.tenant_id and d.owner_type='PLATFORM'
                          and r.status='SUCCEEDED') as confirmed_platform_service_fee_minor,
                    (select coalesce(sum(amount_minor), 0) from payment_profit_sharing_detail, context
                      where payment_profit_sharing_detail.tenant_id=context.tenant_id
                        and owner_type='PLATFORM' and status='PENDING') as pending_platform_service_fee_minor
                """, (result, row) -> new TenantCounts(
                result.getLong("active_members"), result.getLong("stations"), result.getLong("devices"),
                result.getLong("online_devices"), result.getLong("successful_payments_today"),
                result.getLong("today_net_revenue_minor"),
                result.getLong("confirmed_platform_service_fee_minor"),
                result.getLong("pending_platform_service_fee_minor")), tenantId,
                io.smartcharge.platform.shared.persistence.JdbcTimes.timestamp(todayStart));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PlatformTenantCreated create(@Valid @RequestBody CreatePlatformTenantRequest request,
                                 Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        Boolean existingTenant = jdbc.queryForObject(
                "select exists(select 1 from tenant where code=?)", Boolean.class, request.code());
        if (Boolean.TRUE.equals(existingTenant)) {
            throw new DomainException("The tenant code is already in use");
        }
        UUID tenantId = UUID.randomUUID();
        return tenantJdbc.readWriteAs(tenantId, () -> {
            jdbc.update("insert into tenant (id, code, display_name, status) values (?, ?, ?, 'ACTIVE')",
                    tenantId, request.code(), request.displayName().strip());
            audit.record("TENANT_CREATED", "tenant", tenantId, null,
                    Map.of("code", request.code(), "displayName", request.displayName().strip()));
            return new PlatformTenantCreated(tenantId, request.code(), request.displayName().strip());
        });
    }

    @PatchMapping("/{tenantId}/status")
    PlatformTenantView status(@PathVariable UUID tenantId,
                              @Valid @RequestBody TenantStatusRequest request,
                              Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        if (!List.of("ACTIVE", "SUSPENDED", "CLOSED").contains(request.status())) {
            throw new IllegalArgumentException("Unsupported tenant status");
        }
        tenantJdbc.readWriteAs(tenantId, () -> {
            boolean exists = !jdbc.query("select id from tenant where id=? for update",
                    (row, index) -> row.getObject(1, UUID.class), tenantId).isEmpty();
            if (!exists) throw new IllegalArgumentException("Tenant does not exist");
            if (!"ACTIVE".equals(request.status()) && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from charging_order where tenant_id=?
                                   and status in ('START_PENDING','CHARGING','STOP_PENDING'))
                    """, Boolean.class, tenantId))) {
                throw new DomainException("请先停止并确认所有进行中的充电订单，再暂停或关闭运营商");
            }
            jdbc.update("update tenant set status=?,updated_at=now(),version=version+1 where id=?", request.status(), tenantId);
            audit.record("TENANT_" + request.status(), "tenant", tenantId, null,
                    Map.of("status", request.status()));
            return null;
        });
        return list(authentication).stream().filter(item -> item.id().equals(tenantId)).findFirst()
                .orElseThrow(() -> new DomainException("Tenant status was updated but could not be reloaded"));
    }

    record CreatePlatformTenantRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}") String code,
            @NotBlank @Size(max = 160) String displayName) { }
    record TenantStatusRequest(@NotNull String status) { }
    record PlatformTenantCreated(UUID id, String code, String displayName) { }
    record PlatformTenantView(UUID id, String code, String displayName, String status,
                              long activeMembers, long stations, long devices, long onlineDevices,
                              long successfulPaymentsToday, long todayNetRevenueMinor,
                              long confirmedPlatformServiceFeeMinor, long pendingPlatformServiceFeeMinor,
                              Instant createdAt, Instant updatedAt) { }
    private record TenantCounts(long activeMembers, long stations, long devices, long onlineDevices,
                                long successfulPaymentsToday, long todayNetRevenueMinor,
                                long confirmedPlatformServiceFeeMinor, long pendingPlatformServiceFeeMinor) { }
}
