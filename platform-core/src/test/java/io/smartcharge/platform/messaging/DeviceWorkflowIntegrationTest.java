package io.smartcharge.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.nats.client.JetStream;
import io.smartcharge.platform.DatabaseTestSupport;
import io.smartcharge.platform.billing.TariffCalculator;
import io.smartcharge.platform.contracts.DeviceEnvelope;
import io.smartcharge.platform.contracts.DeviceEventType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceWorkflowIntegrationTest {
    @Test void startMeterStopAndDuplicateEventsProduceOneCorrectBill() throws Exception {
        var database = DatabaseTestSupport.database();
        var fixture = database.seedCharging();
        var jdbc = database.ownerJdbc();
        var runtime = database.runtimeJdbc();
        UUID order = UUID.randomUUID();
        jdbc.update("""
                insert into charging_order(id,tenant_id,order_no,customer_id,connector_id,tariff_id,status,idempotency_key)
                values (?,?,?,?,?,?,'START_PENDING',?)
                """, order, fixture.tenantId(), "V" + order, fixture.customerId(), fixture.connectorId(),
                fixture.tariffId(), order.toString());
        jdbc.update("insert into charging_session(id,tenant_id,order_id) values (?,?,?)",
                UUID.randomUUID(), fixture.tenantId(), order);
        jdbc.update("update connector set status='RESERVED' where id=?", fixture.connectorId());
        var consumer = new DeviceEventConsumer(mock(JetStream.class), runtime.jdbc(), runtime.tenants(), new TariffCalculator());
        Instant start = Instant.now().minusSeconds(120);
        DeviceEnvelope stopped = event(fixture.deviceCode(), DeviceEventType.SESSION_STOPPED, start.plusSeconds(61),
                "{\"orderId\":\"" + order + "\",\"meterStopWh\":110}");
        assertThatThrownBy(() -> consumer.process(stopped)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from device_message where message_id=?",
                Integer.class, stopped.messageId())).isZero();
        consumer.process(event(fixture.deviceCode(), DeviceEventType.SESSION_STARTED, start,
                "{\"orderId\":\"" + order + "\",\"meterStartWh\":100}"));
        consumer.process(event(fixture.deviceCode(), DeviceEventType.CONNECTOR_STATUS, start.plusSeconds(10),
                "{\"connectorNo\":1,\"status\":\"AVAILABLE\"}"));
        assertThat(jdbc.queryForObject("select status from connector where id=?", String.class, fixture.connectorId()))
                .isEqualTo("CHARGING");
        consumer.process(event(fixture.deviceCode(), DeviceEventType.METER_SAMPLE, start.plusSeconds(30),
                "{\"orderId\":\"" + order + "\",\"connectorNo\":1,\"sequenceNo\":1,\"energyWh\":108}"));
        assertThat(jdbc.queryForObject("select energy_wh from charging_session where order_id=?", Long.class, order))
                .isEqualTo(8);
        consumer.process(stopped);
        consumer.process(stopped);
        assertThat(jdbc.queryForObject("select payable_amount_minor from charging_order where id=?", Long.class, order))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("select status from charging_order where id=?", String.class, order))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select status from connector where id=?", String.class, fixture.connectorId()))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id=? and to_status='COMPLETED'",
                Integer.class, order)).isEqualTo(1);
    }

    @Test void retiredDevicesCannotResurrectViaHeartbeat() throws Exception {
        var database = DatabaseTestSupport.database();
        var fixture = database.seedCharging();
        database.ownerJdbc().update("update device set status='RETIRED' where id=?", fixture.deviceId());
        var runtime = database.runtimeJdbc();
        var consumer = new DeviceEventConsumer(mock(JetStream.class), runtime.jdbc(), runtime.tenants(), new TariffCalculator());
        assertThatThrownBy(() -> consumer.process(event(fixture.deviceCode(), DeviceEventType.HEARTBEAT, Instant.now(), "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(database.ownerJdbc().queryForObject("select status from device where id=?", String.class, fixture.deviceId()))
                .isEqualTo("RETIRED");
    }

    @Test void confirmedStopCannotReenableAnOperatorDisabledConnector() throws Exception {
        var database = DatabaseTestSupport.database();
        var fixture = database.seedCharging();
        var jdbc = database.ownerJdbc();
        UUID order = UUID.randomUUID();
        jdbc.update("""
                insert into charging_order(id,tenant_id,order_no,customer_id,connector_id,tariff_id,status,idempotency_key)
                values (?,?,?,?,?,?,'START_PENDING',?)
                """, order, fixture.tenantId(), "V" + order, fixture.customerId(), fixture.connectorId(),
                fixture.tariffId(), order.toString());
        jdbc.update("insert into charging_session(id,tenant_id,order_id) values (?,?,?)",
                UUID.randomUUID(), fixture.tenantId(), order);
        var runtime = database.runtimeJdbc();
        var consumer = new DeviceEventConsumer(mock(JetStream.class), runtime.jdbc(), runtime.tenants(), new TariffCalculator());
        Instant start = Instant.now().minusSeconds(120);
        consumer.process(event(fixture.deviceCode(), DeviceEventType.SESSION_STARTED, start,
                "{\"orderId\":\"" + order + "\",\"meterStartWh\":100}"));
        jdbc.update("update connector set status='DISABLED',last_status_at=now() where id=?", fixture.connectorId());
        consumer.process(event(fixture.deviceCode(), DeviceEventType.SESSION_STOPPED, start.plusSeconds(61),
                "{\"orderId\":\"" + order + "\",\"meterStopWh\":110}"));
        assertThat(jdbc.queryForObject("select status from charging_order where id=?", String.class, order))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select payable_amount_minor from charging_order where id=?", Long.class, order))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("select status from connector where id=?", String.class, fixture.connectorId()))
                .isEqualTo("DISABLED");
    }

    private DeviceEnvelope event(String device, DeviceEventType type, Instant at, String payload) {
        return new DeviceEnvelope("1.0", UUID.randomUUID(), device, at, UUID.randomUUID().toString(), type,
                payload, "isolated-test-signature");
    }

    @Test void aLateCancelledStartTriggersOneSafetyStopAndNeverReopensTheBill() throws Exception {
        var db = DatabaseTestSupport.database();
        var fixture = db.seedCharging();
        var jdbc = db.ownerJdbc();
        UUID order = UUID.randomUUID();
        jdbc.update("""
                insert into charging_order(id,tenant_id,order_no,customer_id,connector_id,tariff_id,status,idempotency_key)
                values (?,?,?,?,?,?,'CANCELLED',?)
                """, order, fixture.tenantId(), "V" + order, fixture.customerId(), fixture.connectorId(),
                fixture.tariffId(), order.toString());
        jdbc.update("insert into charging_session(id,tenant_id,order_id) values (?,?,?)",
                UUID.randomUUID(), fixture.tenantId(), order);
        jdbc.update("update connector set status='OFFLINE' where id=?", fixture.connectorId());
        var runtime = db.runtimeJdbc();
        var consumer = new DeviceEventConsumer(mock(JetStream.class), runtime.jdbc(), runtime.tenants(), new TariffCalculator());
        Instant start = Instant.now();
        String payload = "{\"orderId\":\"" + order + "\",\"meterStartWh\":100}";
        consumer.process(event(fixture.deviceCode(), DeviceEventType.SESSION_STARTED, start, payload));
        consumer.process(event(fixture.deviceCode(), DeviceEventType.SESSION_STARTED, start, payload));
        assertThat(jdbc.queryForObject("select count(*) from device_command where order_id=? and command_type='STOP_CHARGING'",
                Integer.class, order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from connector where id=?", String.class, fixture.connectorId())).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("select status from device where id=?", String.class, fixture.deviceId())).isEqualTo("FAULTED");
        consumer.process(event(fixture.deviceCode(), DeviceEventType.SESSION_STOPPED, start.plusSeconds(2),
                "{\"orderId\":\"" + order + "\",\"meterStopWh\":102}"));
        assertThat(jdbc.queryForObject("select status from charging_order where id=?", String.class, order)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select payable_amount_minor from charging_order where id=?", Long.class, order)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from device_alarm where tenant_id=?", Integer.class, fixture.tenantId())).isEqualTo(1);
    }
}
