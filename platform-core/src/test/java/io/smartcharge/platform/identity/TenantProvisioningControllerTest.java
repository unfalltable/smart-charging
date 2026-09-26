package io.smartcharge.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.identity.TenantProvisioningController.ProvisionTenantRequest;
import io.smartcharge.platform.identity.TenantProvisioningController.TenantIdentity;
import io.smartcharge.platform.identity.TenantProvisioningController.TenantResolution;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

class TenantProvisioningControllerTest {
    private JdbcTemplate jdbc;
    private TenantProvisioningController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        controller = new TenantProvisioningController(jdbc, mock(TransactionTemplate.class),
                mock(TenantJdbcExecutor.class), mock(AuditService.class));
    }

    @Test
    void reconcilesTheSameTenantInsteadOfCreatingADuplicate() {
        UUID tenantId = UUID.randomUUID();
        ProvisionTenantRequest request = request(tenantId, "gavin");
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<TenantIdentity>>any(),
                eq(tenantId), eq("gavin")))
                .thenReturn(List.of(new TenantIdentity(tenantId, "gavin")));

        TenantResolution resolution = controller.createOrReconcileTenant(tenantId, request);

        assertThat(resolution).isEqualTo(new TenantResolution(tenantId, false));
        verify(jdbc).update(anyString(), eq("Gavin"), eq(tenantId));
    }

    @Test
    void createsATenantWhenNeitherIdentityNorCodeExists() {
        UUID tenantId = UUID.randomUUID();
        ProvisionTenantRequest request = request(tenantId, "gavin");
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<TenantIdentity>>any(),
                eq(tenantId), eq("gavin")))
                .thenReturn(List.of());

        TenantResolution resolution = controller.createOrReconcileTenant(tenantId, request);

        assertThat(resolution).isEqualTo(new TenantResolution(tenantId, true));
        verify(jdbc).update(anyString(), eq(tenantId), eq("gavin"), eq("Gavin"));
    }

    @Test
    void rejectsReusingATenantIdentityForAnotherCode() {
        UUID tenantId = UUID.randomUUID();
        ProvisionTenantRequest request = request(tenantId, "gavin");
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<TenantIdentity>>any(),
                eq(tenantId), eq("gavin")))
                .thenReturn(List.of(new TenantIdentity(tenantId, "another-code")));

        assertThatThrownBy(() -> controller.createOrReconcileTenant(tenantId, request))
                .isInstanceOf(DomainException.class)
                .hasMessage("Tenant id is already assigned to a different code");
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void reusesTheDatabaseTenantWhenAStaleConfiguredIdUsesTheSameCode() {
        UUID staleConfiguredId = UUID.randomUUID();
        UUID databaseTenantId = UUID.randomUUID();
        ProvisionTenantRequest request = request(staleConfiguredId, "gavin");
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<TenantIdentity>>any(),
                eq(staleConfiguredId), eq("gavin")))
                .thenReturn(List.of(new TenantIdentity(databaseTenantId, "gavin")));

        TenantResolution resolution = controller.createOrReconcileTenant(staleConfiguredId, request);

        assertThat(resolution).isEqualTo(new TenantResolution(databaseTenantId, false));
        verify(jdbc).update(anyString(), eq("Gavin"), eq(databaseTenantId));
    }

    private ProvisionTenantRequest request(UUID tenantId, String code) {
        return new ProvisionTenantRequest(tenantId, code, "Gavin", "admin-subject", "Platform Admin");
    }
}
