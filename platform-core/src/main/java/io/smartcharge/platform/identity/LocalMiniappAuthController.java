package io.smartcharge.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1/auth/miniapp")
final class LocalMiniappAuthController {
    private static final UUID LOCAL_TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @PostMapping("/login")
    LocalSession login(@Valid @RequestBody LoginRequest request) {
        return new LocalSession("local-development-token", LOCAL_TENANT_ID, CurrentCustomer.LOCAL_CUSTOMER_ID);
    }

    record LoginRequest(@Pattern(regexp = "WECHAT|ALIPAY") String provider, @NotBlank String code) { }
    record LocalSession(String accessToken, UUID tenantId, UUID customerId) { }
}
