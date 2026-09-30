package io.smartcharge.platform.charging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.smartcharge.platform.DatabaseTestSupport;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantContext;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChargingWorkflowIntegrationTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void retriesCreateOneOrderAndCannotExposeAnotherCustomersOrder() throws Exception {
        var database = DatabaseTestSupport.database();
        var fixture = database.seedCharging();
        var runtime = database.runtimeJdbc();
        var service = new ChargingOrderService(new ChargingOrderRepository(runtime.jdbc()), runtime.tenants());
        TenantContext.set(fixture.tenantId());
        String key = UUID.randomUUID().toString();
        var created = service.createAndRequestStart(key, fixture.customerId(), fixture.connectorId());
        assertThat(service.createAndRequestStart(key, fixture.customerId(), fixture.connectorId()).orderId())
                .isEqualTo(created.orderId());
        assertThatThrownBy(() -> service.createAndRequestStart(key, UUID.randomUUID(), fixture.connectorId()))
                .isInstanceOf(DomainException.class);
        assertThat(database.ownerJdbc().queryForObject("select count(*) from device_command where order_id=?",
                Integer.class, created.orderId())).isEqualTo(1);
        assertThat(database.ownerJdbc().queryForObject("select count(*) from outbox_event where tenant_id=?",
                Integer.class, fixture.tenantId())).isEqualTo(1);
    }

    @Test void offlineDevicesCannotStartCharging() throws Exception {
        var database = DatabaseTestSupport.database();
        var fixture = database.seedCharging();
        var runtime = database.runtimeJdbc();
        database.ownerJdbc().update("update device set last_seen_at=now()-interval '10 minutes' where id=?",
                fixture.deviceId());
        TenantContext.set(fixture.tenantId());
        var service = new ChargingOrderService(new ChargingOrderRepository(runtime.jdbc()), runtime.tenants());
        assertThatThrownBy(() -> service.createAndRequestStart(UUID.randomUUID().toString(),
                fixture.customerId(), fixture.connectorId())).isInstanceOf(DomainException.class);
        assertThat(database.ownerJdbc().queryForObject("select count(*) from charging_order where tenant_id=?",
                Integer.class, fixture.tenantId())).isZero();
    }

    @Test void concurrentCustomersCannotReserveTheSamePort() throws Exception {
        var database = DatabaseTestSupport.database();
        var fixture = database.seedCharging();
        UUID otherCustomer = UUID.randomUUID();
        database.ownerJdbc().update("insert into customer(id,tenant_id,status) values (?,?,'ACTIVE')",
                otherCustomer, fixture.tenantId());
        var runtime = database.runtimeJdbc();
        var service = new ChargingOrderService(new ChargingOrderRepository(runtime.jdbc()), runtime.tenants());
        Callable<Boolean> first = request(service, fixture.tenantId(), fixture.customerId(), fixture.connectorId());
        Callable<Boolean> second = request(service, fixture.tenantId(), otherCustomer, fixture.connectorId());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(java.util.List.of(first, second));
            int successes = (results.get(0).get() ? 1 : 0) + (results.get(1).get() ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        }
        assertThat(database.ownerJdbc().queryForObject("select count(*) from charging_order where tenant_id=?",
                Integer.class, fixture.tenantId())).isEqualTo(1);
    }

    private Callable<Boolean> request(ChargingOrderService service, UUID tenant, UUID customer, UUID connector) {
        return () -> {
            TenantContext.set(tenant);
            try {
                service.createAndRequestStart(UUID.randomUUID().toString(), customer, connector);
                return true;
            } catch (DomainException unavailable) {
                return false;
            } finally { TenantContext.clear(); }
        };
    }

    @Test void simultaneousRetriesOfOneRequestReturnTheSameOrder() throws Exception {
        var database = DatabaseTestSupport.database();
        var fixture = database.seedCharging();
        var runtime = database.runtimeJdbc();
        var service = new ChargingOrderService(new ChargingOrderRepository(runtime.jdbc()), runtime.tenants());
        String key = UUID.randomUUID().toString();
        Callable<UUID> request = () -> {
            TenantContext.set(fixture.tenantId());
            try { return service.createAndRequestStart(key, fixture.customerId(), fixture.connectorId()).orderId(); }
            finally { TenantContext.clear(); }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(java.util.List.of(request, request));
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        }
        assertThat(database.ownerJdbc().queryForObject("select count(*) from charging_order where tenant_id=?",
                Integer.class, fixture.tenantId())).isEqualTo(1);
    }

    @Test void unpaidCompletedOrderBlocksNewCharging() throws Exception {
        var database = DatabaseTestSupport.database();
        var fixture = database.seedCharging();
        UUID completed = UUID.randomUUID();
        database.ownerJdbc().update("""
                insert into charging_order(id,tenant_id,order_no,customer_id,connector_id,tariff_id,status,
                                           idempotency_key,payable_amount_minor)
                values (?,?,?,?,?,?,'COMPLETED',?,100)
                """, completed, fixture.tenantId(), "V" + completed, fixture.customerId(), fixture.connectorId(),
                fixture.tariffId(), completed.toString());
        var runtime = database.runtimeJdbc();
        var service = new ChargingOrderService(new ChargingOrderRepository(runtime.jdbc()), runtime.tenants());
        TenantContext.set(fixture.tenantId());
        assertThatThrownBy(() -> service.createAndRequestStart(UUID.randomUUID().toString(),
                fixture.customerId(), fixture.connectorId())).isInstanceOf(DomainException.class)
                .hasMessageContaining("先支付");
        database.ownerJdbc().update("update charging_order set paid_amount_minor=100 where id=?", completed);
        assertThat(service.createAndRequestStart(UUID.randomUUID().toString(), fixture.customerId(), fixture.connectorId()))
                .isNotNull();
    }
}
