package io.smartcharge.platform.finance;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
final class LocalPaymentGateway implements PaymentGateway {
    @Override
    public boolean supports(String channel) {
        return "WECHAT".equals(channel) || "ALIPAY".equals(channel);
    }

    @Override
    public GatewayIntent createPayment(GatewayPayment request) {
        return new GatewayIntent("local-" + request.paymentId(), Map.of(
                "mode", "LOCAL_SIMULATION",
                "paymentId", request.paymentId().toString(),
                "merchantOrderNo", request.merchantOrderNo()));
    }

    @Override
    public GatewayRefund createRefund(GatewayRefundRequest request) {
        return new GatewayRefund("local-refund-" + request.refundId(), true);
    }

    @Override
    public GatewayPaymentStatus queryPayment(UUID tenantId, String merchantOrderNo) {
        return new GatewayPaymentStatus(null, 0, ProviderState.PENDING);
    }

    @Override
    public GatewayRefundStatus queryRefund(UUID tenantId, String merchantRefundNo) {
        return new GatewayRefundStatus(null, 0, ProviderState.PENDING);
    }

    @Override
    public VerifiedCallback verifyCallback(UUID tenantId, Map<String, String> headers, String body) {
        throw new UnsupportedOperationException("Local payments use the authenticated simulation endpoint");
    }

    @Override
    public VerifiedRefundCallback verifyRefundCallback(UUID tenantId, Map<String, String> headers, String body) {
        throw new UnsupportedOperationException("Local payments use the authenticated simulation endpoint");
    }
}
