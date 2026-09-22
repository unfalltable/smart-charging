package io.smartcharge.platform.finance;

import io.smartcharge.platform.shared.domain.DomainException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class PaymentGatewayRegistry {
    private final List<PaymentGateway> gateways;

    PaymentGatewayRegistry(List<PaymentGateway> gateways) {
        this.gateways = List.copyOf(gateways);
    }

    PaymentGateway required(String channel) {
        return gateways.stream().filter(gateway -> gateway.supports(channel)).findFirst()
                .orElseThrow(() -> new DomainException("Payment channel is not configured"));
    }
}
