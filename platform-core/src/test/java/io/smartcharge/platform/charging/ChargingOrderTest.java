package io.smartcharge.platform.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.smartcharge.platform.shared.domain.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChargingOrderTest {
    private final ChargingOrder order = ChargingOrder.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void completesOnlyThroughAcknowledgedLifecycle() {
        Instant started = Instant.parse("2026-01-01T00:00:00Z");
        order.requestStart();
        order.confirmStarted(started);
        order.requestStop();
        order.complete(started.plusSeconds(600), 350, 125);

        assertEquals(ChargeOrderStatus.COMPLETED, order.status());
        assertEquals(350, order.energyWh());
        assertEquals(125, order.payableAmountMinor());
    }

    @Test
    void rejectsCompletionWithoutDeviceStartAndStopAcknowledgements() {
        assertThrows(DomainException.class, () -> order.complete(Instant.now(), 1, 1));
    }
}
