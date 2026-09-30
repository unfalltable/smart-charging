package io.smartcharge.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.smartcharge.platform.DatabaseTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationalRecoveryIntegrationTest {
    @Test
    void acknowledgedStartTimeoutDoesNotPretendThePhysicalPortIsAvailable() throws Exception {
        var db = DatabaseTestSupport.database();
        var fixture = db.seedCharging();
        var jdbc = db.ownerJdbc();
        UUID order = UUID.randomUUID(), command = UUID.randomUUID();
        jdbc.update("""
                insert into charging_order(id,tenant_id,order_no,customer_id,connector_id,tariff_id,status,idempotency_key)
                values (?,?,?,?,?,?,'START_PENDING',?)
                """, order, fixture.tenantId(), "V" + order, fixture.customerId(), fixture.connectorId(),
                fixture.tariffId(), order.toString());
        jdbc.update("update connector set status='RESERVED' where id=?", fixture.connectorId());
        jdbc.update("""
                insert into device_command(id,tenant_id,device_id,connector_id,order_id,command_type,status,payload,expires_at)
                values (?,?,?,?,?,'START_CHARGING','ACKNOWLEDGED','{}',now()-interval '1 minute')
                """, command, fixture.tenantId(), fixture.deviceId(), fixture.connectorId(), order);
        var runtime = db.runtimeJdbc();
        new OperationalRecoveryJob(runtime.jdbc(), runtime.tenants()).recover();
        assertThat(jdbc.queryForObject("select status from charging_order where id=?", String.class, order)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from connector where id=?", String.class, fixture.connectorId())).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("select status from device_command where id=?", String.class, command)).isEqualTo("EXPIRED");
        new OperationalRecoveryJob(runtime.jdbc(), runtime.tenants()).recover();
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id=?", Integer.class, order)).isEqualTo(1);
    }

    @Test
    void acknowledgedStopTimeoutAllowsARealStopRetry() throws Exception {
        var db = DatabaseTestSupport.database();
        var fixture = db.seedCharging();
        var jdbc = db.ownerJdbc();
        UUID order = UUID.randomUUID(), command = UUID.randomUUID();
        jdbc.update("""
                insert into charging_order(id,tenant_id,order_no,customer_id,connector_id,tariff_id,status,idempotency_key)
                values (?,?,?,?,?,?,'STOP_PENDING',?)
                """, order, fixture.tenantId(), "V" + order, fixture.customerId(), fixture.connectorId(),
                fixture.tariffId(), order.toString());
        jdbc.update("update connector set status='CHARGING' where id=?", fixture.connectorId());
        jdbc.update("""
                insert into device_command(id,tenant_id,device_id,connector_id,order_id,command_type,status,payload,expires_at)
                values (?,?,?,?,?,'STOP_CHARGING','ACKNOWLEDGED','{}',now()-interval '1 minute')
                """, command, fixture.tenantId(), fixture.deviceId(), fixture.connectorId(), order);
        var runtime = db.runtimeJdbc();
        new OperationalRecoveryJob(runtime.jdbc(), runtime.tenants()).recover();
        assertThat(jdbc.queryForObject("select status from charging_order where id=?", String.class, order)).isEqualTo("CHARGING");
        assertThat(jdbc.queryForObject("select status from connector where id=?", String.class, fixture.connectorId())).isEqualTo("CHARGING");
        assertThat(jdbc.queryForObject("select status from device_command where id=?", String.class, command)).isEqualTo("EXPIRED");
    }
}
