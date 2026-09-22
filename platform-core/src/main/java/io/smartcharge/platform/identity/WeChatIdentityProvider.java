package io.smartcharge.platform.identity;

import io.smartcharge.platform.shared.domain.DomainException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("!local")
@ConditionalOnProperty(prefix = "charging.identity.wechat", name = {"app-id", "app-secret", "tenant-code"})
final class WeChatIdentityProvider implements MiniappIdentityProvider {
    private final WeChatIdentityProperties properties;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    WeChatIdentityProvider(WeChatIdentityProperties properties) {
        this.properties = properties;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public boolean supports(String provider, String tenantCode) {
        return "WECHAT".equals(provider) && properties.tenantCode().equals(tenantCode);
    }

    @Override
    public ExternalIdentity exchange(String authorizationCode) {
        String encodedCode = URLEncoder.encode(authorizationCode, StandardCharsets.UTF_8);
        String encodedAppId = URLEncoder.encode(properties.appId(), StandardCharsets.UTF_8);
        String encodedSecret = URLEncoder.encode(properties.appSecret(), StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.weixin.qq.com/sns/jscode2session?appid=" + encodedAppId
                + "&secret=" + encodedSecret + "&js_code=" + encodedCode + "&grant_type=authorization_code");
        String body = http.get().uri(uri).retrieve().body(String.class);
        try {
            JsonNode response = json.readTree(body);
            if (response.has("errcode") && response.path("errcode").asInt() != 0) {
                throw new DomainException("WeChat authorization code was rejected");
            }
            String openId = response.path("openid").asString("");
            if (openId.isBlank()) throw new DomainException("WeChat did not return a user identity");
            String unionId = response.path("unionid").asString("");
            return new ExternalIdentity(openId, unionId.isBlank() ? null : unionId);
        } catch (DomainException expected) {
            throw expected;
        } catch (Exception invalidResponse) {
            throw new DomainException("WeChat returned an invalid identity response");
        }
    }
}
