package io.smartcharge.platform.gateway.broker;

import io.smartcharge.platform.contracts.DeviceEnvelope;
import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public final class DeviceEventPublisher {
    private final JetStream jetStream;

    public DeviceEventPublisher(JetStream jetStream) {
        this.jetStream = jetStream;
    }

    public void publish(DeviceEnvelope envelope, String sourceJson) throws Exception {
        String subject = "charging.device." + envelope.deviceCode() + ".event." +
                envelope.eventType().name().toLowerCase();
        jetStream.publish(subject, sourceJson.getBytes(StandardCharsets.UTF_8),
                PublishOptions.builder().messageId(envelope.messageId().toString()).build());
    }
}
