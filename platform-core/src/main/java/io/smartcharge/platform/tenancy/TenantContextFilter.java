package io.smartcharge.platform.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class TenantContextFilter extends OncePerRequestFilter {
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final Set<String> TENANT_FREE_PREFIXES = Set.of(
            "/actuator/", "/internal/", "/api/v1/public/", "/api/v1/auth/miniapp/");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return TENANT_FREE_PREFIXES.stream().anyMatch(request.getRequestURI()::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String rawTenantId = request.getHeader(TENANT_HEADER);
            if (rawTenantId == null || rawTenantId.isBlank()) {
                response.sendError(HttpStatus.BAD_REQUEST.value(), TENANT_HEADER + " is required");
                return;
            }
            UUID tenantId = UUID.fromString(rawTenantId);
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwt) {
                List<String> allowedTenants = jwt.getToken().getClaimAsStringList("tenant_ids");
                if (allowedTenants == null || !allowedTenants.contains(tenantId.toString())) {
                    response.sendError(HttpStatus.FORBIDDEN.value(), "Token has no access to this tenant");
                    return;
                }
            }
            TenantContext.set(tenantId);
            chain.doFilter(request, response);
        } catch (IllegalArgumentException invalidTenantId) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), TENANT_HEADER + " must be a UUID");
        } finally {
            TenantContext.clear();
        }
    }
}
