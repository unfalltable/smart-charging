package io.smartcharge.platform.shared.config;

import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import io.smartcharge.platform.tenancy.PlatformAuthority;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class TenantAccessFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PlatformAuthority platformAuthority;

    public TenantAccessFilter(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                              PlatformAuthority platformAuthority) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.platformAuthority = platformAuthority;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/v1/admin/") || path.startsWith("/api/v1/operations/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID tenantId = TenantContext.requireTenantId();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String subject = authentication == null ? "" : authentication.getName();
        String path = request.getRequestURI();
        if (platformAuthority.isPlatformAdministrator(authentication)) {
            boolean activeTenant = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from tenant where id=? and status='ACTIVE')",
                    Boolean.class, tenantId));
            if (activeTenant) {
                chain.doFilter(request, response);
                return;
            }
            deny(response, "The selected tenant is not active");
            return;
        }
        String roleClause;
        if (path.startsWith("/api/v1/admin/access/")) {
            roleClause = "m.role_code='TENANT_ADMIN'";
        } else if (path.startsWith("/api/v1/admin/legal/")) {
            roleClause = "m.role_code='TENANT_ADMIN'";
        } else if (path.startsWith("/api/v1/admin/finance/")) {
            roleClause = "m.role_code in ('TENANT_ADMIN','FINANCE')";
        } else if (path.equals("/api/v1/admin/operations/audit")) {
            roleClause = "m.role_code in ('TENANT_ADMIN','AUDITOR')";
        } else if (path.startsWith("/api/v1/admin/operations/work-orders")) {
            roleClause = "m.role_code in ('TENANT_ADMIN','OPERATOR','SUPPORT')";
        } else {
            roleClause = "m.role_code in ('TENANT_ADMIN','OPERATOR')";
        }
        boolean allowed = tenantJdbc.readWriteAs(tenantId, () -> Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from tenant_membership m
                    join platform_user u on u.id=m.user_id
                    where m.tenant_id=? and u.subject=? and u.status='ACTIVE'
                      and m.status='ACTIVE' and %s
                )
                """.formatted(roleClause), Boolean.class, tenantId, subject)));
        if (!allowed) {
            deny(response, "No active tenant role grants this operation");
            return;
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"TENANT_ACCESS_DENIED\",\"message\":\"" + message + "\"}");
    }
}
