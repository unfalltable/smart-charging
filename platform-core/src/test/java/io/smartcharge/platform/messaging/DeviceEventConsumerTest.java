package io.smartcharge.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.nats.client.JetStream;
import io.smartcharge.platform.billing.TariffCalculator;
import io.smartcharge.platform.contracts.DeviceEnvelope;
import io.smartcharge.platform.contracts.DeviceEventType;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DeviceEventConsumerTest {
    @Test
    void bindsEventInstantAsJdbcTimestamp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);
        AtomicReference<Object[]> insertParameters = new AtomicReference<>();

        when(jdbc.query(contains("from device_route"),
                org.mockito.ArgumentMatchers.<RowMapper<DeviceEventConsumer.DeviceRoute>>any(), eq("PILE001")))
                .thenReturn(List.of(new DeviceEventConsumer.DeviceRoute(tenantId, deviceId)));
        when(tenantJdbc.readWriteAs(eq(tenantId), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(jdbc.update(contains("insert into device_message"), any(Object[].class))).thenAnswer(invocation -> {
            insertParameters.set(Arrays.copyOfRange(invocation.getArguments(), 1, invocation.getArguments().length));
            return 1;
        });

        Instant occurredAt = Instant.parse("2026-09-25T06:55:36.123456Z");
        DeviceEnvelope envelope = new DeviceEnvelope("1.0", UUID.randomUUID(), "PILE001", occurredAt,
                "nonce-1", DeviceEventType.BOOT, "{}", "signature");
        DeviceEventConsumer consumer = new DeviceEventConsumer(mock(JetStream.class), jdbc, tenantJdbc,
                new TariffCalculator());

        consumer.process(envelope);

        assertThat(insertParameters.get()[6]).isInstanceOf(Timestamp.class);
        assertThat(((Timestamp) insertParameters.get()[6]).toInstant()).isEqualTo(occurredAt);
    }
}
