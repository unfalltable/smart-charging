package io.smartcharge.platform.tenancy;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Identifies the deliberately configured upstream platform administrator. */
@Component
public final class PlatformAuthority {
    public PlatformAuthority() { }

    public boolean isPlatformAdministrator(Authentication authentication) {
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "SCOPE_platform_admin".equals(authority.getAuthority()))) {
            return true;
        }
        return false;
    }

    public void requirePlatformAdministrator(Authentication authentication) {
        if (!isPlatformAdministrator(authentication)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Platform administrator authority is required");
        }
    }
}
