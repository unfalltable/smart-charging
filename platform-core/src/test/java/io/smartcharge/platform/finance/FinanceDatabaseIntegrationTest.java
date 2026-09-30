package io.smartcharge.platform.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.DatabaseTestSupport;
import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.identity.CurrentCustomer;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class FinanceDatabaseIntegrationTest {
    @Test
    void providerNotFoundRecoveryReusesTheOriginalNumbersAndKeepsRefundReservationsDuringNetworkAmbiguity() throws Exception {
        Fixture fixture = fixture();
        var runtime = fixture.database().runtimeJdbc();
        var customer = mock(CurrentCustomer.class);
        when(customer.requireId()).thenReturn(fixture.customerId());
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports("WECHAT")).thenReturn(true);
        when(gateway.createPayment(any())).thenReturn(new PaymentGateway.GatewayIntent("prepay-boundary", Map.of("package", "prepay_id=boundary")));
        var registry = new PaymentGatewayRegistry(List.of(gateway));
        var audit = new AuditService(runtime.jdbc());
        var payments = new PaymentService(runtime.jdbc(), runtime.tenants(), customer, registry, audit,
                new ProfitSharingPlanner(runtime.jdbc(), 3_000), 30, true);
        var refunds = new RefundCallbackService(runtime.jdbc(), runtime.tenants(), registry, audit,
                new ProfitSharingReturnPlanner(runtime.jdbc()));
        var admin = new FinanceAdminController(runtime.jdbc(), runtime.tenants(), registry, audit, refunds);
        try {
            TenantContext.set(fixture.tenantId());
            var payment = payments.create("original-payment-1", fixture.orderId(), "WECHAT");
            fixture.database().ownerJdbc().update("update customer_identity set provider_subject=? where customer_id=?",
                    "changed-current-identity", fixture.customerId());
            var changedConfiguration = new PaymentService(runtime.jdbc(), runtime.tenants(), customer, registry, audit,
                    new ProfitSharingPlanner(runtime.jdbc(), 3_000), 60, false);
            changedConfiguration.recoverMissingPayment(fixture.tenantId(), payment.paymentId());
            var paymentsSent = ArgumentCaptor.forClass(PaymentGateway.GatewayPayment.class);
            verify(gateway, times(2)).createPayment(paymentsSent.capture());
            assertThat(paymentsSent.getAllValues().get(1)).isEqualTo(paymentsSent.getAllValues().getFirst());
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select count(*) from payment_transaction where order_id=?", Long.class, fixture.orderId()))).isEqualTo(1);
            payments.applyProviderStatus(fixture.tenantId(), payment.paymentId(), new PaymentGateway.GatewayPaymentStatus(
                    "wx-original-" + payment.paymentId(), 1_000, PaymentGateway.ProviderState.SUCCEEDED, Instant.now()));
            doThrow(new ProviderRequestRejectedException("NOT_ENOUGH")).when(gateway).createRefund(any());
            var refundRequest = new FinanceAdminController.CreateRefundRequest(payment.paymentId(), 700, "Customer request");
            assertThatThrownBy(() -> admin.createRefund("original-refund-1", refundRequest))
                    .isInstanceOf(DomainException.class).hasMessageContaining("NOT_ENOUGH");
            UUID refundId = runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select id from refund_transaction where payment_id=?", UUID.class, payment.paymentId()));
            doThrow(new IllegalStateException("Network timeout")).when(gateway).createRefund(any());
            assertThatThrownBy(() -> refunds.recoverMissingRefund(fixture.tenantId(), refundId))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select status from refund_transaction where id=?", String.class, refundId))).isEqualTo("PROCESSING");
            assertThatThrownBy(() -> admin.createRefund("second-refund-key", new FinanceAdminController.CreateRefundRequest(
                    payment.paymentId(), 400, "Second refund"))).isInstanceOf(DomainException.class).hasMessageContaining("exceeds");
            doReturn(new PaymentGateway.GatewayRefund("wx-refund-original", false, null)).when(gateway).createRefund(any());
            refunds.recoverMissingRefund(fixture.tenantId(), refundId);
            assertThat(admin.createRefund("original-refund-1", refundRequest).id()).isEqualTo(refundId);
            var refundsSent = ArgumentCaptor.forClass(PaymentGateway.GatewayRefundRequest.class);
            verify(gateway, times(4)).createRefund(refundsSent.capture());
            assertThat(refundsSent.getAllValues()).allMatch(original -> original.equals(refundsSent.getAllValues().getFirst()));
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select count(*) from refund_transaction where payment_id=?", Long.class, payment.paymentId()))).isEqualTo(1);
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select paid_amount_minor from charging_order where id=?", Long.class, fixture.orderId()))).isEqualTo(1_000);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void legacyUnknownRequestsAreOnlyReleasedAfterOfficialClosureRatherThanPrepayLifetime() throws Exception {
        Fixture fixture = fixture();
        var runtime = fixture.database().runtimeJdbc();
        var customer = mock(CurrentCustomer.class);
        when(customer.requireId()).thenReturn(fixture.customerId());
        var gateway = mock(PaymentGateway.class);
        when(gateway.supports("WECHAT")).thenReturn(true);
        when(gateway.createPayment(any())).thenReturn(new PaymentGateway.GatewayIntent("prepay-boundary", Map.of("package", "prepay_id=boundary")));
        var payments = new PaymentService(runtime.jdbc(), runtime.tenants(), customer,
                new PaymentGatewayRegistry(List.of(gateway)), new AuditService(runtime.jdbc()),
                new ProfitSharingPlanner(runtime.jdbc(), 3_000), 2, true);
        try {
            TenantContext.set(fixture.tenantId());
            var payment = payments.create("legacy-payment-key", fixture.orderId(), "WECHAT");
            fixture.database().ownerJdbc().update("update payment_transaction set request_payload='{}',created_at=now()-interval '1 hour' where id=?", payment.paymentId());
            assertThatThrownBy(() -> payments.recoverMissingPayment(fixture.tenantId(), payment.paymentId()))
                    .isInstanceOf(DomainException.class).hasMessageContaining("official closure or manual reconciliation");
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select status from payment_transaction where id=?", String.class, payment.paymentId()))).isEqualTo("PROCESSING");
            fixture.database().ownerJdbc().update("update payment_transaction set created_at=now()-interval '8 days' where id=?", payment.paymentId());
            assertThatThrownBy(() -> payments.recoverMissingPayment(fixture.tenantId(), payment.paymentId()))
                    .isInstanceOf(DomainException.class).hasMessageContaining("official closure or manual reconciliation");
            when(gateway.queryPayment(fixture.tenantId(), fixture.merchantChannelId(), payment.merchantOrderNo()))
                    .thenReturn(new PaymentGateway.GatewayPaymentStatus(null, 0, PaymentGateway.ProviderState.PENDING, null));
            var closed = payments.resolveProviderStatus(fixture.tenantId(), fixture.merchantChannelId(), "WECHAT",
                    payment.merchantOrderNo(), payments.closingDeadline(Instant.now().minusSeconds(3_600), null));
            payments.applyProviderStatus(fixture.tenantId(), payment.paymentId(), closed);
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select status from payment_transaction where id=?", String.class, payment.paymentId()))).isEqualTo("FAILED");
            verify(gateway, times(1)).createPayment(any());
            verify(gateway).closePayment(fixture.tenantId(), fixture.merchantChannelId(), payment.merchantOrderNo());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void nearExpiredMissingPaymentsAreNotResubmittedOrReleasedBeforeConfirmationGrace() throws Exception {
        Fixture fixture = fixture();
        var runtime = fixture.database().runtimeJdbc();
        var customer = mock(CurrentCustomer.class);
        when(customer.requireId()).thenReturn(fixture.customerId());
        var gateway = mock(PaymentGateway.class);
        when(gateway.supports("WECHAT")).thenReturn(true);
        when(gateway.createPayment(any())).thenReturn(new PaymentGateway.GatewayIntent("prepay-boundary", Map.of("package", "prepay_id=boundary")));
        var payments = new PaymentService(runtime.jdbc(), runtime.tenants(), customer,
                new PaymentGatewayRegistry(List.of(gateway)), new AuditService(runtime.jdbc()),
                new ProfitSharingPlanner(runtime.jdbc(), 3_000), 2, true);
        try {
            TenantContext.set(fixture.tenantId());
            var payment = payments.create("short-window-payment", fixture.orderId(), "WECHAT");
            updateStoredExpiry(fixture, payment.paymentId(), Instant.now().plusSeconds(30));
            assertThatThrownBy(() -> payments.recoverMissingPayment(fixture.tenantId(), payment.paymentId()))
                    .isInstanceOf(DomainException.class).hasMessageContaining("less than one minute");
            updateStoredExpiry(fixture, payment.paymentId(), Instant.now().minusSeconds(30));
            assertThatThrownBy(() -> payments.recoverMissingPayment(fixture.tenantId(), payment.paymentId()))
                    .isInstanceOf(DomainException.class).hasMessageContaining("safety window");
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select status from payment_transaction where id=?", String.class, payment.paymentId()))).isEqualTo("PROCESSING");
            updateStoredExpiry(fixture, payment.paymentId(), Instant.now().minusSeconds(61));
            payments.recoverMissingPayment(fixture.tenantId(), payment.paymentId());
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select status from payment_transaction where id=?", String.class, payment.paymentId()))).isEqualTo("FAILED");
            verify(gateway, times(1)).createPayment(any());
        } finally {
            TenantContext.clear();
        }
    }

    private void updateStoredExpiry(Fixture fixture, UUID paymentId, Instant expiresAt) {
        fixture.database().ownerJdbc().update("""
                update payment_transaction set request_payload=jsonb_set(request_payload,'{paymentExpiresAt}',to_jsonb(cast(? as text)))
                 where id=?
                """, expiresAt.toString(), paymentId);
    }

    @Test
    void restrictedDatabaseRoleCanProcessDuplicatePaymentAndRefundCallbacksWithoutDoublePosting() throws Exception {
        Fixture fixture = fixture();
        var runtime = fixture.database().runtimeJdbc();
        JdbcTemplate jdbc = runtime.jdbc();
        CurrentCustomer customer = mock(CurrentCustomer.class);
        when(customer.requireId()).thenReturn(fixture.customerId());
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports("WECHAT")).thenReturn(true);
        when(gateway.createPayment(any())).thenReturn(new PaymentGateway.GatewayIntent("prepay-test-boundary",
                Map.of("package", "prepay_id=isolated-test-boundary")));
        var registry = new PaymentGatewayRegistry(List.of(gateway));
        var audit = new AuditService(jdbc);
        var returns = new ProfitSharingReturnPlanner(jdbc);
        var refundService = new RefundCallbackService(jdbc, runtime.tenants(), registry, audit, returns);
        var payments = new PaymentService(jdbc, runtime.tenants(), customer, registry, audit,
                new ProfitSharingPlanner(jdbc, 3_000), 30, true);
        var admin = new FinanceAdminController(jdbc, runtime.tenants(), registry, audit, refundService);
        var customerFinance = new CustomerFinanceController(jdbc, runtime.tenants(), customer);
        try {
            TenantContext.set(fixture.tenantId());
            var intent = payments.create("payment-isolated-1", fixture.orderId(), "WECHAT");
            Instant paidAt = Instant.parse("2026-09-29T15:59:59Z");
            when(gateway.verifyCallback(eq(fixture.tenantId()), eq(fixture.merchantChannelId()), anyMap(), eq("payment-event")))
                    .thenReturn(new PaymentGateway.VerifiedCallback("paid-event", intent.merchantOrderNo(),
                            "wx-" + intent.paymentId(), 1_000, true, paidAt, "{}"));
            TenantContext.clear();
            assertThat(jdbc.queryForObject("select count(*) from merchant_channel", Long.class)).isZero();
            payments.processCallback("WECHAT", fixture.tenantCode(), fixture.merchantId(), Map.of(), "payment-event");
            payments.processCallback("WECHAT", fixture.tenantCode(), fixture.merchantId(), Map.of(), "payment-event");
            assertThat(runtime.tenants().readWriteAs(fixture.tenantId(), () -> jdbc.queryForObject(
                    "select paid_amount_minor from charging_order where id=?", Long.class, fixture.orderId()))).isEqualTo(1_000);
            assertThat(runtime.tenants().readWriteAs(fixture.tenantId(), () -> jdbc.queryForObject(
                    "select count(*) from ledger_entry where tenant_id=?", Long.class, fixture.tenantId()))).isEqualTo(2);
            assertThat(runtime.tenants().readWriteAs(fixture.tenantId(), () -> jdbc.queryForObject(
                    "select completed_at from payment_transaction where id=?", java.sql.Timestamp.class,
                    intent.paymentId())).toInstant()).isEqualTo(paidAt);
            assertThat(runtime.tenants().readWriteAs(fixture.tenantId(), () -> jdbc.queryForObject(
                    "select occurred_at from ledger_transaction where reference_type='PAYMENT' and reference_id=?",
                    java.sql.Timestamp.class, intent.paymentId())).toInstant()).isEqualTo(paidAt);

            TenantContext.set(fixture.tenantId());
            when(gateway.createRefund(any())).thenReturn(new PaymentGateway.GatewayRefund("wx-refund", false, null));
            var refundRequest = new FinanceAdminController.CreateRefundRequest(intent.paymentId(), 100, "Customer request");
            var refund = admin.createRefund("refund-isolated-1", refundRequest);
            assertThat(admin.createRefund("refund-isolated-1", refundRequest).id()).isEqualTo(refund.id());
            assertThat(runtime.tenants().readWrite(() -> jdbc.queryForObject(
                    "select count(*) from refund_transaction where payment_id=?", Long.class, intent.paymentId()))).isEqualTo(1);
            assertThatThrownBy(() -> customerFinance.requestInvoice(new CustomerFinanceController.InvoiceRequest(
                    fixture.orderId(), "Customer", null, "customer@example.test")))
                    .isInstanceOf(DomainException.class).hasMessageContaining("pending refund");
            when(gateway.verifyRefundCallback(eq(fixture.tenantId()), eq(fixture.merchantChannelId()), anyMap(), eq("refund-pending")))
                    .thenReturn(new PaymentGateway.VerifiedRefundCallback("refund-pending", refund.merchantRefundNo(),
                            "wx-refund", "wx-" + intent.paymentId(), 100, PaymentGateway.ProviderState.PENDING, null, "{}"));
            when(gateway.verifyRefundCallback(eq(fixture.tenantId()), eq(fixture.merchantChannelId()), anyMap(), eq("refund-success")))
                    .thenReturn(new PaymentGateway.VerifiedRefundCallback("refund-success", refund.merchantRefundNo(),
                            "wx-refund", "wx-" + intent.paymentId(), 100, PaymentGateway.ProviderState.SUCCEEDED,
                            paidAt.plusSeconds(5), "{}"));
            fixture.database().ownerJdbc().update("update tenant set status='SUSPENDED' where id=?", fixture.tenantId());
            fixture.database().ownerJdbc().update("update merchant_channel set status='DISABLED' where id=?", fixture.merchantChannelId());
            TenantContext.clear();
            refundService.process("WECHAT", fixture.tenantCode(), fixture.merchantId(), Map.of(), "refund-pending");
            assertThat(runtime.tenants().readWriteAs(fixture.tenantId(), () -> jdbc.queryForObject(
                    "select status from refund_transaction where id=?", String.class, refund.id()))).isEqualTo("PROCESSING");
            refundService.process("WECHAT", fixture.tenantCode(), fixture.merchantId(), Map.of(), "refund-success");
            refundService.process("WECHAT", fixture.tenantCode(), fixture.merchantId(), Map.of(), "refund-success");
            assertThat(runtime.tenants().readWriteAs(fixture.tenantId(), () -> jdbc.queryForObject(
                    "select paid_amount_minor from charging_order where id=?", Long.class, fixture.orderId()))).isEqualTo(900);
            assertThat(runtime.tenants().readWriteAs(fixture.tenantId(), () -> jdbc.queryForObject(
                    "select count(*) from ledger_entry where tenant_id=?", Long.class, fixture.tenantId()))).isEqualTo(4);
            TenantContext.set(fixture.tenantId());
            assertThatThrownBy(() -> payments.create("payment-after-refund", fixture.orderId(), "WECHAT"))
                    .isInstanceOf(DomainException.class).hasMessageContaining("no outstanding balance");
            assertThat(customerFinance.refunds()).extracting(CustomerFinanceController.CustomerRefundView::id)
                    .containsExactly(refund.id());
            when(customer.requireId()).thenReturn(UUID.randomUUID());
            assertThat(customerFinance.refunds()).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void anOfficiallyClosedExpiredIntentCanBeRebuiltAndLateFailureCannotOverwriteSuccessfulPayment() throws Exception {
        Fixture fixture = fixture();
        var runtime = fixture.database().runtimeJdbc();
        CurrentCustomer customer = mock(CurrentCustomer.class);
        when(customer.requireId()).thenReturn(fixture.customerId());
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports("WECHAT")).thenReturn(true);
        when(gateway.createPayment(any())).thenReturn(new PaymentGateway.GatewayIntent("prepay-boundary",
                Map.of("package", "prepay_id=isolated-boundary")));
        var payments = new PaymentService(runtime.jdbc(), runtime.tenants(), customer,
                new PaymentGatewayRegistry(List.of(gateway)), new AuditService(runtime.jdbc()),
                new ProfitSharingPlanner(runtime.jdbc(), 3_000), 30, true);
        try {
            TenantContext.set(fixture.tenantId());
            var first = payments.create("payment-before-expiry", fixture.orderId(), "WECHAT");
            fixture.database().ownerJdbc().update("""
                    update payment_transaction set created_at=now()-interval '1 hour',
                        request_payload=jsonb_set(request_payload,'{paymentExpiresAt}',to_jsonb(cast(? as text))) where id=?
                    """, Instant.now().minusSeconds(1_800).toString(), first.paymentId());
            when(gateway.queryPayment(fixture.tenantId(), fixture.merchantChannelId(), first.merchantOrderNo()))
                    .thenReturn(new PaymentGateway.GatewayPaymentStatus(null, 0, PaymentGateway.ProviderState.PENDING, null));
            assertThat(payments.create("payment-before-expiry", fixture.orderId(), "WECHAT").status()).isEqualTo("FAILED");
            var rebuilt = payments.create("payment-after-expiry", fixture.orderId(), "WECHAT");
            assertThat(rebuilt.paymentId()).isNotEqualTo(first.paymentId());
            assertThat(rebuilt.status()).isEqualTo("PROCESSING");
            payments.applyProviderStatus(fixture.tenantId(), rebuilt.paymentId(), new PaymentGateway.GatewayPaymentStatus(
                    "wx-rebuilt-" + rebuilt.paymentId(), 1_000, PaymentGateway.ProviderState.SUCCEEDED, Instant.now()));
            payments.applyProviderStatus(fixture.tenantId(), rebuilt.paymentId(), new PaymentGateway.GatewayPaymentStatus(
                    null, 0, PaymentGateway.ProviderState.FAILED, null));
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select status from payment_transaction where id=?", String.class, rebuilt.paymentId()))).isEqualTo("SUCCEEDED");
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select paid_amount_minor from charging_order where id=?", Long.class, fixture.orderId()))).isEqualTo(1_000);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void walletAdjustmentsAreIdempotentAndCannotConsumeFrozenFunds() throws Exception {
        Fixture fixture = fixture();
        var runtime = fixture.database().runtimeJdbc();
        var admin = new FinanceAdminController(runtime.jdbc(), runtime.tenants(), mock(PaymentGatewayRegistry.class),
                new AuditService(runtime.jdbc()), mock(RefundCallbackService.class));
        try {
            TenantContext.set(fixture.tenantId());
            var credit = new FinanceAdminController.WalletAdjustmentRequest(fixture.customerId(), "CREDIT", 1_000, "Manual adjustment");
            var first = admin.adjustWallet("wallet-credit-1", credit);
            var duplicate = admin.adjustWallet("wallet-credit-1", credit);
            assertThat(duplicate.get("referenceId").toString()).isEqualTo(first.get("referenceId").toString());
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select count(*) from wallet_entry where tenant_id=?", Long.class, fixture.tenantId()))).isEqualTo(1);
            assertThatThrownBy(() -> admin.adjustWallet("wallet-credit-1",
                    new FinanceAdminController.WalletAdjustmentRequest(fixture.customerId(), "CREDIT", 2_000, "Changed request")))
                    .isInstanceOf(DomainException.class).hasMessageContaining("Idempotency");
            runtime.tenants().readWrite(() -> runtime.jdbc().update(
                    "update wallet_account set frozen_minor=900 where tenant_id=?", fixture.tenantId()));
            assertThatThrownBy(() -> admin.adjustWallet("wallet-debit-1",
                    new FinanceAdminController.WalletAdjustmentRequest(fixture.customerId(), "DEBIT", 200, "Adjustment")))
                    .isInstanceOf(DomainException.class).hasMessageContaining("Available wallet balance");
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select balance_minor from wallet_account where tenant_id=?", Long.class, fixture.tenantId()))).isEqualTo(1_000);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void dailyReconciliationDetectsMissingProviderRowsAndTransactionIdentityMismatch() throws Exception {
        Fixture fixture = fixture();
        JdbcTemplate owner = fixture.database().ownerJdbc();
        owner.update("""
                insert into payment_transaction (id,tenant_id,order_id,merchant_channel_id,channel,transaction_type,
                    merchant_order_no,provider_transaction_no,amount_minor,status,completed_at)
                values (?,?,?,?,'WECHAT','PAY',? ,?,1000,'SUCCEEDED','2026-09-29T23:59:59+08:00')
                """, UUID.randomUUID(), fixture.tenantId(), fixture.orderId(), fixture.merchantChannelId(),
                "P-" + fixture.orderId(), "wx-" + fixture.orderId());
        var runtime = fixture.database().runtimeJdbc();
        var admin = new FinanceAdminController(runtime.jdbc(), runtime.tenants(), mock(PaymentGatewayRegistry.class),
                new AuditService(runtime.jdbc()), mock(RefundCallbackService.class));
        try {
            TenantContext.set(fixture.tenantId());
            var missing = admin.reconcile(new FinanceAdminController.ReconciliationRequest("WECHAT",
                    LocalDate.of(2026, 9, 29), "empty-provider-statement", List.of()));
            assertThat(missing.status()).isEqualTo("EXCEPTION");
            assertThat(missing.exceptionCount()).isEqualTo(1);
            var mismatch = admin.reconcile(new FinanceAdminController.ReconciliationRequest("WECHAT",
                    LocalDate.of(2026, 9, 29), "wrong-provider-identity", List.of(
                    new FinanceAdminController.ReconciliationRow("P-" + fixture.orderId(), "wrong-wx", 1_000))));
            assertThat(mismatch.status()).isEqualTo("EXCEPTION");
            assertThat(runtime.tenants().readWrite(() -> runtime.jdbc().queryForObject(
                    "select result from reconciliation_item where batch_id=?", String.class, mismatch.batchId())))
                    .isEqualTo("TRANSACTION_MISMATCH");
        } finally {
            TenantContext.clear();
        }
    }

    private Fixture fixture() throws Exception {
        var database = DatabaseTestSupport.database();
        JdbcTemplate jdbc = database.ownerJdbc();
        UUID tenant = UUID.randomUUID(), customer = UUID.randomUUID(), station = UUID.randomUUID();
        UUID device = UUID.randomUUID(), connector = UUID.randomUUID(), tariff = UUID.randomUUID();
        UUID order = UUID.randomUUID(), merchantChannel = UUID.randomUUID();
        String tenantCode = "finance-" + tenant, merchantId = "merchant-" + tenant.toString().substring(0, 8);
        jdbc.update("insert into tenant(id,code,display_name,status) values(?,?,?,'ACTIVE')", tenant, tenantCode, "Isolated finance verification");
        jdbc.update("insert into customer(id,tenant_id,status) values(?,?,'ACTIVE')", customer, tenant);
        jdbc.update("insert into customer_identity(id,tenant_id,customer_id,provider,provider_subject) values(?,?,?,'WECHAT',?)",
                UUID.randomUUID(), tenant, customer, "wx-customer-" + customer);
        jdbc.update("insert into station(id,tenant_id,organization_id,code,name,status) values(?,?,?,'site','Isolated site','ACTIVE')", station, tenant, tenant);
        jdbc.update("""
                insert into device(id,tenant_id,station_id,device_code,protocol_code,product_model,connector_count,status)
                values(?,?,?,?,'TEST_BOUNDARY','Isolated verification',1,'ONLINE')
                """, device, tenant, station, "finance-device-" + device);
        jdbc.update("insert into tariff(id,tenant_id,name,billing_mode,price_rules,effective_from,status) values(?,?,'Isolated tariff','DURATION','{}',now(),'ACTIVE')", tariff, tenant);
        jdbc.update("insert into connector(id,tenant_id,device_id,connector_no,external_code,status,tariff_id) values(?,?,?,1,'one','AVAILABLE',?)", connector, tenant, device, tariff);
        jdbc.update("""
                insert into charging_order(id,tenant_id,order_no,customer_id,connector_id,tariff_id,status,idempotency_key,payable_amount_minor)
                values(?,?,?,?,?,?,'COMPLETED','isolated-order',1000)
                """, order, tenant, "O-" + order.toString().substring(0, 30), customer, connector, tariff);
        jdbc.update("""
                insert into merchant_channel(id,tenant_id,organization_id,channel,merchant_id,application_id,secret_reference,
                    notify_url,refund_notify_url,profit_sharing_required,status)
                values(?,?,?,'WECHAT',?,'isolated-app','env:ISOLATED','https://example.test/payment','https://example.test/refund',false,'ACTIVE')
                """, merchantChannel, tenant, tenant, merchantId);
        return new Fixture(database, tenant, tenantCode, merchantChannel, merchantId, customer, order);
    }

    private record Fixture(DatabaseTestSupport.Database database, UUID tenantId, String tenantCode,
                           UUID merchantChannelId, String merchantId, UUID customerId, UUID orderId) { }
}
