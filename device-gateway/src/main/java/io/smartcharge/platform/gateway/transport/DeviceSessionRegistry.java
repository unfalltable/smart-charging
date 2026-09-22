package io.smartcharge.platform.gateway.transport;

import io.netty.channel.Channel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public final class DeviceSessionRegistry {
    private final ConcurrentMap<String, Channel> sessions = new ConcurrentHashMap<>();

    void bind(String deviceCode, Channel channel) {
        Channel previous = sessions.put(deviceCode, channel);
        if (previous != null && previous != channel) previous.close();
    }

    void unbind(String deviceCode, Channel channel) {
        if (deviceCode != null) sessions.remove(deviceCode, channel);
    }

    public boolean send(String deviceCode, String message) {
        Channel channel = sessions.get(deviceCode);
        if (channel == null || !channel.isActive()) return false;
        channel.writeAndFlush(message);
        return true;
    }
}
