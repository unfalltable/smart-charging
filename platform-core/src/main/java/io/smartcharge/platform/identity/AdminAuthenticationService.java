package io.smartcharge.platform.identity;

import io.smartcharge.platform.shared.domain.AuthenticationFailureException;
import io.smartcharge.platform.shared.web.RequestContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AdminAuthenticationService {
    private static final int MAX_FAILURES = 5;
    private static final int LOCK_MINUTES = 15;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final AdminTokenService tokens;
    private final String dummyPasswordHash;

    AdminAuthenticationService(JdbcTemplate jdbc, TransactionTemplate transactions,
                               PasswordEncoder passwords, AdminTokenService tokens) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    AdminTokenService.Session login(String suppliedUsername, String password) {
        String username = suppliedUsername.strip().toLowerCase(Locale.ROOT);
        AuthenticationOutcome outcome = transactions.execute(status -> {
            LoginAccount account = jdbc.query("""
                    select id, subject, username, display_name, status, platform_role, password_hash,
                           must_change_password, failed_login_count, locked_until, auth_version
                      from platform_user where lower(username)=lower(?) for update
                    """, (result, row) -> new LoginAccount(
                    result.getObject("id", UUID.class), result.getString("subject"),
                    result.getString("username"), result.getString("display_name"),
                    result.getString("status"), result.getString("platform_role"),
                    result.getString("password_hash"), result.getBoolean("must_change_password"),
                    result.getInt("failed_login_count"),
                    result.getTimestamp("locked_until") == null ? null : result.getTimestamp("locked_until").toInstant(),
                    result.getLong("auth_version")), username).stream().findFirst().orElse(null);
            if (account == null) {
                passwords.matches(password, dummyPasswordHash);
                loginEvent(null, username, "FAILURE", "BAD_CREDENTIALS");
                return AuthenticationOutcome.failure(invalidCredentials());
            }
            if (!"ACTIVE".equals(account.status())) {
                loginEvent(account.id(), username, "FAILURE", "ACCOUNT_DISABLED");
                return AuthenticationOutcome.failure(new AuthenticationFailureException("账号已停用，请联系管理员"));
            }
            if (account.lockedUntil() != null && account.lockedUntil().isAfter(Instant.now())) {
                loginEvent(account.id(), username, "LOCKED", "TOO_MANY_ATTEMPTS");
                return AuthenticationOutcome.failure(
                        new AuthenticationFailureException("登录失败次数过多，请在15分钟后重试"));
            }
            if (account.passwordHash() == null || !passwords.matches(password, account.passwordHash())) {
                int failures = (account.lockedUntil() == null ? account.failedLoginCount() : 0) + 1;
                Instant lockedUntil = failures >= MAX_FAILURES
                        ? Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES) : null;
                jdbc.update("""
                        update platform_user set failed_login_count=?, locked_until=?, updated_at=now()
                         where id=?
                        """, failures, lockedUntil == null ? null : java.sql.Timestamp.from(lockedUntil), account.id());
                loginEvent(account.id(), username, lockedUntil == null ? "FAILURE" : "LOCKED",
                        lockedUntil == null ? "BAD_CREDENTIALS" : "TOO_MANY_ATTEMPTS");
                return AuthenticationOutcome.failure(invalidCredentials());
            }
            jdbc.update("""
                    update platform_user set failed_login_count=0, locked_until=null,
                           last_login_at=now(), updated_at=now() where id=?
                    """, account.id());
            loginEvent(account.id(), username, "SUCCESS", null);
            return AuthenticationOutcome.success(tokens.issue(account.tokenAccount(), account.mustChangePassword()));
        });
        if (outcome == null) throw new IllegalStateException("Authentication transaction returned no result");
        if (outcome.failure() != null) throw outcome.failure();
        return outcome.session();
    }

    AdminTokenService.Session refresh(String refreshToken) {
        AuthenticationOutcome outcome = transactions.execute(status -> {
            try {
                return AuthenticationOutcome.success(tokens.rotate(refreshToken));
            } catch (AuthenticationFailureException failure) {
                return AuthenticationOutcome.failure(failure);
            }
        });
        if (outcome == null) throw new IllegalStateException("Refresh transaction returned no result");
        if (outcome.failure() != null) throw outcome.failure();
        return outcome.session();
    }

    AdminTokenService.Session changePassword(UUID userId, String currentPassword, String newPassword) {
        validateNewPassword(newPassword);
        return transactions.execute(status -> {
            LoginAccount account = jdbc.query("""
                    select id, subject, username, display_name, status, platform_role, password_hash,
                           must_change_password, failed_login_count, locked_until, auth_version
                      from platform_user where id=? for update
                    """, (result, row) -> new LoginAccount(
                    result.getObject("id", UUID.class), result.getString("subject"),
                    result.getString("username"), result.getString("display_name"),
                    result.getString("status"), result.getString("platform_role"),
                    result.getString("password_hash"), result.getBoolean("must_change_password"),
                    result.getInt("failed_login_count"), null, result.getLong("auth_version")), userId)
                    .stream().findFirst().orElseThrow(() -> new AuthenticationFailureException("账号不存在"));
            if (!passwords.matches(currentPassword, account.passwordHash())) throw invalidCredentials();
            if (passwords.matches(newPassword, account.passwordHash())) {
                throw new IllegalArgumentException("新密码不能与当前密码相同");
            }
            jdbc.update("""
                    update platform_user set password_hash=?, must_change_password=false,
                           password_changed_at=now(), auth_version=auth_version+1,
                           failed_login_count=0, locked_until=null, updated_at=now(), version=version+1
                     where id=?
                    """, passwords.encode(newPassword), userId);
            tokens.revokeAll(userId);
            AdminTokenService.Account updated = tokens.loadActive(userId);
            return tokens.issue(updated, false);
        });
    }

    void logout(UUID userId, String refreshToken) {
        transactions.executeWithoutResult(status -> tokens.revoke(userId, refreshToken));
    }

    private void loginEvent(UUID userId, String username, String result, String reason) {
        jdbc.update("""
                insert into admin_login_event
                    (id, user_id, username, source_ip, result, failure_reason)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, username, RequestContext.sourceIp(), result, reason);
    }

    private static AuthenticationFailureException invalidCredentials() {
        return new AuthenticationFailureException("用户名或密码错误");
    }

    private static void validateNewPassword(String password) {
        if (password == null || password.length() < 12
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("新密码至少12个字符，UTF-8长度不能超过72字节");
        }
        boolean upper = password.chars().anyMatch(Character::isUpperCase);
        boolean lower = password.chars().anyMatch(Character::isLowerCase);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        boolean symbol = password.chars().anyMatch(value -> !Character.isLetterOrDigit(value));
        if (!(upper && lower && digit && symbol)) {
            throw new IllegalArgumentException("新密码必须同时包含大写字母、小写字母、数字和特殊字符");
        }
    }

    private record LoginAccount(UUID id, String subject, String username, String displayName,
                                String status, String platformRole, String passwordHash,
                                boolean mustChangePassword, int failedLoginCount,
                                Instant lockedUntil, long authVersion) {
        AdminTokenService.Account tokenAccount() {
            return new AdminTokenService.Account(id, subject, username, displayName,
                    platformRole, mustChangePassword, authVersion);
        }
    }

    private record AuthenticationOutcome(AdminTokenService.Session session,
                                         AuthenticationFailureException failure) {
        static AuthenticationOutcome success(AdminTokenService.Session session) {
            return new AuthenticationOutcome(session, null);
        }

        static AuthenticationOutcome failure(AuthenticationFailureException failure) {
            return new AuthenticationOutcome(null, failure);
        }
    }
}
