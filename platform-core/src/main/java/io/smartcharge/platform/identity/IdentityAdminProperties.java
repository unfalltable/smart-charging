package io.smartcharge.platform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("charging.identity.admin")
public class IdentityAdminProperties {
    private int invitationLifespanHours = 48;

    public int getInvitationLifespanHours() { return invitationLifespanHours; }
    public void setInvitationLifespanHours(int invitationLifespanHours) { this.invitationLifespanHours = invitationLifespanHours; }
}
