package io.smartcharge.platform.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class FinanceAdminControllerTest {
    @Test
    void reconciliationFindsSuccessfulPlatformPaymentsMissingFromTheStatement() throws Exception {
        UUID tenantId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);
        when(tenantJdbc.readWrite(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        when(jdbc.query(contains("from payment_transaction"),
                org.mockito.ArgumentMatchers.<RowMapper<FinanceAdminController.PaymentMatch>>any(),
                eq(tenantId), eq("WECHAT"), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(List.of(new FinanceAdminController.PaymentMatch(UUID.randomUUID(), "P-present", 500, "wx-present"),
                        new FinanceAdminController.PaymentMatch(UUID.randomUUID(), "P-missing", 700, "wx-missing")));
        TenantContext.set(tenantId);
        try {
            var controller = new FinanceAdminController(jdbc, tenantJdbc, mock(PaymentGatewayRegistry.class),
                    mock(AuditService.class), mock(RefundCallbackService.class));
            var result = controller.reconcile(new FinanceAdminController.ReconciliationRequest("WECHAT",
                    LocalDate.of(2026, 9, 29), "statement-file-sha256", List.of(
                    new FinanceAdminController.ReconciliationRow("P-present", "wx-present", 500))));
            assertThat(result.status()).isEqualTo("EXCEPTION");
            assertThat(result.totalCount()).isEqualTo(2);
            assertThat(result.matchedCount()).isEqualTo(1);
            assertThat(result.exceptionCount()).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void reconciliationRejectsDuplicateProviderRowsBeforeCreatingABatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var controller = new FinanceAdminController(jdbc, mock(TenantJdbcExecutor.class),
                mock(PaymentGatewayRegistry.class), mock(AuditService.class), mock(RefundCallbackService.class));
        var duplicate = new FinanceAdminController.ReconciliationRow("P-repeat", "wx-repeat", 500);
        assertThatThrownBy(() -> controller.reconcile(new FinanceAdminController.ReconciliationRequest("WECHAT",
                LocalDate.of(2026, 9, 29), "statement-file-sha256", List.of(duplicate, duplicate))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        verifyNoInteractions(jdbc);
    }

    @Test
    void settlementUsesChinaBusinessDaysAndExcludesPreauthorizations() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);
        when(tenantJdbc.readWrite(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());

        ResultSet rule = mock(ResultSet.class);
        when(rule.getObject("organization_id", UUID.class)).thenReturn(organizationId);
        when(rule.getInt("share_basis_points")).thenReturn(8_000);
        when(rule.getInt("platform_service_fee_basis_points")).thenReturn(1_000);
        when(rule.getLong("fixed_service_fee_minor")).thenReturn(0L);
        when(rule.getInt("hierarchy_level")).thenReturn(1);
        when(jdbc.query(contains("from settlement_rule"),
                org.mockito.ArgumentMatchers.<RowMapper<FinanceAdminController.Rule>>any(),
                eq(tenantId), eq(ruleId), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> List.of(
                        invocation.<RowMapper<FinanceAdminController.Rule>>getArgument(1).mapRow(rule, 0)));

        Timestamp start = Timestamp.from(Instant.parse("2025-12-31T16:00:00Z"));
        Timestamp end = Timestamp.from(Instant.parse("2026-01-02T16:00:00Z"));
        when(jdbc.queryForObject(contains("transaction_type in ('PAY', 'CAPTURE')"), eq(Long.class),
                eq(tenantId), eq(start), eq(end), eq(1), eq(organizationId))).thenReturn(10_000L);
        when(jdbc.queryForObject(contains("from refund_transaction"), eq(Long.class),
                eq(tenantId), eq(start), eq(end), eq(1), eq(organizationId))).thenReturn(1_000L);

        TenantContext.set(tenantId);
        try {
            var controller = new FinanceAdminController(jdbc, tenantJdbc,
                    mock(PaymentGatewayRegistry.class), mock(AuditService.class),
                    mock(RefundCallbackService.class));
            var result = controller.generateSettlement(new FinanceAdminController.GenerateSettlementRequest(
                    ruleId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)));

            assertThat(result.grossAmountMinor()).isEqualTo(9_000L);
            assertThat(result.platformServiceFeeMinor()).isEqualTo(900L);
            verify(jdbc).queryForObject(contains("transaction_type in ('PAY', 'CAPTURE')"), eq(Long.class),
                    eq(tenantId), eq(start), eq(end), eq(1), eq(organizationId));
        } finally {
            TenantContext.clear();
        }
    }
}
