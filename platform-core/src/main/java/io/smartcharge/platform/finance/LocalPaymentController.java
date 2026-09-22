package io.smartcharge.platform.finance;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1/payments")
final class LocalPaymentController {
    private final PaymentService payments;

    LocalPaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/{paymentId}/simulate-success")
    PaymentService.PaymentIntent simulateSuccess(@PathVariable UUID paymentId) {
        return payments.completeLocal(paymentId);
    }
}
