package io.smartcharge.platform.identity;

import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class CurrentCustomer {
    public static final UUID LOCAL_CUSTOMER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final Environment environment;

    public CurrentCustomer(Environment environment) {
        this.environment = environment;
    }

    public UUID requireId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String claim = jwt.getToken().getClaimAsString("customer_id");
            if (claim == null) throw new IllegalStateException("Token has no customer identity");
            return UUID.fromString(claim);
        }
        if (environment.acceptsProfiles(Profiles.of("local"))) return LOCAL_CUSTOMER_ID;
        throw new IllegalStateException("Authenticated customer identity is missing");
    }
}
