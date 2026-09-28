package io.smartcharge.platform.identity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix = "charging.security", name = "identity-provider-mode", havingValue = "database")
final class LocalPlatformAdministratorBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final LocalAdminProperties properties;

    LocalPlatformAdministratorBootstrap(JdbcTemplate jdbc, TransactionTemplate transactions,
                                        PasswordEncoder passwords, LocalAdminProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        validate();
        transactions.executeWithoutResult(status -> bootstrap());
    }

    private void bootstrap() {
        String username = properties.getUsername().strip().toLowerCase(Locale.ROOT);
        AdminRow superAdmin = jdbc.query("""
                select id, password_hash from platform_user
                 where platform_role='PLATFORM_SUPER_ADMIN' for update
                """, (result, row) -> new AdminRow(
                result.getObject("id", UUID.class), result.getString("password_hash")))
                .stream().findFirst().orElse(null);
        if (superAdmin == null) {
            superAdmin = jdbc.query("""
                    select id, password_hash from platform_user where lower(username)=lower(?) for update
                    """, (result, row) -> new AdminRow(
                    result.getObject("id", UUID.class), result.getString("password_hash")), username)
                    .stream().findFirst().orElse(null);
        }
        if (superAdmin == null) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into platform_user
                        (id, subject, username, email, display_name, status, identity_managed,
                         password_hash, platform_role, must_change_password, password_changed_at)
                    values (?, ?, ?, nullif(?,''), ?, 'ACTIVE', true, ?,
                            'PLATFORM_SUPER_ADMIN', true, ?)
                    """, id, properties.getSubject(), username, properties.getEmail().strip(),
                    properties.getDisplayName().strip(), passwords.encode(properties.getPassword()),
                    java.sql.Timestamp.from(Instant.now()));
            return;
        }
        String passwordHash = superAdmin.passwordHash();
        boolean initializePassword = passwordHash == null || passwordHash.isBlank();
        jdbc.update("""
                update platform_user
                   set username=?, email=nullif(?,''), display_name=?, status='ACTIVE',
                       identity_managed=true, platform_role='PLATFORM_SUPER_ADMIN',
                       password_hash=case when ? then ? else password_hash end,
                       must_change_password=case when ? then true else must_change_password end,
                       password_changed_at=case when ? then now() else password_changed_at end,
                       updated_at=now(), version=version+1
                 where id=?
                """, username, properties.getEmail().strip(), properties.getDisplayName().strip(),
                initializePassword, initializePassword ? passwords.encode(properties.getPassword()) : "",
                initializePassword, initializePassword, superAdmin.id());
    }

    private void validate() {
        if (!properties.getSubject().matches("[A-Za-z0-9:_-]{8,160}")) {
            throw new IllegalStateException("PLATFORM_ADMIN_SUBJECT must be a stable identifier");
        }
        if (!properties.getUsername().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw new IllegalStateException("PLATFORM_ADMIN_USERNAME must contain 3 to 64 safe characters");
        }
        String password = properties.getPassword();
        if (password.length() < 12 || !password.chars().anyMatch(Character::isUpperCase)
                || !password.chars().anyMatch(Character::isLowerCase)
                || !password.chars().anyMatch(Character::isDigit)
                || !password.chars().anyMatch(value -> !Character.isLetterOrDigit(value))) {
            throw new IllegalStateException(
                    "PLATFORM_ADMIN_PASSWORD must contain at least 12 characters with upper/lower case, number and symbol");
        }
        if (properties.getDisplayName().isBlank()) {
            throw new IllegalStateException("PLATFORM_ADMIN_DISPLAY_NAME is required");
        }
    }

    private record AdminRow(UUID id, String passwordHash) { }
}
