package io.smartcharge.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.tenancy.PlatformAuthority;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PlatformTenantManagementControllerTest {
    @Test
    void exposesRealCrossTenantOperatingAndServiceFeeFigures() throws Exception {
        UUID tenantId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);
        ResultSet tenant = mock(ResultSet.class);
        when(tenant.getObject("id", UUID.class)).thenReturn(tenantId);
        when(tenant.getString("code")).thenReturn("north-region");
        when(tenant.getString("display_name")).thenReturn("北区运营商");
        when(tenant.getString("status")).thenReturn("ACTIVE");
        when(tenant.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
        when(tenant.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-02T00:00:00Z")));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PlatformTenantManagementController.PlatformTenantView>>any()))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<PlatformTenantManagementController.PlatformTenantView>>getArgument(1)
                        .mapRow(tenant, 0)));
        when(tenantJdbc.readWriteAs(eq(tenantId), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());

        ResultSet counts = mock(ResultSet.class);
        when(counts.getLong("active_members")).thenReturn(3L);
        when(counts.getLong("stations")).thenReturn(2L);
        when(counts.getLong("devices")).thenReturn(12L);
        when(counts.getLong("online_devices")).thenReturn(10L);
        when(counts.getLong("successful_payments_today")).thenReturn(41L);
        when(counts.getLong("today_net_revenue_minor")).thenReturn(20500L);
        when(counts.getLong("confirmed_platform_service_fee_minor")).thenReturn(1800L);
        when(counts.getLong("pending_platform_service_fee_minor")).thenReturn(600L);
        when(jdbc.queryForObject(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq(tenantId), any(Timestamp.class)))
                .thenAnswer(invocation -> invocation.<RowMapper<?>>getArgument(1).mapRow(counts, 0));

        PlatformTenantManagementController controller = new PlatformTenantManagementController(
                jdbc, tenantJdbc, mock(TenantProvisioningController.class), mock(IdentityAdminGateway.class),
                new PlatformAuthority(), mock(AuditService.class));

        var rows = controller.list(platformAdministrator());

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().todayNetRevenueMinor()).isEqualTo(20500L);
        assertThat(rows.getFirst().confirmedPlatformServiceFeeMinor()).isEqualTo(1800L);
        assertThat(rows.getFirst().pendingPlatformServiceFeeMinor()).isEqualTo(600L);
        assertThat(rows.getFirst().onlineDevices()).isEqualTo(10L);
    }

    private JwtAuthenticationToken platformAdministrator() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("platform-admin")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("scope", "platform_admin")
                .build();
        return new JwtAuthenticationToken(jwt,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_platform_admin")));
    }
}
