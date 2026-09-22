package io.smartcharge.platform.gateway.transport;

import io.smartcharge.platform.gateway.GatewayProperties;
import io.smartcharge.platform.gateway.broker.DeviceEventPublisher;
import io.smartcharge.platform.gateway.security.DeviceMessageVerifier;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
final class NettyDeviceServer implements SmartLifecycle {
    private final GatewayProperties properties;
    private final DeviceMessageVerifier verifier;
    private final DeviceEventPublisher publisher;
    private final DeviceSessionRegistry sessions;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel serverChannel;
    private volatile boolean running;

    NettyDeviceServer(GatewayProperties properties, DeviceMessageVerifier verifier,
                      DeviceEventPublisher publisher, DeviceSessionRegistry sessions) {
        this.properties = properties;
        this.verifier = verifier;
        this.publisher = publisher;
        this.sessions = sessions;
    }

    @Override
    public void start() {
        try {
            SslContext sslContext = buildSslContext();
            boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            workers = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            if (sslContext != null) {
                                channel.pipeline().addLast("tls", sslContext.newHandler(channel.alloc()));
                            }
                            channel.pipeline()
                                    .addLast("frame", new LineBasedFrameDecoder(properties.maxFrameLength()))
                                    .addLast("decode", new StringDecoder(StandardCharsets.UTF_8))
                                    .addLast("encode", new StringEncoder(StandardCharsets.UTF_8))
                                    .addLast("messages", new DeviceChannelHandler(json, verifier, publisher, sessions));
                        }
                    });
            serverChannel = bootstrap.bind(properties.port()).syncUninterruptibly().channel();
            running = true;
        } catch (Exception exception) {
            stop();
            throw new IllegalStateException("Unable to start device gateway", exception);
        }
    }

    private SslContext buildSslContext() throws Exception {
        GatewayProperties.Tls tls = properties.tls();
        if (tls == null || !tls.enabled()) return null;
        return SslContextBuilder.forServer(new File(tls.certificateChain()), new File(tls.privateKey()))
                .trustManager(new File(tls.trustCertificates()))
                .clientAuth(ClientAuth.REQUIRE)
                .build();
    }

    @Override
    public void stop() {
        if (serverChannel != null) serverChannel.close().syncUninterruptibly();
        if (workers != null) workers.shutdownGracefully();
        if (boss != null) boss.shutdownGracefully();
        running = false;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
