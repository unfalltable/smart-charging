package io.smartcharge.platform.charging;

import io.smartcharge.platform.shared.domain.DomainException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ChargingOrder {
    private final UUID id;
    private final UUID tenantId;
    private final UUID customerId;
    private final UUID connectorId;
    private ChargeOrderStatus status;
    private Instant startedAt;
    private Instant stoppedAt;
    private long energyWh;
    private long payableAmountMinor;

    private ChargingOrder(UUID id, UUID tenantId, UUID customerId, UUID connectorId) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.customerId = Objects.requireNonNull(customerId);
        this.connectorId = Objects.requireNonNull(connectorId);
        this.status = ChargeOrderStatus.CREATED;
    }

    public static ChargingOrder create(UUID id, UUID tenantId, UUID customerId, UUID connectorId) {
        return new ChargingOrder(id, tenantId, customerId, connectorId);
    }

    public void requestStart() {
        requireStatus(ChargeOrderStatus.CREATED);
        status = ChargeOrderStatus.START_PENDING;
    }

    public void confirmStarted(Instant at) {
        requireStatus(ChargeOrderStatus.START_PENDING);
        startedAt = Objects.requireNonNull(at);
        status = ChargeOrderStatus.CHARGING;
    }

    public void requestStop() {
        requireStatus(ChargeOrderStatus.CHARGING);
        status = ChargeOrderStatus.STOP_PENDING;
    }

    public void complete(Instant at, long energyWh, long payableAmountMinor) {
        requireStatus(ChargeOrderStatus.STOP_PENDING);
        if (energyWh < 0 || payableAmountMinor < 0) {
            throw new DomainException("Metering and amount values cannot be negative");
        }
        stoppedAt = Objects.requireNonNull(at);
        if (startedAt != null && at.isBefore(startedAt)) {
            throw new DomainException("Stop time cannot be before start time");
        }
        this.energyWh = energyWh;
        this.payableAmountMinor = payableAmountMinor;
        status = ChargeOrderStatus.COMPLETED;
    }

    public void cancel() {
        requireStatus(ChargeOrderStatus.CREATED);
        status = ChargeOrderStatus.CANCELLED;
    }

    public void fail() {
        if (status == ChargeOrderStatus.COMPLETED || status == ChargeOrderStatus.CANCELLED) {
            throw new DomainException("A terminal order cannot fail");
        }
        status = ChargeOrderStatus.FAILED;
    }

    private void requireStatus(ChargeOrderStatus expected) {
        if (status != expected) {
            throw new DomainException("Expected order status " + expected + " but was " + status);
        }
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID customerId() { return customerId; }
    public UUID connectorId() { return connectorId; }
    public ChargeOrderStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant stoppedAt() { return stoppedAt; }
    public long energyWh() { return energyWh; }
    public long payableAmountMinor() { return payableAmountMinor; }
}
