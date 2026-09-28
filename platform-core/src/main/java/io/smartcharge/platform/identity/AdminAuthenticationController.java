package io.smartcharge.platform.identity;

import io.smartcharge.platform.shared.domain.AuthenticationFailureException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/admin")
@ConditionalOnProperty(prefix = "charging.security", name = "identity-provider-mode", havingValue = "database")
final class AdminAuthenticationController {
    private final AdminAuthenticationService authentication;

    AdminAuthenticationController(AdminAuthenticationService authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/login")
    AdminTokenService.Session login(@Valid @RequestBody LoginRequest request) {
        return authentication.login(request.username(), request.password());
    }

    @PostMapping("/refresh")
    AdminTokenService.Session refresh(@Valid @RequestBody RefreshRequest request) {
        return authentication.refresh(request.refreshToken());
    }

    @PostMapping("/password")
    AdminTokenService.Session changePassword(@Valid @RequestBody PasswordRequest request,
                                             Authentication principal) {
        return authentication.changePassword(adminUserId(principal), request.currentPassword(), request.newPassword());
    }

    @PostMapping("/logout")
    void logout(@Valid @RequestBody LogoutRequest request, Authentication principal) {
        authentication.logout(adminUserId(principal), request.refreshToken());
    }

    private static UUID adminUserId(Authentication principal) {
        if (!(principal instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailureException("登录状态无效");
        }
        String rawId = jwt.getToken().getClaimAsString("admin_user_id");
        try {
            return UUID.fromString(rawId);
        } catch (RuntimeException invalid) {
            throw new AuthenticationFailureException("登录状态无效");
        }
    }

    record LoginRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}") String username,
            @NotBlank @Size(min = 8, max = 128) String password) { }
    record RefreshRequest(@NotBlank @Size(min = 40, max = 100) String refreshToken) { }
    record PasswordRequest(@NotBlank @Size(min = 8, max = 128) String currentPassword,
                           @NotBlank @Size(min = 12, max = 128) String newPassword) { }
    record LogoutRequest(@Size(max = 100) String refreshToken) { }
}
