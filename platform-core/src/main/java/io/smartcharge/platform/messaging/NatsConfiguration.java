package io.smartcharge.platform.messaging;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NatsConfiguration {
    @Bean(destroyMethod = "close")
    Connection natsConnection(@Value("${nats.url}") String url) throws Exception {
        return Nats.connect(new Options.Builder()
                .server(url)
                .connectionTimeout(Duration.ofSeconds(3))
                .maxReconnects(-1)
                .build());
    }

    @Bean
    JetStream jetStream(Connection connection) throws Exception {
        JetStreamManagement management = connection.jetStreamManagement();
        ensureStream(management, "CHARGING_COMMANDS", "charging.command.>", Duration.ofDays(7));
        ensureStream(management, "DEVICE_EVENTS", "charging.device.>", Duration.ofDays(14));
        return connection.jetStream();
    }

    private static void ensureStream(JetStreamManagement management, String name, String subject, Duration maxAge)
            throws Exception {
        try {
            management.getStreamInfo(name);
        } catch (JetStreamApiException missing) {
            if (missing.getErrorCode() != 404) throw missing;
            try {
                management.addStream(StreamConfiguration.builder()
                        .name(name)
                        .subjects(subject)
                        .storageType(StorageType.File)
                        .maxAge(maxAge)
                        .duplicateWindow(Duration.ofMinutes(10))
                        .build());
            } catch (JetStreamApiException racedCreation) {
                management.getStreamInfo(name);
            }
        }
    }
}
