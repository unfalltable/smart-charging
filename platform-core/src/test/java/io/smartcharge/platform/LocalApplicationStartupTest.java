package io.smartcharge.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=local",
        "charging.qr-signing-secret=startup-test-qr-key-at-least-32-characters",
        "charging.device-credentials.master-key-base64=",
        "spring.flyway.enabled=true"
})
class LocalApplicationStartupTest {
    @MockitoBean DataSource dataSource;
    @MockitoBean Flyway flyway;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean Connection natsConnection;
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS) JetStream jetStream;
    @Value("${local.server.port}") int port;

    @Test
    void localApplicationStartsAndExposesReadiness() throws Exception {
        verify(flyway).migrate();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        }
    }
}
