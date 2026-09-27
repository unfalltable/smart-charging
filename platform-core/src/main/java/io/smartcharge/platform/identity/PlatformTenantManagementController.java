package io.smartcharge.platform.identity;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.domain.ServiceUnavailableException;
import io.smartcharge.platform.tenancy.PlatformAuthority;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final TenantProvisioningController provisioning;
    private final IdentityAdminGateway identity;
    private final PlatformAuthority platformAuthority;
    private final AuditService audit;

    PlatformTenantManagementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                                       TenantProvisioningController provisioning,
                                       IdentityAdminGateway identity,
                                       PlatformAuthority platformAuthority, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.provisioning = provisioning;
        this.identity = identity;
        this.platformAuthority = platformAuthority;
        this.audit = audit;
    }

    @GetMapping
    List<PlatformTenantView> list(Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        return jdbc.query("""
                select id, code, display_name, status, created_at, updated_at
                  from tenant order by created_at desc, code
                """, (result, row) -> {
            UUID tenantId = result.getObject("id", UUID.class);
            TenantCounts counts = tenantJdbc.readWriteAs(tenantId, () -> new TenantCounts(
                    jdbc.queryForObject("""
                            select count(distinct user_id) from tenant_membership
                             where tenant_id=? and status='ACTIVE'
                               and (accepted_at is not null or invite_expires_at is null or invite_expires_at > now())
                            """, Integer.class, tenantId),
                    jdbc.queryForObject("select count(*) from station where tenant_id=?", Integer.class, tenantId),
                    jdbc.queryForObject("select count(*) from device where tenant_id=?", Integer.class, tenantId)));
            return new PlatformTenantView(tenantId, result.getString("code"), result.getString("display_name"),
                    result.getString("status"), counts.activeMembers(), counts.stations(), counts.devices(),
                    result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant());
        });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PlatformTenantCreated create(@Valid @RequestBody CreatePlatformTenantRequest request,
                                 Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        if (!identity.capabilities().managedLifecycle()) {
            throw new ServiceUnavailableException(
                    "Tenant administrator creation must be completed in the external identity provider");
        }
        Boolean existingTenant = jdbc.queryForObject(
                "select exists(select 1 from tenant where code=?)", Boolean.class, request.code());
        if (Boolean.TRUE.equals(existingTenant)) {
            throw new DomainException("The tenant code is already in use");
        }
        UUID requestedTenantId = UUID.randomUUID();
        IdentityAdminGateway.ProvisionedIdentity account = identity.provision(
                new IdentityAdminGateway.ProvisionIdentity(request.adminUsername(), request.adminEmail(),
                        request.adminDisplayName(), requestedTenantId, "TENANT_ADMIN", true));
        TenantProvisioningController.ProvisionedTenant tenant;
        try {
            tenant = provisioning.create(new TenantProvisioningController.ProvisionTenantRequest(
                    requestedTenantId, request.code(), request.displayName(), account.subject(),
                    request.adminDisplayName())).getBody();
            if (tenant == null) throw new IllegalStateException("Tenant provisioning returned no result");
            UUID tenantId = tenant.tenantId();
            Instant expiresAt = Instant.now().plus(identity.capabilities().invitationLifespanHours(), ChronoUnit.HOURS);
            tenantJdbc.readWriteAs(tenantId, () -> {
                jdbc.update("""
                        update platform_user
                           set username=?, email=?, display_name=?, identity_managed=true,
                               mfa_required=true, status='ACTIVE', updated_at=now(), version=version+1
                         where subject=?
                        """, account.username(), account.email(), request.adminDisplayName(), account.subject());
                jdbc.update("""
                        update tenant_membership
                           set invited_at=now(), invite_expires_at=?, accepted_at=null, invited_by=?,
                               updated_at=now(), version=version+1
                         where id=? and tenant_id=?
                        """, expiresAt, authentication.getName(), tenant.adminMembershipId(), tenantId);
                audit.record("TENANT_ADMIN_INVITED", "tenant_membership", tenant.adminMembershipId(), null,
                        Map.of("username", account.username(), "email", account.email(),
                                "expiresAt", expiresAt.toString()));
                return null;
            });
        } catch (RuntimeException failure) {
            identity.deleteIfCreated(account);
            throw failure;
        }
        String delivery = "TEMPORARY_PASSWORD";
        String temporaryPassword = account.temporaryPassword();
        if (!account.created()) {
            delivery = "EXISTING_ACCOUNT";
            temporaryPassword = null;
        } else if (identity.capabilities().emailDelivery()) {
            try {
                identity.sendInvitation(account.subject());
                delivery = "EMAIL";
                temporaryPassword = null;
            } catch (ServiceUnavailableException emailFailure) {
                temporaryPassword = identity.resetTemporaryPassword(account.subject(), true, false);
                delivery = "TEMPORARY_PASSWORD_FALLBACK";
            }
        }
        return new PlatformTenantCreated(tenant.tenantId(), tenant.code(), tenant.displayName(),
                account.username(), account.email(), delivery, temporaryPassword);
    }

    @PatchMapping("/{tenantId}/status")
    PlatformTenantView status(@PathVariable UUID tenantId,
                              @Valid @RequestBody TenantStatusRequest request,
                              Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        if (!List.of("ACTIVE", "SUSPENDED", "CLOSED").contains(request.status())) {
            throw new IllegalArgumentException("Unsupported tenant status");
        }
        int changed = jdbc.update("""
                update tenant set status=?, updated_at=now(), version=version+1 where id=?
                """, request.status(), tenantId);
        if (changed != 1) throw new IllegalArgumentException("Tenant does not exist");
        tenantJdbc.readWriteAs(tenantId, () -> {
            audit.record("TENANT_" + request.status(), "tenant", tenantId, null,
                    Map.of("status", request.status()));
            return null;
        });
        return list(authentication).stream().filter(item -> item.id().equals(tenantId)).findFirst()
                .orElseThrow(() -> new DomainException("Tenant status was updated but could not be reloaded"));
    }

    record CreatePlatformTenantRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}") String code,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}") String adminUsername,
            @NotBlank @Email @Size(max = 254) String adminEmail,
            @NotBlank @Size(max = 120) String adminDisplayName) { }
    record TenantStatusRequest(@NotNull String status) { }
    record PlatformTenantCreated(UUID id, String code, String displayName, String adminUsername,
                                 String adminEmail, String delivery, String temporaryPassword) { }
    record PlatformTenantView(UUID id, String code, String displayName, String status,
                              int activeMembers, int stations, int devices,
                              Instant createdAt, Instant updatedAt) { }
    private record TenantCounts(int activeMembers, int stations, int devices) { }
}
