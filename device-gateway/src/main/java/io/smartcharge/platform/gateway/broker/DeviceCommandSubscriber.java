package io.smartcharge.platform.gateway.broker;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.smartcharge.platform.contracts.DeviceCommand;
import io.smartcharge.platform.contracts.SignedDeviceCommand;
import io.smartcharge.platform.gateway.security.DeviceCommandSigner;
import io.smartcharge.platform.gateway.transport.DeviceSessionRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
final class DeviceCommandSubscriber implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DeviceCommandSubscriber.class);
    private final Connection connection;
    private final DeviceCommandSigner signer;
    private final DeviceSessionRegistry sessions;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private Dispatcher dispatcher;
    private volatile boolean running;

    DeviceCommandSubscriber(Connection connection, DeviceCommandSigner signer, DeviceSessionRegistry sessions) {
        this.connection = connection;
        this.signer = signer;
        this.sessions = sessions;
    }

    @Override
    public void start() {
        dispatcher = connection.createDispatcher(message -> {
            try {
                DeviceCommand command = json.readValue(
                        new String(message.getData(), StandardCharsets.UTF_8), DeviceCommand.class);
                if (command.expiresAt().isBefore(Instant.now())) return;
                SignedDeviceCommand signed = signer.sign(command);
                boolean delivered = sessions.send(command.deviceCode(), json.writeValueAsString(signed) + "\n");
                if (!delivered) log.debug("Device is offline for commandId={}", command.commandId());
            } catch (Exception failure) {
                log.warn("Rejected device command message: reason={}", failure.getClass().getSimpleName());
            }
        });
        dispatcher.subscribe("charging.command.*");
        running = true;
    }

    @Override
    public void stop() {
        if (dispatcher != null) connection.closeDispatcher(dispatcher);
        running = false;
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 200; }
}
