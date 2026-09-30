package io.smartcharge.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.smartcharge.platform.DatabaseTestSupport;
import io.smartcharge.platform.shared.domain.AuthenticationFailureException;
import io.smartcharge.platform.tenancy.TenantContext;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CustomerSessionIntegrationTest {
    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void reusedRefreshCommitsRevocationAndInvalidatesAlreadyIssuedAccess() throws Exception {
        var db = DatabaseTestSupport.database();
        var fixture = db.seedCharging();
        var runtime = db.runtimeJdbc();
        var properties = new TokenProperties("https://verification.invalid", Base64.getEncoder().encodeToString(
                new byte[32]), "charging-verification", 15, 30);
        var configuration = new TokenConfiguration();
        var secret = configuration.appJwtSecret(properties);
        var tokens = new TokenService(runtime.jdbc(), configuration.appJwtEncoder(secret), properties);
        var controller = new MiniappAuthController(runtime.jdbc(), runtime.tenants(), List.of(), tokens);
        var initial = runtime.tenants().readWriteAs(fixture.tenantId(), () -> tokens.issue(
                fixture.tenantId(), fixture.customerId()));
        var rotated = controller.refresh(new MiniappAuthController.RefreshRequest(
                fixture.tenantId(), initial.refreshToken()));
        TenantContext.set(fixture.tenantId());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                configuration.jwtDecoder(secret, properties).decode(rotated.accessToken())));
        var current = new CurrentCustomer(runtime.jdbc(), runtime.tenants());
        assertThat(current.requireId()).isEqualTo(fixture.customerId());
        assertThatThrownBy(() -> controller.refresh(new MiniappAuthController.RefreshRequest(
                fixture.tenantId(), initial.refreshToken()))).isInstanceOf(AuthenticationFailureException.class);
        assertThat(db.ownerJdbc().queryForObject("select count(*) from auth_refresh_token where tenant_id=? "
                + "and revoked_at is null", Long.class, fixture.tenantId())).isZero();
        assertThatThrownBy(current::requireId).isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void logoutWithConsumedTokenRevokesTheWholeRotatedFamily() throws Exception {
        var db = DatabaseTestSupport.database();
        var fixture = db.seedCharging();
        var runtime = db.runtimeJdbc();
        var properties = new TokenProperties("https://verification.invalid", Base64.getEncoder().encodeToString(
                new byte[32]), "charging-verification", 15, 30);
        var configuration = new TokenConfiguration();
        var tokens = new TokenService(runtime.jdbc(), configuration.appJwtEncoder(
                configuration.appJwtSecret(properties)), properties);
        var controller = new MiniappAuthController(runtime.jdbc(), runtime.tenants(), List.of(), tokens);
        var initial = runtime.tenants().readWriteAs(fixture.tenantId(), () -> tokens.issue(
                fixture.tenantId(), fixture.customerId()));
        var rotated = controller.refresh(new MiniappAuthController.RefreshRequest(fixture.tenantId(), initial.refreshToken()));
        controller.logout(new MiniappAuthController.RefreshRequest(fixture.tenantId(), initial.refreshToken()));
        assertThatThrownBy(() -> controller.refresh(new MiniappAuthController.RefreshRequest(
                fixture.tenantId(), rotated.refreshToken()))).isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void disabledCustomerCannotObtainAnotherSession() throws Exception {
        var db = DatabaseTestSupport.database();
        var fixture = db.seedCharging();
        var runtime = db.runtimeJdbc();
        var properties = new TokenProperties("https://verification.invalid", Base64.getEncoder().encodeToString(
                new byte[32]), "charging-verification", 15, 30);
        var configuration = new TokenConfiguration();
        var tokens = new TokenService(runtime.jdbc(), configuration.appJwtEncoder(
                configuration.appJwtSecret(properties)), properties);
        var initial = runtime.tenants().readWriteAs(fixture.tenantId(), () -> tokens.issue(
                fixture.tenantId(), fixture.customerId()));
        db.ownerJdbc().update("update customer set status='BLOCKED' where id=?", fixture.customerId());
        TenantContext.set(fixture.tenantId());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(configuration.jwtDecoder(
                configuration.appJwtSecret(properties), properties).decode(initial.accessToken())));
        var current = new CurrentCustomer(runtime.jdbc(), runtime.tenants());
        assertThatThrownBy(current::requireId).isInstanceOf(AuthenticationFailureException.class);
        assertThat(current.requireIdForStop()).isEqualTo(fixture.customerId());
        var controller = new MiniappAuthController(runtime.jdbc(), runtime.tenants(), List.of(), tokens);
        assertThatThrownBy(() -> controller.refresh(new MiniappAuthController.RefreshRequest(
                fixture.tenantId(), initial.refreshToken()))).isInstanceOf(AuthenticationFailureException.class);
    }
}
