package io.smartcharge.platform.gateway.transport;

import io.smartcharge.platform.contracts.DeviceEnvelope;
import io.smartcharge.platform.gateway.broker.DeviceEventPublisher;
import io.smartcharge.platform.gateway.security.DeviceAuthenticationException;
import io.smartcharge.platform.gateway.security.DeviceMessageVerifier;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

final class DeviceChannelHandler extends SimpleChannelInboundHandler<String> {
    private final JsonMapper json;
    private final DeviceMessageVerifier verifier;
    private final DeviceEventPublisher publisher;
    private final DeviceSessionRegistry sessions;
    private String authenticatedDeviceCode;

    DeviceChannelHandler(JsonMapper json, DeviceMessageVerifier verifier, DeviceEventPublisher publisher,
                         DeviceSessionRegistry sessions) {
        this.json = json;
        this.verifier = verifier;
        this.publisher = publisher;
        this.sessions = sessions;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, String sourceJson) throws Exception {
        try {
            DeviceEnvelope envelope = json.readValue(sourceJson, DeviceEnvelope.class);
            verifier.verify(envelope);
            if (authenticatedDeviceCode != null && !authenticatedDeviceCode.equals(envelope.deviceCode())) {
                throw new DeviceAuthenticationException("A connection cannot change device identity");
            }
            authenticatedDeviceCode = envelope.deviceCode();
            sessions.bind(authenticatedDeviceCode, context.channel());
            publisher.publish(envelope, sourceJson);
            context.writeAndFlush(json.writeValueAsString(Map.of(
                    "messageId", envelope.messageId(), "accepted", true)) + "\n");
        } catch (DeviceAuthenticationException authenticationFailure) {
            context.writeAndFlush("{\"accepted\":false,\"code\":\"AUTHENTICATION_FAILED\"}\n")
                    .addListener(ignored -> context.close());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        sessions.unbind(authenticatedDeviceCode, context.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        context.close();
    }
}
