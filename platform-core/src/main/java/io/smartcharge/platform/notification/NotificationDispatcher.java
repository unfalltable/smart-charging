package io.smartcharge.platform.notification;

import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final List<NotificationSender> senders;

    NotificationDispatcher(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, List<NotificationSender> senders) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.senders = List.copyOf(senders);
    }

    @Scheduled(fixedDelayString = "${notifications.dispatch-delay-ms:1000}")
    void dispatch() {
        List<UUID> tenants = jdbc.query("select id from tenant where status='ACTIVE'",
                (result, row) -> result.getObject(1, UUID.class));
        for (UUID tenantId : tenants) {
            List<NotificationSender.NotificationMessage> messages = tenantJdbc.readWriteAs(tenantId,
                    () -> claim(tenantId));
            for (NotificationSender.NotificationMessage message : messages) send(message);
        }
    }

    private List<NotificationSender.NotificationMessage> claim(UUID tenantId) {
        List<NotificationSender.NotificationMessage> messages = jdbc.query("""
                select id, channel, template_code, recipient, payload::text
                  from notification_outbox
                 where tenant_id=? and status in ('PENDING','FAILED') and available_at<=now() and attempts<8
                 order by available_at for update skip locked limit 50
                """, (result, row) -> new NotificationSender.NotificationMessage(
                result.getObject("id", UUID.class), tenantId, result.getString("channel"),
                result.getString("template_code"), result.getString("recipient"), result.getString("payload")), tenantId);
        for (NotificationSender.NotificationMessage message : messages) {
            jdbc.update("""
                    update notification_outbox set status='SENDING', attempts=attempts+1
                     where tenant_id=? and id=?
                    """, tenantId, message.id());
        }
        return messages;
    }

    private void send(NotificationSender.NotificationMessage message) {
        try {
            NotificationSender sender = senders.stream().filter(candidate -> candidate.supports(message.channel()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Notification channel is not configured"));
            sender.send(message);
            tenantJdbc.readWriteAs(message.tenantId(), () -> {
                jdbc.update("""
                        update notification_outbox set status='SENT', sent_at=now(), last_error=null
                         where tenant_id=? and id=? and status='SENDING'
                        """, message.tenantId(), message.id());
                return null;
            });
        } catch (RuntimeException failure) {
            log.warn("Notification delivery failed: id={}, reason={}", message.id(), failure.getClass().getSimpleName());
            tenantJdbc.readWriteAs(message.tenantId(), () -> {
                jdbc.update("""
                        update notification_outbox set status='FAILED', last_error=?,
                               available_at=now()+(interval '1 second' * least(3600, power(2, attempts)))
                         where tenant_id=? and id=? and status='SENDING'
                        """, failure.getClass().getSimpleName(), message.tenantId(), message.id());
                return null;
            });
        }
    }
}
