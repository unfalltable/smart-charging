package io.smartcharge.platform.identity;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.domain.ServiceUnavailableException;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@RequestMapping("/api/v1/admin/access")
final class AccessManagementController {
    private static final Set<String> ROLES = Set.of("TENANT_ADMIN", "OPERATOR", "FINANCE", "AUDITOR", "SUPPORT");
    private static final Set<String> MFA_REQUIRED_ROLES = Set.of("TENANT_ADMIN", "FINANCE");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final AuditService audit;
    private final IdentityAdminGateway identity;

    AccessManagementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, AuditService audit,
                               IdentityAdminGateway identity) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.audit = audit;
        this.identity = identity;
    }

    @GetMapping("/capabilities")
    IdentityAdminGateway.Capabilities capabilities() {
        return identity.capabilities();
    }

    @GetMapping("/memberships")
    List<MembershipView> list() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query(membershipSql("m.tenant_id=?"),
                (result, row) -> membershipView(result), tenantId));
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationResult invite(@Valid @RequestBody InvitationRequest request, Authentication authentication) {
        requireRole(request.roleCode());
        if (!identity.capabilities().managedLifecycle()) {
            throw new ServiceUnavailableException(
                    "Create the user in the external identity provider, then link its OIDC subject here");
        }
        UUID tenantId = TenantContext.requireTenantId();
        boolean requireMfa = Boolean.TRUE.equals(request.requireMfa()) || MFA_REQUIRED_ROLES.contains(request.roleCode());
        Instant expiresAt = Instant.now().plus(request.expiresInHours(), ChronoUnit.HOURS);
        IdentityAdminGateway.ProvisionedIdentity provisioned = identity.provision(
                new IdentityAdminGateway.ProvisionIdentity(request.username(), request.email(),
                        request.displayName(), tenantId, request.roleCode(), requireMfa));
        MembershipView membership;
        try {
            membership = tenantJdbc.readWrite(() -> {
                UUID userId = jdbc.queryForObject("""
                        insert into platform_user
                            (id, subject, username, email, display_name, status, identity_managed, mfa_required)
                        values (?, ?, ?, ?, ?, 'ACTIVE', true, ?)
                        on conflict (subject) do update
                            set username=coalesce(excluded.username, platform_user.username),
                                email=coalesce(excluded.email, platform_user.email),
                                display_name=coalesce(excluded.display_name, platform_user.display_name),
                                status='ACTIVE', identity_managed=true,
                                mfa_required=platform_user.mfa_required or excluded.mfa_required,
                                updated_at=now(), version=platform_user.version+1
                        returning id
                        """, UUID.class, UUID.randomUUID(), provisioned.subject(), provisioned.username(),
                        provisioned.email(), request.displayName(), requireMfa);
                UUID membershipId = jdbc.queryForObject("""
                        insert into tenant_membership
                            (id, tenant_id, user_id, role_code, status, invited_at, invite_expires_at,
                             accepted_at, invited_by)
                        values (?, ?, ?, ?, 'ACTIVE', now(), ?, null, ?)
                        on conflict (tenant_id, user_id, role_code) do update
                           set status='ACTIVE', invited_at=now(), invite_expires_at=excluded.invite_expires_at,
                               accepted_at=null, invited_by=excluded.invited_by, updated_at=now(),
                               version=tenant_membership.version+1
                        returning id
                        """, UUID.class, UUID.randomUUID(), tenantId, userId, request.roleCode(), expiresAt,
                        authentication.getName());
                audit.record("ACCOUNT_INVITED", "tenant_membership", membershipId, null,
                        Map.of("username", provisioned.username(), "email", provisioned.email(),
                                "role", request.roleCode(), "mfaRequired", requireMfa,
                                "expiresAt", expiresAt.toString()));
                return findMembership(tenantId, membershipId);
            });
        } catch (RuntimeException failure) {
            identity.deleteIfCreated(provisioned);
            throw failure;
        }
        String delivery = "TEMPORARY_PASSWORD";
        String temporaryPassword = provisioned.temporaryPassword();
        if (!provisioned.created()) {
            delivery = "EXISTING_ACCOUNT";
            temporaryPassword = null;
        } else if (identity.capabilities().emailDelivery()) {
            try {
                identity.sendInvitation(provisioned.subject());
                delivery = "EMAIL";
                temporaryPassword = null;
            } catch (ServiceUnavailableException emailFailure) {
                temporaryPassword = identity.resetTemporaryPassword(provisioned.subject(), requireMfa, false);
                delivery = "TEMPORARY_PASSWORD_FALLBACK";
            }
        }
        return new InvitationResult(membership, delivery, temporaryPassword);
    }

    /** Links an account owned by an external OIDC provider. Bundled identity deployments use invitations. */
    @PostMapping("/memberships")
    @ResponseStatus(HttpStatus.CREATED)
    MembershipView createExternalMembership(@Valid @RequestBody MembershipRequest request) {
        requireRole(request.roleCode());
        if (identity.capabilities().managedLifecycle()) {
            throw new DomainException("Use the account invitation workflow for the bundled identity provider");
        }
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            UUID userId = jdbc.queryForObject("""
                    insert into platform_user (id, subject, display_name, status, identity_managed)
                    values (?, ?, ?, 'ACTIVE', false)
                    on conflict (subject) do update
                        set display_name=coalesce(excluded.display_name, platform_user.display_name),
                            updated_at=now(), version=platform_user.version+1
                    returning id
                    """, UUID.class, UUID.randomUUID(), request.subject(), request.displayName());
            UUID membershipId = jdbc.queryForObject("""
                    insert into tenant_membership
                        (id, tenant_id, user_id, role_code, status, accepted_at)
                    values (?, ?, ?, ?, 'ACTIVE', now())
                    on conflict (tenant_id, user_id, role_code)
                    do update set status='ACTIVE', updated_at=now(), version=tenant_membership.version+1
                    returning id
                    """, UUID.class, UUID.randomUUID(), tenantId, userId, request.roleCode());
            MembershipView view = findMembership(tenantId, membershipId);
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
        MembershipIdentity membership = tenantJdbc.readWrite(() -> {
            MembershipIdentity locked = membershipIdentityForUpdate(tenantId, request.membershipId());
            if ("DISABLED".equals(request.status()) && "TENANT_ADMIN".equals(locked.roleCode())) {
                Integer remaining = jdbc.queryForObject("""
                        select count(*) from tenant_membership
                         where tenant_id=? and role_code='TENANT_ADMIN' and status='ACTIVE' and id<>?
                           and accepted_at is not null
                        """, Integer.class, tenantId, request.membershipId());
                if (remaining == null || remaining == 0) {
                    throw new DomainException("The last active tenant administrator cannot be disabled");
                }
            }
            jdbc.update("""
                    update tenant_membership
                       set status=?, updated_at=now(), version=version+1
                     where tenant_id=? and id=?
                    """, request.status(), tenantId, request.membershipId());
            audit.record("TENANT_MEMBERSHIP_" + request.status(), "tenant_membership", request.membershipId(),
                    null, Map.of("status", request.status()));
            return locked;
        });
        if (identity.capabilities().managedLifecycle()) {
            if ("ACTIVE".equals(request.status())) {
                identity.addTenantAuthorization(membership.subject(), tenantId, membership.roleCode(),
                        membership.mfaRequired());
            }
            identity.logout(membership.subject());
        }
        return tenantJdbc.readWrite(() -> findMembership(tenantId, request.membershipId()));
    }

    @PostMapping("/memberships/{membershipId}/recovery")
    RecoveryResult recover(@PathVariable UUID membershipId, @RequestBody(required = false) RecoveryRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        MembershipIdentity account = tenantJdbc.readWrite(() -> membershipIdentity(tenantId, membershipId));
        boolean resetMfa = request != null && Boolean.TRUE.equals(request.resetMfa());
        Delivery delivery = deliverRecovery(account, resetMfa);
        tenantJdbc.readWrite(() -> {
            audit.record("ACCOUNT_RECOVERY_ISSUED", "tenant_membership", membershipId, null,
                    Map.of("delivery", delivery.method(), "resetMfa", resetMfa));
            return null;
        });
        return new RecoveryResult(delivery.method(), delivery.temporaryPassword());
    }

    @PostMapping("/memberships/{membershipId}/resend")
    InvitationResult resend(@PathVariable UUID membershipId) {
        UUID tenantId = TenantContext.requireTenantId();
        MembershipIdentity account = tenantJdbc.readWrite(() -> membershipIdentity(tenantId, membershipId));
        Delivery delivery = deliverInvitation(account);
        Instant expiresAt = Instant.now().plus(identity.capabilities().invitationLifespanHours(), ChronoUnit.HOURS);
        MembershipView view = tenantJdbc.readWrite(() -> {
            jdbc.update("""
                    update tenant_membership
                       set status='ACTIVE', invited_at=now(), invite_expires_at=?, accepted_at=null,
                           updated_at=now(), version=version+1
                     where tenant_id=? and id=?
                    """, expiresAt, tenantId, membershipId);
            audit.record("ACCOUNT_INVITATION_RESENT", "tenant_membership", membershipId, null,
                    Map.of("delivery", delivery.method(), "expiresAt", expiresAt.toString()));
            return findMembership(tenantId, membershipId);
        });
        return new InvitationResult(view, delivery.method(), delivery.temporaryPassword());
    }

    @GetMapping("/login-events")
    List<IdentityAdminGateway.LoginEvent> loginEvents() {
        UUID tenantId = TenantContext.requireTenantId();
        List<UserIdentityKey> identities = tenantJdbc.readWrite(() -> jdbc.query("""
                select distinct u.subject, u.username
                  from tenant_membership m join platform_user u on u.id=m.user_id
                 where m.tenant_id=?
                """, (result, row) -> new UserIdentityKey(
                result.getString("subject"), result.getString("username")), tenantId));
        Set<String> subjects = identities.stream().map(UserIdentityKey::subject).collect(java.util.stream.Collectors.toSet());
        Set<String> usernames = identities.stream().map(UserIdentityKey::username)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return identity.loginEvents(200).stream().filter(event ->
                subjects.contains(event.subject()) || (event.username() != null
                        && usernames.contains(event.username().toLowerCase(java.util.Locale.ROOT)))).toList();
    }

    private Delivery deliverInvitation(MembershipIdentity account) {
        if (identity.capabilities().emailDelivery()) {
            try {
                identity.sendInvitation(account.subject());
                return new Delivery("EMAIL", null);
            } catch (ServiceUnavailableException emailFailure) {
                return new Delivery("TEMPORARY_PASSWORD_FALLBACK",
                        identity.resetTemporaryPassword(account.subject(), account.mfaRequired(), false));
            }
        }
        return new Delivery("TEMPORARY_PASSWORD",
                identity.resetTemporaryPassword(account.subject(), account.mfaRequired(), false));
    }

    private Delivery deliverRecovery(MembershipIdentity account, boolean resetMfa) {
        if (identity.capabilities().emailDelivery()) {
            try {
                identity.sendRecovery(account.subject(), resetMfa, account.mfaRequired());
                return new Delivery("EMAIL", null);
            } catch (ServiceUnavailableException emailFailure) {
                return new Delivery("TEMPORARY_PASSWORD_FALLBACK",
                        identity.resetTemporaryPassword(account.subject(), account.mfaRequired(), resetMfa));
            }
        }
        return new Delivery("TEMPORARY_PASSWORD",
                identity.resetTemporaryPassword(account.subject(), account.mfaRequired(), resetMfa));
    }

    private MembershipIdentity membershipIdentity(UUID tenantId, UUID membershipId) {
        return jdbc.query("""
                select m.id, m.role_code, u.subject, u.mfa_required
                  from tenant_membership m join platform_user u on u.id=m.user_id
                 where m.tenant_id=? and m.id=?
                """, (result, row) -> membershipIdentity(result), tenantId, membershipId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Membership does not exist"));
    }

    private MembershipIdentity membershipIdentityForUpdate(UUID tenantId, UUID membershipId) {
        return jdbc.query("""
                select m.id, m.role_code, u.subject, u.mfa_required
                  from tenant_membership m join platform_user u on u.id=m.user_id
                 where m.tenant_id=? and m.id=? for update
                """, (result, row) -> membershipIdentity(result), tenantId, membershipId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Membership does not exist"));
    }

    private static MembershipIdentity membershipIdentity(java.sql.ResultSet result) throws java.sql.SQLException {
        return new MembershipIdentity(result.getObject("id", UUID.class), result.getString("role_code"),
                result.getString("subject"), result.getBoolean("mfa_required"));
    }

    private MembershipView findMembership(UUID tenantId, UUID membershipId) {
        return jdbc.query(membershipSql("m.tenant_id=? and m.id=?"),
                (result, row) -> membershipView(result), tenantId, membershipId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Membership does not exist"));
    }

    private static String membershipSql(String predicate) {
        return """
                select m.id, u.id user_id, u.subject, u.username, u.email, u.display_name,
                       u.status user_status, u.identity_managed, u.mfa_required, u.last_login_at,
                       m.role_code, m.status membership_status, m.invited_at, m.invite_expires_at,
                       m.accepted_at, m.created_at,
                       case
                         when m.status='DISABLED' then 'REVOKED'
                         when m.accepted_at is not null then 'ACCEPTED'
                         when m.invite_expires_at is not null and m.invite_expires_at <= now() then 'EXPIRED'
                         else 'PENDING'
                       end invitation_status
                  from tenant_membership m join platform_user u on u.id=m.user_id
                 where %s order by u.display_name nulls last, u.subject, m.role_code
                """.formatted(predicate);
    }

    private static MembershipView membershipView(java.sql.ResultSet result) throws java.sql.SQLException {
        return new MembershipView(result.getObject("id", UUID.class), result.getObject("user_id", UUID.class),
                result.getString("subject"), result.getString("username"), result.getString("email"),
                result.getString("display_name"), result.getString("user_status"),
                result.getBoolean("identity_managed"), result.getBoolean("mfa_required"),
                result.getString("role_code"), result.getString("membership_status"),
                result.getString("invitation_status"), instant(result, "invited_at"),
                instant(result, "invite_expires_at"), instant(result, "accepted_at"),
                instant(result, "last_login_at"), instant(result, "created_at"));
    }

    private static Instant instant(java.sql.ResultSet result, String name) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = result.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void requireRole(String roleCode) {
        if (!ROLES.contains(roleCode)) throw new IllegalArgumentException("Unsupported tenant role");
    }

    record InvitationRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank String roleCode,
            Boolean requireMfa,
            @NotNull @Min(1) @Max(720) Integer expiresInHours) { }

    record MembershipRequest(@NotBlank @Size(max = 160) String subject,
                             @Size(max = 120) String displayName,
                             @NotBlank String roleCode) { }
    record MembershipStatusRequest(@NotNull UUID membershipId, @NotBlank String status) { }
    record MembershipView(UUID id, UUID userId, String subject, String username, String email,
                          String displayName, String userStatus, boolean identityManaged,
                          boolean mfaRequired, String roleCode, String membershipStatus,
                          String invitationStatus, Instant invitedAt, Instant inviteExpiresAt,
                          Instant acceptedAt, Instant lastLoginAt, Instant createdAt) { }
    record InvitationResult(MembershipView membership, String delivery, String temporaryPassword) { }
    record RecoveryResult(String delivery, String temporaryPassword) { }
    record RecoveryRequest(Boolean resetMfa) { }
    private record MembershipIdentity(UUID id, String roleCode, String subject, boolean mfaRequired) { }
    private record Delivery(String method, String temporaryPassword) { }
    private record UserIdentityKey(String subject, String username) { }
}
