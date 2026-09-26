package io.smartcharge.platform.identity;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/tenants")
final class TenantProvisioningController {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TenantJdbcExecutor tenantJdbc;
    private final AuditService audit;

    TenantProvisioningController(JdbcTemplate jdbc, TransactionTemplate transactions,
                                 TenantJdbcExecutor tenantJdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tenantJdbc = tenantJdbc;
        this.audit = audit;
    }

    @PostMapping
    ResponseEntity<ProvisionedTenant> create(@Valid @RequestBody ProvisionTenantRequest request) {
        ProvisionedTenant provisioned = Objects.requireNonNull(transactions.execute(status -> {
            UUID requestedTenantId = request.tenantId() == null ? UUID.randomUUID() : request.tenantId();
            TenantResolution resolution = createOrReconcileTenant(requestedTenantId, request);
            UUID tenantId = resolution.tenantId();
            return tenantJdbc.readWriteAs(tenantId, () -> {
                jdbc.update("""
                        insert into operator_organization
                            (id, tenant_id, parent_id, code, name, organization_type, hierarchy_level, status)
                        values (?, ?, null, ?, ?, 'REGIONAL_OPERATOR', 1, 'ACTIVE')
                        on conflict (tenant_id) where hierarchy_level=1 do update
                           set name=excluded.name, updated_at=now(), version=operator_organization.version+1
                        """, tenantId, tenantId, request.code(), request.displayName());
                UUID userId = jdbc.queryForObject("""
                        insert into platform_user (id, subject, display_name, status)
                        values (?, ?, ?, 'ACTIVE')
                        on conflict (subject) do update
                           set display_name=coalesce(excluded.display_name, platform_user.display_name),
                               status='ACTIVE', updated_at=now()
                        returning id
                        """, UUID.class, UUID.randomUUID(), request.adminSubject(), request.adminDisplayName());
                UUID membershipId = jdbc.queryForObject("""
                        insert into tenant_membership (id, tenant_id, user_id, role_code, status)
                        values (?, ?, ?, 'TENANT_ADMIN', 'ACTIVE')
                        on conflict (tenant_id, user_id, role_code) do update
                           set status='ACTIVE'
                        returning id
                        """, UUID.class, UUID.randomUUID(), tenantId, userId);
                audit.record(resolution.created() ? "TENANT_PROVISIONED" : "TENANT_PROVISIONING_RECONCILED",
                        "tenant", tenantId, null,
                        Map.of("code", request.code(), "adminSubject", request.adminSubject(),
                                "requestedTenantId", requestedTenantId));
                return new ProvisionedTenant(tenantId, request.code(), request.displayName(),
                        membershipId, request.adminSubject(), resolution.created());
            });
        }));
        HttpStatus responseStatus = provisioned.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(responseStatus).body(provisioned);
    }

    TenantResolution createOrReconcileTenant(UUID tenantId, ProvisionTenantRequest request) {
        List<TenantIdentity> matches = jdbc.query("""
                select id, code
                  from tenant
                 where id = ? or code = ?
                   for update
                """, (result, row) -> new TenantIdentity(
                result.getObject("id", UUID.class), result.getString("code")), tenantId, request.code());
        TenantIdentity idMatch = matches.stream().filter(tenant -> tenant.id().equals(tenantId)).findFirst().orElse(null);
        TenantIdentity codeMatch = matches.stream().filter(tenant -> tenant.code().equals(request.code())).findFirst().orElse(null);
        if (idMatch != null && !idMatch.code().equals(request.code())) {
            throw new DomainException("Tenant id is already assigned to a different code");
        }
        if (codeMatch == null && idMatch == null) {
            jdbc.update("""
                    insert into tenant (id, code, display_name, status)
                    values (?, ?, ?, 'ACTIVE')
                    """, tenantId, request.code(), request.displayName());
            return new TenantResolution(tenantId, true);
        }
        UUID resolvedTenantId = codeMatch == null ? idMatch.id() : codeMatch.id();
        jdbc.update("""
                update tenant
                   set display_name = ?, updated_at = now(), version = version + 1
                 where id = ?
                """, request.displayName(), resolvedTenantId);
        return new TenantResolution(resolvedTenantId, false);
    }

    record ProvisionTenantRequest(
            UUID tenantId,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}") String code,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 160) String adminSubject,
            @NotBlank @Size(max = 120) String adminDisplayName) { }

    record ProvisionedTenant(UUID tenantId, String code, String displayName,
                             UUID adminMembershipId, String adminSubject, boolean created) { }

    record TenantIdentity(UUID id, String code) { }
    record TenantResolution(UUID tenantId, boolean created) { }
}
