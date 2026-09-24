package io.smartcharge.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "gateway.tls.enabled=false", "spring.data.redis.password="
})
class GatewayStartupTest {
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS) Connection natsConnection;
    @MockitoBean JetStream jetStream;
    @Value("${local.management.port}") int httpPort;
    @Value("${gateway.port}") int tcpPort;

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) throws Exception {
        try (var socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            registry.add("gateway.port", () -> port);
        }
    }

    @Test
    void readinessIsServedOverHttpAndDevicePortAcceptsConnections() throws Exception {
        try (var client = HttpClient.newHttpClient(); var device = new Socket("127.0.0.1", tcpPort)) {
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + httpPort + "/actuator/health/readiness")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
            assertThat(device.isConnected()).isTrue();
        }
    }
}
