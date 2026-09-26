package io.smartcharge.platform.identity;

import io.smartcharge.platform.tenancy.PlatformAuthority;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
final class SessionController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PlatformAuthority platformAuthority;

    SessionController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, PlatformAuthority platformAuthority) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.platformAuthority = platformAuthority;
    }

    @GetMapping
    SessionView current(Authentication authentication) {
        String subject = authentication.getName();
        boolean platformAdministrator = platformAuthority.isPlatformAdministrator(authentication);
        List<TenantView> tenants = platformAdministrator
                ? platformTenants()
                : memberTenants(authentication, subject);
        return new SessionView(subject, platformAdministrator, tenants);
    }

    private List<TenantView> platformTenants() {
        return jdbc.query("""
                select id, code, display_name
                  from tenant
                 where status='ACTIVE'
                 order by display_name, code
                """, (result, row) -> new TenantView(
                result.getObject("id", UUID.class),
                result.getString("code"),
                result.getString("display_name"),
                List.of("PLATFORM_ADMIN")));
    }

    private List<TenantView> memberTenants(Authentication authentication, String subject) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) return List.of();
        Object tenantClaim = jwt.getToken().getClaim("tenant_ids");
        if (!(tenantClaim instanceof Collection<?> claimValues)) return List.of();
        List<TenantView> tenants = new ArrayList<>();
        claimValues.stream().map(String::valueOf).distinct().forEach(rawTenantId -> {
            try {
                UUID tenantId = UUID.fromString(rawTenantId);
                TenantView tenant = tenantJdbc.readWriteAs(tenantId, () -> memberTenant(tenantId, subject));
                if (tenant != null) tenants.add(tenant);
            } catch (IllegalArgumentException ignored) {
                // A malformed signed claim grants no tenant access.
            }
        });
        return List.copyOf(tenants);
    }

    private TenantView memberTenant(UUID tenantId, String subject) {
        List<TenantRole> rows = jdbc.query("""
                select t.id, t.code, t.display_name, m.role_code
                  from tenant t
                  join tenant_membership m on m.tenant_id=t.id and m.status='ACTIVE'
                  join platform_user u on u.id=m.user_id and u.status='ACTIVE'
                 where t.id=? and t.status='ACTIVE' and u.subject=?
                 order by m.role_code
                """, (result, row) -> new TenantRole(
                result.getObject("id", UUID.class),
                result.getString("code"),
                result.getString("display_name"),
                result.getString("role_code")), tenantId, subject);
        if (rows.isEmpty()) return null;
        TenantRole first = rows.getFirst();
        List<String> roles = List.copyOf(new LinkedHashSet<>(rows.stream().map(TenantRole::role).toList()));
        return new TenantView(first.id(), first.code(), first.displayName(), roles);
    }

    record SessionView(String subject, boolean platformAdministrator, List<TenantView> tenants) { }
    record TenantView(UUID id, String code, String displayName, List<String> roles) { }
    private record TenantRole(UUID id, String code, String displayName, String role) { }
}
