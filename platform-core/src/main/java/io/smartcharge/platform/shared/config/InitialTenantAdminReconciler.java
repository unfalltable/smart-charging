package io.smartcharge.platform.shared.config;

import io.smartcharge.platform.shared.web.RequestContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
final class InitialTenantAdminReconciler {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final String identityProviderMode;
    private final String initialAdminUsername;
    private final UUID initialTenantId;

    InitialTenantAdminReconciler(
            JdbcTemplate jdbc,
            TenantJdbcExecutor tenantJdbc,
            @Value("${charging.security.identity-provider-mode:external}") String identityProviderMode,
            @Value("${charging.security.initial-admin-username:}") String initialAdminUsername,
            @Value("${charging.security.initial-tenant-id:}") String initialTenantId) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.identityProviderMode = identityProviderMode;
        this.initialAdminUsername = initialAdminUsername;
        this.initialTenantId = initialTenantId.isBlank() ? null : UUID.fromString(initialTenantId);
    }

    boolean reconcile(UUID tenantId, Authentication authentication) {
        if (!eligible(tenantId, authentication)) return false;
        JwtAuthenticationToken jwt = (JwtAuthenticationToken) authentication;
        String subject = jwt.getToken().getSubject();
        String displayName = jwt.getToken().getClaimAsString("name");
        if (displayName == null || displayName.isBlank()) displayName = initialAdminUsername;
        String resolvedDisplayName = displayName;
        return tenantJdbc.readWriteAs(tenantId, () -> {
            boolean activeTenant = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from tenant where id=? and status='ACTIVE')",
                    Boolean.class, tenantId));
            if (!activeTenant) return false;
            UUID userId = jdbc.queryForObject("""
                    insert into platform_user (id, subject, display_name, status)
                    values (?, ?, ?, 'ACTIVE')
                    on conflict (subject) do update
                       set display_name=coalesce(platform_user.display_name, excluded.display_name),
                           updated_at=now()
                    returning id
                    """, UUID.class, UUID.randomUUID(), subject, resolvedDisplayName);
            String userStatus = jdbc.queryForObject(
                    "select status from platform_user where id=?", String.class, userId);
            if (!"ACTIVE".equals(userStatus)) return false;
            int inserted = jdbc.update("""
                    insert into tenant_membership (id, tenant_id, user_id, role_code, status)
                    values (?, ?, ?, 'TENANT_ADMIN', 'ACTIVE')
                    on conflict (tenant_id, user_id, role_code) do nothing
                    """, UUID.randomUUID(), tenantId, userId);
            if (inserted == 1) recordReconciliation(tenantId, subject);
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (
                        select 1 from tenant_membership
                         where tenant_id=? and user_id=? and role_code='TENANT_ADMIN' and status='ACTIVE'
                    )
                    """, Boolean.class, tenantId, userId));
        });
    }

    private boolean eligible(UUID tenantId, Authentication authentication) {
        if (!"bundled".equals(identityProviderMode) || initialTenantId == null
                || !initialTenantId.equals(tenantId)
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            return false;
        }
        boolean adminAuthority = authentication.getAuthorities().stream()
                .anyMatch(authority -> "SCOPE_admin".equals(authority.getAuthority()));
        String username = jwt.getToken().getClaimAsString("preferred_username");
        boolean configuredAdmin = !initialAdminUsername.isBlank() && initialAdminUsername.equals(username);
        String subject = jwt.getToken().getSubject();
        return (adminAuthority || configuredAdmin) && subject != null && !subject.isBlank();
    }

    private void recordReconciliation(UUID tenantId, String subject) {
        jdbc.update("""
                insert into audit_log
                    (id, tenant_id, actor_subject, action, resource_type, resource_id,
                     request_id, source_ip, before_data, after_data)
                values (?, ?, ?, 'INITIAL_TENANT_ADMIN_RECONCILED', 'tenant_membership', ?,
                        ?, cast(? as inet), null,
                        jsonb_build_object('username', ?, 'source', 'verified_oidc_token'))
                """, UUID.randomUUID(), tenantId, subject, tenantId.toString(),
                RequestContext.requestId(), RequestContext.sourceIp(), initialAdminUsername);
    }
}
