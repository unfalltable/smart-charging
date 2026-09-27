package io.smartcharge.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentityAdminGatewayTest {
    @Test
    void mapsTenantRolesToIdentityRealmRoles() {
        assertThat(IdentityAdminGateway.realmRole("TENANT_ADMIN")).isEqualTo("admin");
        assertThat(IdentityAdminGateway.realmRole("OPERATOR")).isEqualTo("operator");
        assertThat(IdentityAdminGateway.realmRole("FINANCE")).isEqualTo("finance");
        assertThat(IdentityAdminGateway.realmRole("AUDITOR")).isEqualTo("auditor");
        assertThat(IdentityAdminGateway.realmRole("SUPPORT")).isEqualTo("support");
        assertThatThrownBy(() -> IdentityAdminGateway.realmRole("PLATFORM_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void highAssuranceInvitationRequiresPasswordAndTotpSetup() {
        assertThat(IdentityAdminGateway.requiredActions(true))
                .containsExactlyInAnyOrder("UPDATE_PASSWORD", "CONFIGURE_TOTP", "VERIFY_EMAIL");
    }
}
