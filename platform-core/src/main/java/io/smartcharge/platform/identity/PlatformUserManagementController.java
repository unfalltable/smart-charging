package io.smartcharge.platform.identity;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.PlatformAuthority;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/users")
final class PlatformUserManagementController {
    private static final Set<String> ROLES = Set.of(
            "TENANT_ADMIN", "OPERATOR", "FINANCE", "AUDITOR", "SUPPORT");

    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PlatformAuthority platformAuthority;
    private final PasswordEncoder passwords;
    private final TemporaryPasswordGenerator passwordGenerator;
    private final AdminTokenService tokens;
    private final AuditService audit;

    PlatformUserManagementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                                     PlatformAuthority platformAuthority, PasswordEncoder passwords,
                                     TemporaryPasswordGenerator passwordGenerator,
                                     AdminTokenService tokens, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.platformAuthority = platformAuthority;
        this.passwords = passwords;
        this.passwordGenerator = passwordGenerator;
        this.tokens = tokens;
        this.audit = audit;
    }

    @GetMapping
    List<PlatformUserView> list(Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        return jdbc.query("""
                select u.id, u.username, u.email, u.display_name, u.status, u.must_change_password,
                       u.failed_login_count, u.locked_until, u.last_login_at,
                       a.tenant_id, t.code tenant_code, t.display_name tenant_name, a.role_code
                  from platform_user u
                  left join user_tenant_authority a on a.user_id=u.id and a.status='ACTIVE'
                  left join tenant t on t.id=a.tenant_id
                 where u.platform_role is null
                 order by u.created_at desc, t.display_name, a.role_code
                """, (result, row) -> userView(result));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatedPlatformUser create(@Valid @RequestBody CreatePlatformUserRequest request,
                               Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        validateRole(request.roleCode());
        requireActiveTenant(request.tenantId());
        String username = request.username().strip().toLowerCase(Locale.ROOT);
        String email = normalizeEmail(request.email());
        Boolean duplicate = email == null
                ? jdbc.queryForObject("select exists(select 1 from platform_user where lower(username)=lower(?))",
                Boolean.class, username)
                : jdbc.queryForObject("""
                        select exists(select 1 from platform_user
                         where lower(username)=lower(?) or lower(email)=lower(?))
                        """, Boolean.class, username, email);
        if (Boolean.TRUE.equals(duplicate)) throw new DomainException("用户名或邮箱已被使用");

        UUID userId = UUID.randomUUID();
        String temporaryPassword = passwordGenerator.generate();
        PlatformUserView user = tenantJdbc.readWriteAs(request.tenantId(), () -> {
            jdbc.update("""
                    insert into platform_user
                        (id, subject, username, email, display_name, status, identity_managed,
                         password_hash, must_change_password, password_changed_at)
                    values (?, ?, ?, ?, ?, 'ACTIVE', true, ?, true, now())
                    """, userId, "admin:" + userId, username, email, request.displayName().strip(),
                    passwords.encode(temporaryPassword));
            jdbc.update("""
                    insert into tenant_membership
                        (id, tenant_id, user_id, role_code, status, invited_at, accepted_at)
                    values (?, ?, ?, ?, 'ACTIVE', now(), now())
                    """, UUID.randomUUID(), request.tenantId(), userId, request.roleCode());
            audit.record("ADMIN_ACCOUNT_CREATED", "platform_user", userId, null,
                    Map.of("username", username, "role", request.roleCode()));
            return load(userId, request.tenantId());
        });
        return new CreatedPlatformUser(user, temporaryPassword);
    }

    @PatchMapping("/{userId}/role")
    PlatformUserView changeRole(@PathVariable UUID userId,
                                @Valid @RequestBody ChangeRoleRequest request,
                                Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        validateRole(request.roleCode());
        requireManagedUser(userId);
        requireActiveTenant(request.tenantId());
        return tenantJdbc.readWriteAs(request.tenantId(), () -> {
            int existing = jdbc.queryForObject("""
                    select count(*) from tenant_membership
                     where tenant_id=? and user_id=?
                    """, Integer.class, request.tenantId(), userId);
            if (existing == 0) throw new DomainException("账号不属于所选租户");
            jdbc.update("delete from tenant_membership where tenant_id=? and user_id=?",
                    request.tenantId(), userId);
            jdbc.update("""
                    insert into tenant_membership
                        (id, tenant_id, user_id, role_code, status, invited_at, accepted_at)
                    values (?, ?, ?, ?, 'ACTIVE', now(), now())
                    """, UUID.randomUUID(), request.tenantId(), userId, request.roleCode());
            revokeSessions(userId);
            audit.record("ADMIN_ROLE_CHANGED", "platform_user", userId, null,
                    Map.of("role", request.roleCode()));
            return load(userId, request.tenantId());
        });
    }

    @PatchMapping("/{userId}/status")
    PlatformUserView changeStatus(@PathVariable UUID userId,
                                  @Valid @RequestBody ChangeStatusRequest request,
                                  Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        if (!Set.of("ACTIVE", "CLOSED").contains(request.status())) {
            throw new IllegalArgumentException("账号状态只能是 ACTIVE 或 CLOSED");
        }
        UUID tenantId = requireManagedUser(userId);
        return tenantJdbc.readWriteAs(tenantId, () -> {
            jdbc.update("""
                    update platform_user set status=?, failed_login_count=0, locked_until=null,
                           auth_version=auth_version+1, updated_at=now(), version=version+1
                     where id=? and platform_role is null
                    """, request.status(), userId);
            tokens.revokeAll(userId);
            audit.record("ADMIN_ACCOUNT_" + request.status(), "platform_user", userId, null,
                    Map.of("status", request.status()));
            return load(userId, tenantId);
        });
    }

    @PostMapping("/{userId}/reset-password")
    ResetPasswordResult resetPassword(@PathVariable UUID userId, Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        UUID tenantId = requireManagedUser(userId);
        String temporaryPassword = passwordGenerator.generate();
        PlatformUserView user = tenantJdbc.readWriteAs(tenantId, () -> {
            jdbc.update("""
                    update platform_user set password_hash=?, must_change_password=true,
                           failed_login_count=0, locked_until=null, auth_version=auth_version+1,
                           password_changed_at=now(), updated_at=now(), version=version+1
                     where id=? and platform_role is null
                    """, passwords.encode(temporaryPassword), userId);
            tokens.revokeAll(userId);
            audit.record("ADMIN_PASSWORD_RESET", "platform_user", userId, null,
                    Map.of("mustChangePassword", true));
            return load(userId, tenantId);
        });
        return new ResetPasswordResult(user, temporaryPassword);
    }

    @GetMapping("/login-events")
    List<LoginEventView> loginEvents(Authentication authentication) {
        platformAuthority.requirePlatformAdministrator(authentication);
        return jdbc.query("""
                select occurred_at, username, source_ip, result, failure_reason
                  from admin_login_event order by occurred_at desc limit 200
                """, (result, row) -> new LoginEventView(
                result.getTimestamp("occurred_at").toInstant(), result.getString("username"),
                result.getString("source_ip"), result.getString("result"),
                result.getString("failure_reason")));
    }

    private UUID requireManagedUser(UUID userId) {
        ManagedUser user = jdbc.query("""
                select u.platform_role,
                       (select tenant_id from user_tenant_authority
                         where user_id=u.id and status='ACTIVE' order by tenant_id limit 1) tenant_id
                  from platform_user u where u.id=?
                """, (result, row) -> new ManagedUser(result.getString("platform_role"),
                result.getObject("tenant_id", UUID.class)), userId).stream().findFirst()
                .orElseThrow(() -> new DomainException("账号不存在"));
        if (user.platformRole() != null) throw new DomainException("不能修改平台超级管理员");
        if (user.tenantId() == null) throw new DomainException("账号尚未分配租户");
        return user.tenantId();
    }

    private void requireActiveTenant(UUID tenantId) {
        Boolean active = jdbc.queryForObject(
                "select exists(select 1 from tenant where id=? and status='ACTIVE')", Boolean.class, tenantId);
        if (!Boolean.TRUE.equals(active)) throw new DomainException("所选租户不存在或未启用");
    }

    private PlatformUserView load(UUID userId, UUID tenantId) {
        return jdbc.query("""
                select u.id, u.username, u.email, u.display_name, u.status, u.must_change_password,
                       u.failed_login_count, u.locked_until, u.last_login_at,
                       a.tenant_id, t.code tenant_code, t.display_name tenant_name, a.role_code
                  from platform_user u
                  join user_tenant_authority a on a.user_id=u.id and a.tenant_id=? and a.status='ACTIVE'
                  join tenant t on t.id=a.tenant_id
                 where u.id=? and u.platform_role is null
                """, (result, row) -> userView(result), tenantId, userId).stream().findFirst()
                .orElseThrow(() -> new DomainException("账号权限保存失败"));
    }

    private void revokeSessions(UUID userId) {
        jdbc.update("update platform_user set auth_version=auth_version+1, updated_at=now() where id=?", userId);
        tokens.revokeAll(userId);
    }

    private static PlatformUserView userView(ResultSet result) throws SQLException {
        return new PlatformUserView(result.getObject("id", UUID.class), result.getString("username"),
                result.getString("email"), result.getString("display_name"), result.getString("status"),
                result.getBoolean("must_change_password"), result.getInt("failed_login_count"),
                instant(result, "locked_until"), instant(result, "last_login_at"),
                result.getObject("tenant_id", UUID.class), result.getString("tenant_code"),
                result.getString("tenant_name"), result.getString("role_code"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column) == null ? null : result.getTimestamp(column).toInstant();
    }

    private static void validateRole(String roleCode) {
        if (!ROLES.contains(roleCode)) throw new IllegalArgumentException("不支持的岗位角色");
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return email.strip().toLowerCase(Locale.ROOT);
    }

    record CreatePlatformUserRequest(
            @NotNull UUID tenantId,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}") String username,
            @NotBlank @Size(max = 120) String displayName,
            @Email @Size(max = 254) String email,
            @NotBlank String roleCode) { }
    record ChangeRoleRequest(@NotNull UUID tenantId, @NotBlank String roleCode) { }
    record ChangeStatusRequest(@NotBlank String status) { }
    record CreatedPlatformUser(PlatformUserView user, String temporaryPassword) { }
    record ResetPasswordResult(PlatformUserView user, String temporaryPassword) { }
    record PlatformUserView(UUID id, String username, String email, String displayName, String status,
                            boolean mustChangePassword, int failedLoginCount, Instant lockedUntil,
                            Instant lastLoginAt, UUID tenantId, String tenantCode, String tenantName,
                            String roleCode) { }
    record LoginEventView(Instant occurredAt, String username, String sourceIp,
                          String result, String failureReason) { }
    private record ManagedUser(String platformRole, UUID tenantId) { }
}
