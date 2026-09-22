package io.smartcharge.platform.charging;

import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
final class ChargingOrderService {
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneOffset.UTC);
    private final ChargingOrderRepository repository;
    private final TenantJdbcExecutor tenantJdbc;
    private final Clock clock;

    ChargingOrderService(ChargingOrderRepository repository, TenantJdbcExecutor tenantJdbc) {
        this(repository, tenantJdbc, Clock.systemUTC());
    }

    ChargingOrderService(ChargingOrderRepository repository, TenantJdbcExecutor tenantJdbc, Clock clock) {
        this.repository = repository;
        this.tenantJdbc = tenantJdbc;
        this.clock = clock;
    }

    CreatedOrder createAndRequestStart(String idempotencyKey, UUID customerId, UUID connectorId) {
        validateIdempotencyKey(idempotencyKey);
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> repository.findByIdempotencyKey(tenantId, idempotencyKey, connectorId)
                .orElseGet(() -> createNew(tenantId, idempotencyKey, customerId, connectorId)));
    }

    List<OrderSummary> findMine(UUID customerId) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> repository.findForCustomer(tenantId, customerId));
    }

    StopResult requestStop(UUID customerId, UUID orderId) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            ChargingOrderRepository.OrderLock order = repository.lockCustomerOrder(tenantId, customerId, orderId);
            if (order.status() == ChargeOrderStatus.STOP_PENDING) {
                return new StopResult(orderId, ChargeOrderStatus.STOP_PENDING);
            }
            if (order.status() != ChargeOrderStatus.CHARGING) {
                throw new DomainException("Only a charging order can be stopped");
            }
            repository.requestStop(UUID.randomUUID(), UUID.randomUUID(), tenantId, order.deviceId(),
                    order.connectorId(), orderId, Instant.now(clock).plus(Duration.ofMinutes(2)));
            return new StopResult(orderId, ChargeOrderStatus.STOP_PENDING);
        });
    }

    private CreatedOrder createNew(UUID tenantId, String idempotencyKey, UUID customerId, UUID connectorId) {
        Instant now = Instant.now(clock);
        if (!repository.customerExists(tenantId, customerId)) {
            throw new IllegalArgumentException("Customer does not exist or is inactive");
        }
        if (!repository.requiredAgreementsAccepted(tenantId, customerId)) {
            throw new DomainException("Required service agreements must be accepted before charging");
        }
        ChargingOrderRepository.ConnectorLock connector = repository.lockAvailableConnector(tenantId, connectorId);
        CreatedOrder concurrentResult = repository.findByIdempotencyKey(tenantId, idempotencyKey, connectorId).orElse(null);
        if (concurrentResult != null) {
            return concurrentResult;
        }
        if (!"AVAILABLE".equals(connector.status())) {
            throw new DomainException("Connector is not available");
        }
        if (connector.tariffId() == null || !repository.tariffExists(tenantId, connector.tariffId(), now)) {
            throw new DomainException("Connector has no active tariff");
        }

        UUID orderId = UUID.randomUUID();
        String orderNo = "C" + ORDER_TIME.format(now) + orderId.toString().substring(0, 10).replace("-", "");
        ChargingOrder order = ChargingOrder.create(orderId, tenantId, customerId, connectorId);
        order.requestStart();
        if (repository.insertOrder(order, connector.tariffId(), orderNo, idempotencyKey) == 0) {
            return repository.findByIdempotencyKey(tenantId, idempotencyKey, connectorId).orElseThrow();
        }
        repository.recordStatus(tenantId, orderId, null, "START_PENDING", "CUSTOMER_REQUESTED_START", "customer");
        repository.reserveConnector(tenantId, connectorId);
        repository.insertSession(UUID.randomUUID(), tenantId, orderId);
        repository.enqueueStartCommand(UUID.randomUUID(), UUID.randomUUID(), tenantId, connector.deviceId(),
                connectorId, orderId, now.plus(Duration.ofMinutes(2)));
        return new CreatedOrder(orderId, orderNo, order.status());
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("Idempotency-Key must be 8-128 safe characters");
        }
    }

    record CreatedOrder(UUID orderId, String orderNo, ChargeOrderStatus status) { }
    record StopResult(UUID orderId, ChargeOrderStatus status) { }
}
