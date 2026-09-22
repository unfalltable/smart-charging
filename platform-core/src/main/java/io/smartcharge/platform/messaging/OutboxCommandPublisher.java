package io.smartcharge.platform.messaging;

import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.smartcharge.platform.contracts.DeviceCommand;
import io.smartcharge.platform.contracts.DeviceCommandType;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
final class OutboxCommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxCommandPublisher.class);
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final JetStream jetStream;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    OutboxCommandPublisher(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, JetStream jetStream) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.jetStream = jetStream;
    }

    @Scheduled(fixedDelayString = "${outbox.publisher-delay-ms:250}")
    void publishAvailable() {
        List<UUID> tenants = jdbc.query("select id from tenant where status = 'ACTIVE' order by id",
                (result, row) -> result.getObject(1, UUID.class));
        for (UUID tenantId : tenants) {
            for (PendingCommand pending : claim(tenantId)) publish(pending);
        }
    }

    @Scheduled(fixedDelayString = "${device-commands.retry-delay-ms:5000}")
    void retryUnacknowledged() {
        List<UUID> tenants = jdbc.query("select id from tenant where status = 'ACTIVE' order by id",
                (result, row) -> result.getObject(1, UUID.class));
        for (UUID tenantId : tenants) {
            for (DeviceCommand command : claimRetries(tenantId)) publishRetry(command);
        }
    }

    private List<DeviceCommand> claimRetries(UUID tenantId) {
        return tenantJdbc.readWriteAs(tenantId, () -> {
            List<DeviceCommand> commands = jdbc.query("""
                    select dc.id, dc.tenant_id, d.device_code, c.connector_no, dc.command_type,
                           dc.expires_at, dc.payload::text as payload
                      from device_command dc
                      join device d on d.id = dc.device_id and d.tenant_id = dc.tenant_id
                      left join connector c on c.id = dc.connector_id and c.tenant_id = dc.tenant_id
                     where dc.tenant_id = ? and dc.status = 'PUBLISHED' and dc.expires_at > now()
                       and dc.published_at <= now() - interval '5 seconds'
                     order by dc.published_at
                     for update of dc skip locked
                     limit 50
                    """, (result, row) -> new DeviceCommand(
                            result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                            result.getString("device_code"), (Integer) result.getObject("connector_no"),
                            DeviceCommandType.valueOf(result.getString("command_type")),
                            result.getObject("expires_at", Timestamp.class).toInstant(),
                            result.getString("payload")), tenantId);
            for (DeviceCommand command : commands) {
                jdbc.update("update device_command set published_at = now(), version = version + 1 where tenant_id = ? and id = ?",
                        tenantId, command.commandId());
            }
            return commands;
        });
    }

    private void publishRetry(DeviceCommand command) {
        try {
            byte[] payload = json.writeValueAsString(command).getBytes(StandardCharsets.UTF_8);
            jetStream.publish("charging.command." + command.deviceCode(), payload,
                    PublishOptions.builder().messageId(command.commandId() + ":" + Instant.now().getEpochSecond()).build());
        } catch (Exception failure) {
            log.warn("Device command retry failed: commandId={}, reason={}",
                    command.commandId(), failure.getClass().getSimpleName());
        }
    }

    private List<PendingCommand> claim(UUID tenantId) {
        return tenantJdbc.readWriteAs(tenantId, () -> {
            List<PendingCommand> commands = jdbc.query("""
                    select e.id as event_id, e.attempts, dc.id as command_id, dc.tenant_id, d.device_code,
                           c.connector_no, dc.command_type, dc.expires_at, dc.payload::text as payload
                      from outbox_event e
                      join device_command dc on dc.id = e.aggregate_id and dc.tenant_id = e.tenant_id
                      join device d on d.id = dc.device_id and d.tenant_id = e.tenant_id
                      left join connector c on c.id = dc.connector_id and c.tenant_id = e.tenant_id
                     where e.tenant_id = ? and e.published_at is null and e.available_at <= now()
                       and e.event_type = 'DeviceCommandRequested' and dc.expires_at > now()
                     order by e.occurred_at
                     for update of e skip locked
                     limit 50
                    """, (result, row) -> new PendingCommand(
                            result.getObject("event_id", UUID.class), result.getInt("attempts") + 1,
                            new DeviceCommand(
                                    result.getObject("command_id", UUID.class),
                                    result.getObject("tenant_id", UUID.class),
                                    result.getString("device_code"),
                                    (Integer) result.getObject("connector_no"),
                                    DeviceCommandType.valueOf(result.getString("command_type")),
                                    result.getObject("expires_at", Timestamp.class).toInstant(),
                                    result.getString("payload"))), tenantId);
            for (PendingCommand command : commands) {
                jdbc.update("""
                        update outbox_event set attempts = attempts + 1,
                               available_at = now() + interval '30 seconds'
                         where tenant_id = ? and id = ? and published_at is null
                        """, tenantId, command.eventId());
            }
            return commands;
        });
    }

    private void publish(PendingCommand pending) {
        DeviceCommand command = pending.command();
        try {
            byte[] payload = json.writeValueAsString(command).getBytes(StandardCharsets.UTF_8);
            jetStream.publish("charging.command." + command.deviceCode(), payload,
                    PublishOptions.builder().messageId(pending.eventId() + ":" + pending.attempt()).build());
            tenantJdbc.readWriteAs(command.tenantId(), () -> {
                jdbc.update("update outbox_event set published_at = now(), last_error = null where tenant_id = ? and id = ?",
                        command.tenantId(), pending.eventId());
                jdbc.update("""
                        update device_command set status = 'PUBLISHED', published_at = now(), updated_at = now(), version = version + 1
                         where tenant_id = ? and id = ? and status = 'PENDING'
                        """, command.tenantId(), command.commandId());
                return null;
            });
        } catch (Exception failure) {
            String safeError = failure.getClass().getSimpleName();
            tenantJdbc.readWriteAs(command.tenantId(), () -> {
                jdbc.update("update outbox_event set last_error = ? where tenant_id = ? and id = ?",
                        safeError, command.tenantId(), pending.eventId());
                return null;
            });
            log.warn("Device command publication failed: commandId={}, reason={}", command.commandId(), safeError);
        }
    }

    record PendingCommand(UUID eventId, int attempt, DeviceCommand command) { }
}
