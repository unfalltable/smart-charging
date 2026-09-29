package io.smartcharge.platform.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.identity.CurrentCustomer;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CustomerAccountControllerTest {
    @Test
    void closesAnEligibleAccountAndRevokesItsIdentity() {
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);
        CurrentCustomer currentCustomer = mock(CurrentCustomer.class);
        when(currentCustomer.requireId()).thenReturn(customerId);
        when(tenantJdbc.readWrite(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Integer>>any(),
                eq(tenantId), eq(customerId))).thenReturn(List.of(1));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantId), eq(customerId)))
                .thenReturn(false, false, false);

        TenantContext.set(tenantId);
        try {
            new CustomerAccountController(jdbc, tenantJdbc, currentCustomer).close();
        } finally {
            TenantContext.clear();
        }

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("update auth_refresh_token"),
                eq(tenantId), eq(customerId));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("delete from customer_identity"),
                eq(tenantId), eq(customerId));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status='CLOSED'"),
                eq(tenantId), eq(customerId));
    }
}
