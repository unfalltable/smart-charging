package io.smartcharge.platform.finance;

import io.smartcharge.platform.shared.domain.DomainException;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local")
final class EnvironmentSecretMaterialProvider {
    WeChatMaterial weChat(String reference) {
        if (reference == null || !reference.matches("env:[A-Z][A-Z0-9_]{1,80}")) {
            throw new DomainException("WeChat secret reference must use env:PREFIX format");
        }
        String prefix = reference.substring(4).toUpperCase(Locale.ROOT);
        return new WeChatMaterial(required(prefix + "_PRIVATE_KEY_PATH"),
                required(prefix + "_MERCHANT_SERIAL"), required(prefix + "_API_V3_KEY"),
                required(prefix + "_PUBLIC_KEY_PATH"), required(prefix + "_PUBLIC_KEY_ID"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new DomainException("Required payment secret is unavailable: " + name);
        return value;
    }

    record WeChatMaterial(String privateKeyPath, String merchantSerial, String apiV3Key,
                          String publicKeyPath, String publicKeyId) { }
}
