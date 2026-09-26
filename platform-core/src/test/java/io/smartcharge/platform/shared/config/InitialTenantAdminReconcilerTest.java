package io.smartcharge.platform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class InitialTenantAdminReconcilerTest {
    private final UUID tenantId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private TenantJdbcExecutor tenantJdbc;
    private InitialTenantAdminReconciler reconciler;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tenantJdbc = mock(TenantJdbcExecutor.class);
        reconciler = new InitialTenantAdminReconciler(
                jdbc, tenantJdbc, "bundled", "platform-admin", tenantId.toString());
        when(tenantJdbc.readWriteAs(eq(tenantId), ArgumentMatchers.<Supplier<Boolean>>any()))
                .thenAnswer(invocation -> invocation.<Supplier<Boolean>>getArgument(1).get());
    }

    @Test
    void bindsTheVerifiedBundledAdministratorSubjectToTheInitialTenant() {
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(argThat(sql -> sql != null && sql.contains("from tenant where id")),
                eq(Boolean.class), eq(tenantId))).thenReturn(true);
        when(jdbc.queryForObject(argThat(sql -> sql != null && sql.contains("insert into platform_user")),
                eq(UUID.class), any(UUID.class), eq("real-keycloak-subject"), eq("Platform Owner")))
                .thenReturn(userId);
        when(jdbc.queryForObject(argThat(sql -> sql != null && sql.contains("from platform_user where id")),
                eq(String.class), eq(userId))).thenReturn("ACTIVE");
        when(jdbc.update(argThat(sql -> sql != null && sql.contains("insert into tenant_membership")),
                any(UUID.class), eq(tenantId), eq(userId))).thenReturn(1);
        when(jdbc.queryForObject(argThat(sql -> sql != null && sql.contains("role_code='TENANT_ADMIN'")),
                eq(Boolean.class), eq(tenantId), eq(userId))).thenReturn(true);

        boolean reconciled = reconciler.reconcile(tenantId, authentication(true, "platform-admin"));

        assertThat(reconciled).isTrue();
        verify(jdbc).update(argThat(sql -> sql != null && sql.contains("INITIAL_TENANT_ADMIN_RECONCILED")),
                any(UUID.class), eq(tenantId), eq("real-keycloak-subject"), eq(tenantId.toString()),
                any(), any(), eq("platform-admin"));
    }

    @Test
    void rejectsAUserWithoutTheTrustedAdminRole() {
        boolean reconciled = reconciler.reconcile(tenantId, authentication(false, "platform-admin"));

        assertThat(reconciled).isFalse();
        verify(tenantJdbc, never()).readWriteAs(any(UUID.class), any());
    }

    @Test
    void rejectsAnAdminWhoseVerifiedUsernameDoesNotMatch() {
        boolean reconciled = reconciler.reconcile(tenantId, authentication(true, "another-user"));

        assertThat(reconciled).isFalse();
        verify(tenantJdbc, never()).readWriteAs(any(UUID.class), any());
    }

    private JwtAuthenticationToken authentication(boolean admin, String username) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("real-keycloak-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("preferred_username", username)
                .claim("name", "Platform Owner")
                .build();
        List<SimpleGrantedAuthority> authorities = admin
                ? List.of(new SimpleGrantedAuthority("SCOPE_admin")) : List.of();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
