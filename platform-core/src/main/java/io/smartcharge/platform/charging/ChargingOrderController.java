package io.smartcharge.platform.charging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import io.smartcharge.platform.identity.CurrentCustomer;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/charging/orders")
final class ChargingOrderController {
    private final ChargingOrderService orders;
    private final CurrentCustomer currentCustomer;

    ChargingOrderController(ChargingOrderService orders, CurrentCustomer currentCustomer) {
        this.orders = orders;
        this.currentCustomer = currentCustomer;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ChargingOrderService.CreatedOrder create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @Valid @RequestBody CreateOrderRequest request) {
        return orders.createAndRequestStart(idempotencyKey, currentCustomer.requireId(), request.connectorId());
    }

    record CreateOrderRequest(@NotNull UUID connectorId) { }

    @GetMapping("/mine")
    java.util.List<OrderSummary> mine() {
        return orders.findMine(currentCustomer.requireId());
    }

    @PostMapping("/{orderId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ChargingOrderService.StopResult stop(@PathVariable UUID orderId) {
        return orders.requestStop(currentCustomer.requireId(), orderId);
    }
}
