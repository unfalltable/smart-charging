package io.smartcharge.platform.notification;

import io.smartcharge.platform.identity.WeChatIdentityProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(prefix = "charging.notification.wechat", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WeChatNotificationProperties.class)
final class WeChatNotificationSender implements NotificationSender {
    private static final TypeReference<Map<String, Template>> TEMPLATES = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() { };
    private final WeChatIdentityProperties identity;
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final RestClient http;
    private final Map<String, Template> templates;

    WeChatNotificationSender(WeChatIdentityProperties identity, WeChatNotificationProperties properties,
                             StringRedisTemplate redis) {
        this.identity = identity;
        this.redis = redis;
        this.templates = parseTemplates(properties.templatesJson());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(requestFactory).build();
        if (identity.appId() == null || identity.appId().isBlank()
                || identity.appSecret() == null || identity.appSecret().isBlank()) {
            throw new IllegalStateException("WeChat notification delivery requires WECHAT_APP_ID and WECHAT_APP_SECRET");
        }
        if (templates.isEmpty()) throw new IllegalStateException("WeChat notification templates are not configured");
    }

    @Override
    public boolean supports(String channel) {
        return "WECHAT".equals(channel);
    }

    @Override
    public void send(NotificationMessage message) {
        if (message.recipient() == null || message.recipient().isBlank()) {
            throw new IllegalArgumentException("WeChat notification recipient is missing");
        }
        Template template = templates.get(message.templateCode());
        if (template == null) throw new IllegalStateException("WeChat notification template is not configured");
        Map<String, Object> source = parsePayload(message.payload());
        Map<String, Object> data = new LinkedHashMap<>();
        template.bindings().forEach((templateField, payloadField) -> data.put(templateField,
                Map.of("value", displayValue(payloadField, source.get(payloadField)))));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("touser", message.recipient());
        request.put("template_id", template.templateId());
        if (template.page() != null && !template.page().isBlank()) request.put("page", template.page());
        request.put("data", data);
        String response = http.post().uri(URI.create(
                        "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token="
                                + encode(accessToken())))
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(String.class);
        requireSuccess(response, "send subscription message");
    }

    private String accessToken() {
        String key = "wechat:access-token:" + identity.appId();
        String cached = redis.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) return cached;
        URI uri = URI.create("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                + encode(identity.appId()) + "&secret=" + encode(identity.appSecret()));
        String response = http.get().uri(uri).retrieve().body(String.class);
        try {
            JsonNode body = json.readTree(response);
            String token = body.path("access_token").asString("");
            if (token.isBlank()) throw new IllegalStateException("WeChat access token request was rejected");
            long expires = Math.max(60, body.path("expires_in").asLong(7200) - 300);
            redis.opsForValue().set(key, token, Duration.ofSeconds(expires));
            return token;
        } catch (RuntimeException expected) {
            throw expected;
        } catch (Exception invalid) {
            throw new IllegalStateException("WeChat returned an invalid access token response", invalid);
        }
    }

    private void requireSuccess(String response, String operation) {
        try {
            JsonNode body = json.readTree(response);
            if (body.path("errcode").asInt(-1) != 0) {
                throw new IllegalStateException("WeChat could not " + operation + ": error "
                        + body.path("errcode").asInt());
            }
        } catch (RuntimeException expected) {
            throw expected;
        } catch (Exception invalid) {
            throw new IllegalStateException("WeChat returned an invalid notification response", invalid);
        }
    }

    private Map<String, Template> parseTemplates(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, Template> configured = json.readValue(value, TEMPLATES);
            configured.forEach((code, template) -> {
                if (template.templateId() == null || template.templateId().isBlank()
                        || template.bindings() == null || template.bindings().isEmpty()) {
                    throw new IllegalStateException("Invalid WeChat notification template: " + code);
                }
            });
            return Map.copyOf(configured);
        } catch (RuntimeException expected) {
            throw expected;
        } catch (Exception invalid) {
            throw new IllegalStateException("WECHAT_NOTIFICATION_TEMPLATES_JSON is invalid", invalid);
        }
    }

    private Map<String, Object> parsePayload(String value) {
        try {
            return json.readValue(value, PAYLOAD);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Notification payload is invalid", invalid);
        }
    }

    private static String displayValue(String field, Object value) {
        if (value == null) return "-";
        if (field.endsWith("Minor") && value instanceof Number number) {
            return String.format(java.util.Locale.ROOT, "%.2f元", number.longValue() / 100.0);
        }
        String text = value.toString();
        return text.length() <= 20 ? text : text.substring(0, 20);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Template(String templateId, String page, Map<String, String> bindings) { }
}
