package io.smartcharge.platform.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class PaymentController {
    private final PaymentService payments;
    private final RefundCallbackService refunds;

    PaymentController(PaymentService payments, RefundCallbackService refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PaymentService.PaymentIntent create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @Valid @RequestBody CreatePaymentRequest request) {
        return payments.create(idempotencyKey, request.orderId(), request.channel());
    }

    @PostMapping("/public/payments/{channel}/{tenantCode}/callback")
    void callback(@PathVariable String channel, @PathVariable String tenantCode,
                  @RequestHeader Map<String, String> headers,
                  @RequestBody String body) {
        payments.processCallback(channel.toUpperCase(), tenantCode, headers, body);
    }

    @PostMapping("/public/refunds/{channel}/{tenantCode}/callback")
    void refundCallback(@PathVariable String channel, @PathVariable String tenantCode,
                        @RequestHeader Map<String, String> headers, @RequestBody String body) {
        refunds.process(channel.toUpperCase(), tenantCode, headers, body);
    }

    record CreatePaymentRequest(@NotNull UUID orderId, @NotBlank String channel) { }
}
