package io.smartcharge.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantContextFilterTest {
    private static final UUID DATABASE_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @AfterEach
    void cleanSecurityContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void platformAdministratorCanSelectTheDatabaseTenantWhenTheTokenClaimIsStale() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(new PlatformAuthority("bundled", "platform-admin"));
        SecurityContextHolder.getContext().setAuthentication(
                PlatformAuthorityTest.authentication("platform-admin", false));
        MockHttpServletRequest request = request(DATABASE_TENANT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> tenantInChain = new AtomicReference<>();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> tenantInChain.set(TenantContext.requireTenantId());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tenantInChain.get()).isEqualTo(DATABASE_TENANT);
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void ordinaryUserCannotSelectATenantMissingFromTheVerifiedClaim() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(new PlatformAuthority("bundled", "platform-admin"));
        SecurityContextHolder.getContext().setAuthentication(
                PlatformAuthorityTest.authentication("operator", false));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(DATABASE_TENANT), response, (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest request(UUID tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/operations/dashboard");
        request.addHeader("X-Tenant-Id", tenantId.toString());
        return request;
    }
}
