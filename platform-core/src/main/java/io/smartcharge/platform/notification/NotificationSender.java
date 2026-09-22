package io.smartcharge.platform.notification;

public interface NotificationSender {
    boolean supports(String channel);
    void send(NotificationMessage message);

    record NotificationMessage(java.util.UUID id, java.util.UUID tenantId, String channel,
                               String templateCode, String recipient, String payload) { }
}
