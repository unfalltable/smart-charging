package io.smartcharge.platform.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
final class LocalNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(LocalNotificationSender.class);

    @Override
    public boolean supports(String channel) {
        return true;
    }

    @Override
    public void send(NotificationMessage message) {
        log.info("Local notification delivered: id={}, channel={}, template={}",
                message.id(), message.channel(), message.templateCode());
    }
}
