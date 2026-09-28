package io.smartcharge.platform.identity;

import io.smartcharge.platform.shared.domain.ServiceUnavailableException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "charging.security", name = "identity-provider-mode", havingValue = "database")
final class DatabaseIdentityAdminGateway implements IdentityAdminGateway {
    private static final char[] PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*+-_".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final IdentityAdminProperties properties;

    DatabaseIdentityAdminGateway(JdbcTemplate jdbc, PasswordEncoder passwords,
                                 IdentityAdminProperties properties) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false, true, false, properties.getInvitationLifespanHours());
    }

    @Override
    public ProvisionedIdentity provision(ProvisionIdentity request) {
        String username = request.username().strip().toLowerCase(Locale.ROOT);
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        LocalIdentity existing = jdbc.query("""
                select id, subject, username, email, password_hash
                  from platform_user
                 where lower(username)=lower(?) or lower(email)=lower(?)
                 order by case when lower(username)=lower(?) then 0 else 1 end
                 limit 1
                """, (result, row) -> new LocalIdentity(
                result.getObject("id", UUID.class), result.getString("subject"),
                result.getString("username"), result.getString("email"), result.getString("password_hash")),
                username, email, username).stream().findFirst().orElse(null);
        if (existing != null) {
            if (existing.username() != null && !existing.username().equalsIgnoreCase(username)) {
                throw new IllegalArgumentException("该邮箱已绑定其他登录用户名");
            }
            String temporaryPassword = null;
            if (existing.passwordHash() == null || existing.passwordHash().isBlank()) {
                temporaryPassword = newTemporaryPassword();
                jdbc.update("""
                        update platform_user set password_hash=?, must_change_password=true,
                               password_changed_at=now(), identity_managed=true,
                               username=?, email=?, display_name=?, status='ACTIVE',
                               auth_version=auth_version+1, updated_at=now(), version=version+1
                         where id=?
                        """, passwords.encode(temporaryPassword), username, email,
                        request.displayName().strip(), existing.id());
            }
            return new ProvisionedIdentity(existing.subject(), username, email, false, temporaryPassword);
        }
        UUID id = UUID.randomUUID();
        String subject = "admin:" + id;
        String temporaryPassword = newTemporaryPassword();
        jdbc.update("""
                insert into platform_user
                    (id, subject, username, email, display_name, status, identity_managed,
                     mfa_required, password_hash, must_change_password, password_changed_at)
                values (?, ?, ?, ?, ?, 'ACTIVE', true, false, ?, true, now())
                """, id, subject, username, email, request.displayName().strip(),
                passwords.encode(temporaryPassword));
        return new ProvisionedIdentity(subject, username, email, true, temporaryPassword);
    }

    @Override
    public void addTenantAuthorization(String subject, UUID tenantId, String roleCode, boolean requireMfa) {
        // Membership triggers maintain the token authority index in the same database transaction.
    }

    @Override
    public void sendInvitation(String subject) {
        throw new ServiceUnavailableException("内置账号模式未配置邮件服务，请安全交付一次性临时密码");
    }

    @Override
    public void sendRecovery(String subject, boolean resetMfa, boolean requireMfa) {
        throw new ServiceUnavailableException("内置账号模式未配置邮件服务，请生成一次性临时密码");
    }

    @Override
    public String resetTemporaryPassword(String subject, boolean requireMfa, boolean resetMfa) {
        String temporaryPassword = newTemporaryPassword();
        int changed = jdbc.update("""
                update platform_user set password_hash=?, must_change_password=true,
                       failed_login_count=0, locked_until=null, auth_version=auth_version+1,
                       password_changed_at=now(), updated_at=now(), version=version+1
                 where subject=? and status='ACTIVE'
                """, passwords.encode(temporaryPassword), subject);
        if (changed != 1) throw new IllegalArgumentException("账号不存在或已停用");
        UUID userId = jdbc.queryForObject("select id from platform_user where subject=?", UUID.class, subject);
        jdbc.update("update admin_refresh_token set revoked_at=coalesce(revoked_at,now()) where user_id=?", userId);
        return temporaryPassword;
    }

    @Override
    public void logout(String subject) {
        List<UUID> ids = jdbc.query("select id from platform_user where subject=?",
                (result, row) -> result.getObject(1, UUID.class), subject);
        if (ids.isEmpty()) return;
        jdbc.update("update admin_refresh_token set revoked_at=coalesce(revoked_at,now()) where user_id=?", ids.getFirst());
        jdbc.update("update platform_user set auth_version=auth_version+1, updated_at=now() where id=?", ids.getFirst());
    }

    @Override
    public void deleteIfCreated(ProvisionedIdentity identity) {
        if (identity.created()) jdbc.update("delete from platform_user where subject=?", identity.subject());
    }

    @Override
    public List<LoginEvent> loginEvents(int maximum) {
        int bounded = Math.max(1, Math.min(maximum, 200));
        return jdbc.query("""
                select e.occurred_at, e.result, u.subject, e.username, e.source_ip, e.failure_reason
                  from admin_login_event e left join platform_user u on u.id=e.user_id
                 order by e.occurred_at desc limit ?
                """, (result, row) -> {
            String outcome = result.getString("result");
            String failure = result.getString("failure_reason");
            return new LoginEvent(result.getTimestamp("occurred_at").toInstant(),
                    "SUCCESS".equals(outcome) ? "LOGIN" : "LOGIN_ERROR",
                    result.getString("subject"), result.getString("username"),
                    result.getString("source_ip"), "admin-web", failure,
                    "SUCCESS".equals(outcome) ? "NORMAL" : "WARNING");
        }, bounded);
    }

    private static String newTemporaryPassword() {
        StringBuilder password = new StringBuilder("Aa7!");
        while (password.length() < 20) password.append(PASSWORD_CHARS[RANDOM.nextInt(PASSWORD_CHARS.length)]);
        List<Character> shuffled = new ArrayList<>();
        password.chars().mapToObj(value -> (char) value).forEach(shuffled::add);
        java.util.Collections.shuffle(shuffled, RANDOM);
        StringBuilder result = new StringBuilder(20);
        shuffled.forEach(result::append);
        return result.toString();
    }

    private record LocalIdentity(UUID id, String subject, String username, String email, String passwordHash) { }
}
