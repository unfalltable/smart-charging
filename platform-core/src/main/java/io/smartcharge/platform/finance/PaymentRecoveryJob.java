package io.smartcharge.platform.finance;

import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class PaymentRecoveryJob {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentRecoveryJob.class);
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PaymentGatewayRegistry gateways;
    private final PaymentService payments;
    private final RefundCallbackService refunds;

    PaymentRecoveryJob(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                       PaymentGatewayRegistry gateways, PaymentService payments,
                       RefundCallbackService refunds) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.gateways = gateways;
        this.payments = payments;
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "${payments.recovery-delay-ms:60000}")
    void recoverProcessingPayments() {
        List<UUID> tenants = jdbc.query("select id from tenant order by id",
                (result, row) -> result.getObject(1, UUID.class));
        for (UUID tenantId : tenants) {
            List<PendingPayment> pending = tenantJdbc.readWriteAs(tenantId, () -> jdbc.query("""
                    select id, merchant_channel_id, channel, merchant_order_no, created_at,
                           request_payload->>'paymentExpiresAt' payment_expires_at from payment_transaction
                     where tenant_id=? and status in ('CREATED','PROCESSING')
                       and updated_at<now()-interval '1 minute'
                     order by updated_at limit 50
                    """, (result, row) -> new PendingPayment(result.getObject("id", UUID.class),
                    result.getObject("merchant_channel_id", UUID.class), result.getString("channel"),
                    result.getString("merchant_order_no"), result.getTimestamp("created_at").toInstant(),
                    result.getString("payment_expires_at")), tenantId));
            for (PendingPayment payment : pending) recover(tenantId, payment);
            List<PendingRefund> pendingRefunds = tenantJdbc.readWriteAs(tenantId, () -> jdbc.query("""
                    select r.id, p.merchant_channel_id, p.channel, r.merchant_refund_no from refund_transaction r
                      join payment_transaction p on p.tenant_id=r.tenant_id and p.id=r.payment_id
                     where r.tenant_id=? and r.status in ('CREATED','PROCESSING')
                       and r.updated_at<now()-interval '1 minute'
                     order by r.updated_at limit 50
                    """, (result, row) -> new PendingRefund(result.getObject("id", UUID.class),
                    result.getObject("merchant_channel_id", UUID.class), result.getString("channel"),
                    result.getString("merchant_refund_no")), tenantId));
            for (PendingRefund refund : pendingRefunds) recover(tenantId, refund);
        }
    }

    private void recover(UUID tenantId, PendingPayment payment) {
        try {
            PaymentGateway.GatewayPaymentStatus status;
            try {
                status = payments.resolveProviderStatus(tenantId, payment.merchantChannelId(), payment.channel(),
                        payment.merchantOrderNo(), payments.closingDeadline(payment.createdAt(),
                            PaymentService.paymentExpiry(payment.createdAt(), payment.storedExpiry())));
            } catch (ProviderResourceNotFoundException notCreated) {
                payments.recoverMissingPayment(tenantId, payment.id());
                return;
            }
            payments.applyProviderStatus(tenantId, payment.id(), status);
        } catch (RuntimeException failure) {
            tenantJdbc.readWriteAs(tenantId, () -> jdbc.update("""
                    update payment_transaction set updated_at=now()
                     where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                    """, tenantId, payment.id()));
            LOG.warn("Payment status recovery failed: paymentId={}, reason={}",
                    payment.id(), failure.getClass().getSimpleName());
        }
    }

    private void recover(UUID tenantId, PendingRefund refund) {
        try {
            PaymentGateway.GatewayRefundStatus status;
            try {
                status = gateways.required(refund.channel()).queryRefund(tenantId, refund.merchantChannelId(), refund.merchantRefundNo());
            } catch (ProviderResourceNotFoundException notCreated) {
                refunds.recoverMissingRefund(tenantId, refund.id());
                return;
            }
            refunds.applyProviderStatus(tenantId, refund.id(), status);
        } catch (RuntimeException failure) {
            tenantJdbc.readWriteAs(tenantId, () -> jdbc.update("""
                    update refund_transaction set updated_at=now()
                     where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                    """, tenantId, refund.id()));
            LOG.warn("Refund status recovery failed: refundId={}, reason={}",
                    refund.id(), failure.getClass().getSimpleName());
        }
    }

    private record PendingPayment(UUID id, UUID merchantChannelId, String channel, String merchantOrderNo,
                                  Instant createdAt, String storedExpiry) { }
    private record PendingRefund(UUID id, UUID merchantChannelId, String channel, String merchantRefundNo) { }
}
