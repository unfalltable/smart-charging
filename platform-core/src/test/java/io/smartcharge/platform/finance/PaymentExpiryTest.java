package io.smartcharge.platform.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.identity.CurrentCustomer;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentExpiryTest {
    @Test
    void immutableDeadlinesDoNotChangeWithConfigurationAndLegacyRequestsRetainAnUnknownProviderWindow() {
        Instant created = Instant.parse("2026-09-29T10:00:00Z");
        assertThat(PaymentService.paymentExpiry(created, "2026-09-29T10:30:00Z"))
                .isEqualTo(created.plusSeconds(1_800));
        assertThat(PaymentService.paymentExpiry(created, null)).isNull();
        assertThatThrownBy(() -> PaymentService.paymentExpiry(created, "broken-deadline"))
                .isInstanceOf(DomainException.class).hasMessageContaining("Stored payment expiry");
    }

    @Test
    void shortWindowsCannotBeSubmittedAndNotFoundRequiresAProviderConfirmationGracePeriod() {
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        PaymentService.requireSubmissionWindow(now.plusSeconds(61), now);
        assertThatThrownBy(() -> PaymentService.requireSubmissionWindow(now.plusSeconds(60), now))
                .isInstanceOf(DomainException.class).hasMessageContaining("less than one minute");
        assertThatThrownBy(() -> PaymentService.requireSubmissionWindow(null, now)).isInstanceOf(DomainException.class);
        assertThat(PaymentService.missingPaymentCanBeReleased(now.minusSeconds(59), now)).isFalse();
        assertThat(PaymentService.missingPaymentCanBeReleased(now.minusSeconds(60), now)).isTrue();
        assertThat(PaymentService.missingPaymentCanBeReleased(null, now)).isFalse();
    }

    @Test
    void disabledNewPaymentsDoNotCreateDatabaseRowsOrCallTheProvider() {
        var jdbc = mock(JdbcTemplate.class);
        var tenants = mock(TenantJdbcExecutor.class);
        var gateway = gateway();
        var payments = new PaymentService(jdbc, tenants, mock(CurrentCustomer.class),
                new PaymentGatewayRegistry(List.of(gateway)), mock(AuditService.class),
                mock(ProfitSharingPlanner.class), 30, false);
        assertThatThrownBy(() -> payments.create("disabled-payment", UUID.randomUUID(), "WECHAT"))
                .isInstanceOf(DomainException.class).hasMessageContaining("disabled");
        verifyNoInteractions(jdbc, tenants);
    }

    @Test
    void expiredUnpaidOrderIsReleasedOnlyAfterOfficialCloseSucceeds() {
        PaymentGateway gateway = gateway();
        UUID tenant = UUID.randomUUID(), merchant = UUID.randomUUID();
        when(gateway.queryPayment(tenant, merchant, "P-expired")).thenReturn(pending());
        var status = service(gateway).resolveProviderStatus(tenant, merchant, "WECHAT", "P-expired",
                Instant.now().minusSeconds(3_600));
        assertThat(status.state()).isEqualTo(PaymentGateway.ProviderState.FAILED);
        verify(gateway).closePayment(tenant, merchant, "P-expired");
    }

    @Test
    void failedClosePreservesProcessingUntilTheProviderConfirmsATerminalState() {
        PaymentGateway gateway = gateway();
        UUID tenant = UUID.randomUUID(), merchant = UUID.randomUUID();
        when(gateway.queryPayment(tenant, merchant, "P-pending")).thenReturn(pending());
        doThrow(new IllegalStateException("Network timeout")).when(gateway).closePayment(tenant, merchant, "P-pending");
        var status = service(gateway).resolveProviderStatus(tenant, merchant, "WECHAT", "P-pending",
                Instant.now().minusSeconds(3_600));
        assertThat(status.state()).isEqualTo(PaymentGateway.ProviderState.PENDING);
    }

    @Test
    void paymentWinningTheRaceWithCloseIsAppliedAsSuccessFromTheProviderEvidence() {
        PaymentGateway gateway = gateway();
        UUID tenant = UUID.randomUUID(), merchant = UUID.randomUUID();
        Instant completed = Instant.now();
        when(gateway.queryPayment(tenant, merchant, "P-race")).thenReturn(pending(),
                new PaymentGateway.GatewayPaymentStatus("wx-real-transaction", 100, PaymentGateway.ProviderState.SUCCEEDED, completed));
        doThrow(new IllegalStateException("ORDERPAID")).when(gateway).closePayment(tenant, merchant, "P-race");
        var status = service(gateway).resolveProviderStatus(tenant, merchant, "WECHAT", "P-race",
                Instant.now().minusSeconds(3_600));
        assertThat(status.state()).isEqualTo(PaymentGateway.ProviderState.SUCCEEDED);
        assertThat(status.providerTransactionNo()).isEqualTo("wx-real-transaction");
        assertThat(status.completedAt()).isEqualTo(completed);
    }

    private PaymentGateway gateway() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports("WECHAT")).thenReturn(true);
        return gateway;
    }

    private PaymentGateway.GatewayPaymentStatus pending() {
        return new PaymentGateway.GatewayPaymentStatus(null, 0, PaymentGateway.ProviderState.PENDING, null);
    }

    private PaymentService service(PaymentGateway gateway) {
        return new PaymentService(mock(JdbcTemplate.class), mock(TenantJdbcExecutor.class),
                mock(CurrentCustomer.class), new PaymentGatewayRegistry(List.of(gateway)),
                mock(AuditService.class), mock(ProfitSharingPlanner.class), 30, true);
    }
}
