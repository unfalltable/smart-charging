package io.smartcharge.platform.tenancy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Identifies the deliberately configured upstream platform administrator. */
@Component
public final class PlatformAuthority {
    private final String identityProviderMode;
    private final String platformAdminUsername;

    public PlatformAuthority(
            @Value("${charging.security.identity-provider-mode:external}") String identityProviderMode,
            @Value("${charging.security.initial-admin-username:}") String platformAdminUsername) {
        this.identityProviderMode = identityProviderMode;
        this.platformAdminUsername = platformAdminUsername;
    }

    public boolean isPlatformAdministrator(Authentication authentication) {
        if (!"bundled".equals(identityProviderMode)
                || !(authentication instanceof JwtAuthenticationToken jwt)
                || platformAdminUsername.isBlank()
                || jwt.getToken().getSubject() == null
                || jwt.getToken().getSubject().isBlank()) {
            return false;
        }
        String username = jwt.getToken().getClaimAsString("preferred_username");
        return platformAdminUsername.equals(username);
    }
}
