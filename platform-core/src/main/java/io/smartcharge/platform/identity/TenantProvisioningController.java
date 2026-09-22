package io.smartcharge.platform.identity;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    @ResponseStatus(HttpStatus.CREATED)
    ProvisionedTenant create(@Valid @RequestBody ProvisionTenantRequest request) {
        return transactions.execute(status -> {
            UUID tenantId = UUID.randomUUID();
            jdbc.update("""
                    insert into tenant (id, code, display_name, status)
                    values (?, ?, ?, 'ACTIVE')
                    """, tenantId, request.code(), request.displayName());
            return tenantJdbc.readWriteAs(tenantId, () -> {
                UUID userId = jdbc.queryForObject("""
                        insert into platform_user (id, subject, display_name, status)
                        values (?, ?, ?, 'ACTIVE')
                        on conflict (subject) do update
                           set display_name=coalesce(excluded.display_name, platform_user.display_name),
                               status='ACTIVE', updated_at=now()
                        returning id
                        """, UUID.class, UUID.randomUUID(), request.adminSubject(), request.adminDisplayName());
                UUID membershipId = UUID.randomUUID();
                jdbc.update("""
                        insert into tenant_membership (id, tenant_id, user_id, role_code, status)
                        values (?, ?, ?, 'TENANT_ADMIN', 'ACTIVE')
                        """, membershipId, tenantId, userId);
                audit.record("TENANT_PROVISIONED", "tenant", tenantId, null,
                        Map.of("code", request.code(), "adminSubject", request.adminSubject()));
                return new ProvisionedTenant(tenantId, request.code(), request.displayName(),
                        membershipId, request.adminSubject());
            });
        });
    }

    record ProvisionTenantRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}") String code,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 160) String adminSubject,
            @NotBlank @Size(max = 120) String adminDisplayName) { }

    record ProvisionedTenant(UUID tenantId, String code, String displayName,
                             UUID adminMembershipId, String adminSubject) { }
}
