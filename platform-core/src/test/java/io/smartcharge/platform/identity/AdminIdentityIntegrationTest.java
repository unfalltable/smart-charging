package io.smartcharge.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.smartcharge.platform.DatabaseTestSupport;
import io.smartcharge.platform.shared.domain.AuthenticationFailureException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminIdentityIntegrationTest {
    @Test
    void assignedAdministratorCanLoginChangePasswordAndRejectReusedRefresh() throws Exception {
        var db = DatabaseTestSupport.database();
        var fixture = db.seedCharging();
        var jdbc = db.ownerJdbc();
        var runtime = db.runtimeJdbc();
        UUID user = UUID.randomUUID();
        String username = "verify-" + user;
        var passwords = new BCryptPasswordEncoder();
        String initialPassword = "Verification-Password-329!";
        jdbc.update("""
                insert into platform_user(id,subject,username,display_name,status,password_hash,must_change_password)
                values (?,?,?,'Verification administrator','ACTIVE',?,true)
                """, user, "local:" + user, username, passwords.encode(initialPassword));
        jdbc.update("""
                insert into tenant_membership(id,tenant_id,user_id,role_code,status,accepted_at)
                values (?,?,?,'TENANT_ADMIN','ACTIVE',now())
                """, UUID.randomUUID(), fixture.tenantId(), user);
        var properties = new TokenProperties("https://verification.invalid", Base64.getEncoder().encodeToString(
                new byte[32]), "charging-verification", 15, 30);
        var config = new TokenConfiguration();
        var secret = config.appJwtSecret(properties);
        var tokens = new AdminTokenService(runtime.jdbc(), config.appJwtEncoder(secret), properties);
        var auth = new AdminAuthenticationService(runtime.jdbc(), runtime.transactions(), passwords, tokens);
        var initial = auth.login(username, initialPassword);
        assertThat(initial.mustChangePassword()).isTrue();
        assertThat(initial.refreshToken()).isNull();
        assertThat(config.jwtDecoder(secret, properties).decode(initial.accessToken()).getClaimAsString("scope"))
                .isEqualTo("password_change");
        var changed = auth.changePassword(user, initialPassword, "Updated-Verification-Password-573!");
        assertThat(changed.mustChangePassword()).isFalse();
        assertThat(config.jwtDecoder(secret, properties).decode(changed.accessToken()).getClaimAsStringList("tenant_ids"))
                .containsExactly(fixture.tenantId().toString());
        assertThat(config.jwtDecoder(secret, properties).decode(changed.accessToken()).getClaimAsString("scope"))
                .isEqualTo("admin");
        var refreshed = auth.refresh(changed.refreshToken());
        assertThatThrownBy(() -> auth.refresh(changed.refreshToken())).isInstanceOf(AuthenticationFailureException.class);
        assertThatThrownBy(() -> auth.refresh(refreshed.refreshToken())).isInstanceOf(AuthenticationFailureException.class);
        assertThat(jdbc.queryForObject("select auth_version from platform_user where id=?", Long.class, user))
                .isGreaterThan(1);
        assertThatThrownBy(() -> auth.login(username, initialPassword)).isInstanceOf(AuthenticationFailureException.class);
        assertThat(auth.login(username, "Updated-Verification-Password-573!").refreshToken()).isNotBlank();
    }

    @Test
    void expiredLoginLockStartsANewAttemptWindow() throws Exception {
        var db = DatabaseTestSupport.database();
        var runtime = db.runtimeJdbc();
        UUID user = UUID.randomUUID();
        String username = "verify-" + user;
        var passwords = new BCryptPasswordEncoder();
        db.ownerJdbc().update("""
                insert into platform_user(id,subject,username,display_name,status,password_hash,
                                          failed_login_count,locked_until)
                values (?,?,?,'Verification lock','ACTIVE',?,5,now()-interval '1 second')
                """, user, "local:" + user, username, passwords.encode("Verification-Password-329!"));
        var properties = new TokenProperties("https://verification.invalid", Base64.getEncoder().encodeToString(
                new byte[32]), "charging-verification", 15, 30);
        var config = new TokenConfiguration();
        var auth = new AdminAuthenticationService(runtime.jdbc(), runtime.transactions(), passwords,
                new AdminTokenService(runtime.jdbc(), config.appJwtEncoder(config.appJwtSecret(properties)), properties));
        assertThatThrownBy(() -> auth.login(username, "wrong-password")).isInstanceOf(AuthenticationFailureException.class);
        assertThat(db.ownerJdbc().queryForObject("select failed_login_count from platform_user where id=?",
                Integer.class, user)).isEqualTo(1);
        assertThat(db.ownerJdbc().queryForObject("select locked_until is null from platform_user where id=?",
                Boolean.class, user)).isTrue();
        assertThatThrownBy(() -> auth.changePassword(user, "irrelevant", "Aa1!" + "密".repeat(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("72");
    }
}
