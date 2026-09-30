package io.smartcharge.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP and security filters are real; only external message/rate services are isolated. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=true", "operations.recovery-delay-ms=3600000"})
@ActiveProfiles("production")
@EnabledIfEnvironmentVariable(named = "REQUIRE_DATABASE_TESTS", matches = "true")
class CommercialApiIntegrationTest {
    @MockitoBean Connection natsConnection;
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS) JetStream jetStream;
    @MockitoBean StringRedisTemplate redis;
    @Value("${local.server.port}") int port;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry properties) throws Exception {
        var db = DatabaseTestSupport.database();
        properties.add("spring.datasource.url", db::url);
        properties.add("spring.datasource.username", () -> "app_runtime");
        properties.add("spring.datasource.password", () -> "isolated-test-runtime-password");
        properties.add("spring.flyway.url", db::url);
        properties.add("spring.flyway.user", db::ownerUser);
        properties.add("spring.flyway.password", db::ownerPassword);
    }

    @Test
    void superAdminProvisioningAndTenantAuthorizationWorkThroughTheActualHttpChain() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        try (var client = HttpClient.newHttpClient()) {
            var first = call(client, "POST", "/auth/admin/login", Map.of("username", "platform-admin",
                    "password", "Test-only-platform-password-42!"), "", "", 200);
            assertThat(first.path("mustChangePassword").asBoolean()).isTrue();
            String initial = first.path("accessToken").asText();
            call(client, "GET", "/platform/tenants", null, initial, "", 403);
            var changed = call(client, "POST", "/auth/admin/password", Map.of("currentPassword",
                    "Test-only-platform-password-42!", "newPassword", "Updated-Http-Verification-573!"), initial, "", 200);
            String platform = changed.path("accessToken").asText();
            call(client, "GET", "/session", null, initial, "", 401);
            assertThat(call(client, "GET", "/session", null, platform, "", 200)
                    .path("platformAdministrator").asBoolean()).isTrue();
            String code = "http-" + UUID.randomUUID();
            var tenant = call(client, "POST", "/platform/tenants", Map.of("code", code,
                    "displayName", "Isolated HTTP verification"), platform, "", 201);
            String tenantId = tenant.path("id").asText();
            call(client, "GET", "/operations/dashboard", null, platform, tenantId, 200);
            var secondTier = call(client, "POST", "/admin/organizations", Map.of("parentId", tenantId,
                    "code", "VERIFY_SECOND", "name", "Verification second tier",
                    "organizationType", "SECOND_TIER_PARTNER"), platform, tenantId, 201);
            var thirdTier = call(client, "POST", "/admin/organizations", Map.of("parentId", secondTier.path("id").asText(),
                    "code", "VERIFY_THIRD", "name", "Verification third tier",
                    "organizationType", "THIRD_TIER_FRANCHISE"), platform, tenantId, 201);
            assertThat(thirdTier.path("hierarchyLevel").asInt()).isEqualTo(3);
            var station = call(client, "POST", "/admin/assets/stations", Map.of("organizationId", thirdTier.path("id").asText(),
                    "code", "VERIFY_STATION", "name", "Verification station", "timezone", "Asia/Shanghai", "status", "ACTIVE"),
                    platform, tenantId, 201);
            call(client, "POST", "/admin/assets/devices", Map.of("stationId", station.path("id").asText(),
                    "deviceCode", "verify-device." + UUID.randomUUID(), "protocolCode", "SC1", "productModel",
                    "Verification device", "connectorCount", 12, "ratedPowerW", 2000), platform, tenantId, 201);
            var connectors = call(client, "GET", "/admin/assets/connectors", null, platform, tenantId, 200);
            assertThat(connectors.size()).isEqualTo(12);
            var tariff = call(client, "POST", "/admin/tariffs", Map.of("name", "Verification tariff", "billingMode", "DURATION",
                    "durationUnitPriceMinor", 2, "energyUnitPriceMinor", 0, "minimumAmountMinor", 0,
                    "effectiveFrom", Instant.now().minusSeconds(60).toString()), platform, tenantId, 201);
            String tariffId = tariff.path("id").asText();
            call(client, "POST", "/admin/tariffs/" + tariffId + "/activate", Map.of("version", 0), platform, tenantId, 200);
            String connectorId = connectors.get(0).path("id").asText();
            call(client, "POST", "/admin/tariffs/" + tariffId + "/connectors/" + connectorId,
                    Map.of(), platform, tenantId, 200);
            String qr = call(client, "POST", "/admin/assets/connectors/" + connectorId + "/qr",
                    Map.of(), platform, tenantId, 200).path("token").asText();
            var scanned = call(client, "GET", "/public/scan/" + qr, null, "", "", 200);
            assertThat(scanned.path("priceText").asText()).isEqualTo("2 分/分钟");
            assertThat(scanned.path("status").asText()).isEqualTo("OFFLINE");
            var fixtureJdbc = DatabaseTestSupport.database().ownerJdbc();
            UUID customer = UUID.randomUUID(), runningOrder = UUID.randomUUID();
            fixtureJdbc.update("insert into customer(id,tenant_id,status) values (?,?,'ACTIVE')", customer, UUID.fromString(tenantId));
            fixtureJdbc.update("""
                    insert into charging_order(id,tenant_id,order_no,customer_id,connector_id,tariff_id,status,idempotency_key)
                    values (?,?,?,?,?,?,'CHARGING',?)
                    """, runningOrder, UUID.fromString(tenantId), "V" + runningOrder, customer,
                    UUID.fromString(connectorId), UUID.fromString(tariffId), runningOrder.toString());
            call(client, "PATCH", "/platform/tenants/" + tenantId + "/status", Map.of("status", "SUSPENDED"), platform, "", 409);
            call(client, "POST", "/admin/operations/orders/" + runningOrder + "/stop", Map.of("reason", "Verification safety request"),
                    platform, tenantId, 202);
            call(client, "POST", "/admin/operations/orders/" + runningOrder + "/stop", Map.of("reason", "Verification retry"),
                    platform, tenantId, 202);
            assertThat(fixtureJdbc.queryForObject("select count(*) from device_command where order_id=? and command_type='STOP_CHARGING'",
                    Integer.class, runningOrder)).isEqualTo(1);
            String username = "http-" + UUID.randomUUID();
            var account = call(client, "POST", "/platform/users", Map.of("tenantId", tenantId,
                    "username", username, "displayName", "Verification operator", "roleCode", "OPERATOR"), platform, "", 201);
            String temporary = account.path("temporaryPassword").asText();
            assertThat(temporary).isNotBlank();
            var login = call(client, "POST", "/auth/admin/login", Map.of("username", username,
                    "password", temporary), "", "", 200);
            var operator = call(client, "POST", "/auth/admin/password", Map.of("currentPassword", temporary,
                    "newPassword", "Operator-Http-Verification-924!"), login.path("accessToken").asText(), "", 200);
            String operatorToken = operator.path("accessToken").asText();
            call(client, "GET", "/operations/dashboard", null, operatorToken, tenantId, 200);
            call(client, "GET", "/admin/assets/connectors", null, operatorToken, tenantId, 200);
            call(client, "GET", "/admin/finance/payments", null, operatorToken, tenantId, 403);
            call(client, "GET", "/platform/users", null, operatorToken, "", 403);
            call(client, "GET", "/operations/dashboard", null, operatorToken, UUID.randomUUID().toString(), 403);
        }
    }

    private JsonNode call(HttpClient client, String method, String path, Object body, String token,
                          String tenant, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        if (!tenant.isBlank()) request.header("X-Tenant-Id", tenant);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + " -> " + response.body()).isEqualTo(status);
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }
}
