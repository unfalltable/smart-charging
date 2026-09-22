package io.smartcharge.platform.finance;

import java.util.Map;
import java.util.UUID;

public interface PaymentGateway {
    boolean supports(String channel);

    GatewayIntent createPayment(GatewayPayment request);

    GatewayRefund createRefund(GatewayRefundRequest request);

    GatewayPaymentStatus queryPayment(UUID tenantId, String merchantOrderNo);

    GatewayRefundStatus queryRefund(UUID tenantId, String merchantRefundNo);

    VerifiedCallback verifyCallback(UUID tenantId, Map<String, String> headers, String body);

    VerifiedRefundCallback verifyRefundCallback(UUID tenantId, Map<String, String> headers, String body);

    record GatewayPayment(UUID tenantId, UUID paymentId, String merchantOrderNo, long amountMinor,
                          String currency, String description, String payerSubject) { }
    record GatewayIntent(String providerRequestId, Map<String, String> clientParameters) { }
    record GatewayRefundRequest(UUID tenantId, UUID refundId, String merchantRefundNo,
                                String providerTransactionNo, long amountMinor,
                                long originalPaymentAmountMinor, String currency, String reason) { }
    record GatewayRefund(String providerRefundNo, boolean completed) { }
    enum ProviderState { SUCCEEDED, PENDING, FAILED }
    record GatewayPaymentStatus(String providerTransactionNo, long amountMinor, ProviderState state) { }
    record GatewayRefundStatus(String providerRefundNo, long amountMinor, ProviderState state) { }
    record VerifiedCallback(String providerEventId, String merchantOrderNo, String providerTransactionNo,
                            long amountMinor, boolean succeeded, String rawPayload) { }
    record VerifiedRefundCallback(String providerEventId, String merchantRefundNo, String providerRefundNo,
                                  long amountMinor, boolean succeeded, String rawPayload) { }
}
