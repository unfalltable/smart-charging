package io.smartcharge.platform.identity;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class CurrentCustomer {
    public UUID requireId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String claim = jwt.getToken().getClaimAsString("customer_id");
            if (claim == null) throw new IllegalStateException("Token has no customer identity");
            return UUID.fromString(claim);
        }
        throw new IllegalStateException("Authenticated customer identity is missing");
    }
}
