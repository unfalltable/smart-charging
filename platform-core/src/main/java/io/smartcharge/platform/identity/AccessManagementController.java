package io.smartcharge.platform.identity;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/access")
final class AccessManagementController {
    private static final Set<String> ROLES = Set.of("TENANT_ADMIN", "OPERATOR", "FINANCE", "AUDITOR", "SUPPORT");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final AuditService audit;

    AccessManagementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.audit = audit;
    }

    @GetMapping("/memberships")
    List<MembershipView> list() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select m.id, u.id user_id, u.subject, u.display_name, u.status user_status,
                       m.role_code, m.status membership_status, m.created_at
                  from tenant_membership m join platform_user u on u.id=m.user_id
                 where m.tenant_id=? order by u.display_name nulls last, u.subject, m.role_code
                """, (result, row) -> new MembershipView(
                result.getObject("id", UUID.class), result.getObject("user_id", UUID.class),
                result.getString("subject"), result.getString("display_name"), result.getString("user_status"),
                result.getString("role_code"), result.getString("membership_status"),
                result.getTimestamp("created_at").toInstant()), tenantId));
    }

    @PostMapping("/memberships")
    @ResponseStatus(HttpStatus.CREATED)
    MembershipView create(@Valid @RequestBody MembershipRequest request) {
        if (!ROLES.contains(request.roleCode())) throw new IllegalArgumentException("Unsupported tenant role");
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            UUID userId = jdbc.queryForObject("""
                    insert into platform_user (id, subject, display_name, status)
                    values (?, ?, ?, 'ACTIVE')
                    on conflict (subject) do update
                        set display_name=coalesce(excluded.display_name, platform_user.display_name),
                            updated_at=now()
                    returning id
                    """, UUID.class, UUID.randomUUID(), request.subject(), request.displayName());
            UUID membershipId = UUID.randomUUID();
            jdbc.update("""
                    insert into tenant_membership (id, tenant_id, user_id, role_code, status)
                    values (?, ?, ?, ?, 'ACTIVE')
                    on conflict (tenant_id, user_id, role_code)
                    do update set status='ACTIVE'
                    """, membershipId, tenantId, userId, request.roleCode());
            MembershipView view = jdbc.query("""
                    select m.id, u.id user_id, u.subject, u.display_name, u.status user_status,
                           m.role_code, m.status membership_status, m.created_at
                      from tenant_membership m join platform_user u on u.id=m.user_id
                     where m.tenant_id=? and m.user_id=? and m.role_code=?
                    """, (result, row) -> new MembershipView(
                    result.getObject("id", UUID.class), result.getObject("user_id", UUID.class),
                    result.getString("subject"), result.getString("display_name"), result.getString("user_status"),
                    result.getString("role_code"), result.getString("membership_status"),
                    result.getTimestamp("created_at").toInstant()), tenantId, userId, request.roleCode()).getFirst();
            audit.record("TENANT_MEMBERSHIP_GRANTED", "tenant_membership", view.id(), null,
                    Map.of("subject", request.subject(), "role", request.roleCode()));
            return view;
        });
    }

    @PatchMapping("/memberships/status")
    MembershipView status(@Valid @RequestBody MembershipStatusRequest request) {
        if (!Set.of("ACTIVE", "DISABLED").contains(request.status())) {
            throw new IllegalArgumentException("Invalid membership status");
        }
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            if ("DISABLED".equals(request.status())) {
                String role = jdbc.query("select role_code from tenant_membership where tenant_id=? and id=? for update",
                        (result, row) -> result.getString(1), tenantId, request.membershipId()).stream().findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Membership does not exist"));
                if ("TENANT_ADMIN".equals(role)) {
                    Integer remaining = jdbc.queryForObject("""
                            select count(*) from tenant_membership
                             where tenant_id=? and role_code='TENANT_ADMIN' and status='ACTIVE' and id<>?
                            """, Integer.class, tenantId, request.membershipId());
                    if (remaining == null || remaining == 0) {
                        throw new DomainException("The last active tenant administrator cannot be disabled");
                    }
                }
            }
            int changed = jdbc.update("update tenant_membership set status=? where tenant_id=? and id=?",
                    request.status(), tenantId, request.membershipId());
            if (changed != 1) throw new IllegalArgumentException("Membership does not exist");
            audit.record("TENANT_MEMBERSHIP_" + request.status(), "tenant_membership", request.membershipId(),
                    null, Map.of("status", request.status()));
            return jdbc.query("""
                    select m.id, u.id user_id, u.subject, u.display_name, u.status user_status,
                           m.role_code, m.status membership_status, m.created_at
                      from tenant_membership m join platform_user u on u.id=m.user_id
                     where m.tenant_id=? and m.id=?
                    """, (result, row) -> new MembershipView(
                    result.getObject("id", UUID.class), result.getObject("user_id", UUID.class),
                    result.getString("subject"), result.getString("display_name"), result.getString("user_status"),
                    result.getString("role_code"), result.getString("membership_status"),
                    result.getTimestamp("created_at").toInstant()), tenantId, request.membershipId()).getFirst();
        });
    }

    record MembershipRequest(@NotBlank @Size(max = 160) String subject,
                             @Size(max = 120) String displayName,
                             @NotBlank String roleCode) { }
    record MembershipStatusRequest(@NotNull UUID membershipId, @NotBlank String status) { }
    record MembershipView(UUID id, UUID userId, String subject, String displayName,
                          String userStatus, String roleCode, String membershipStatus, Instant createdAt) { }
}
