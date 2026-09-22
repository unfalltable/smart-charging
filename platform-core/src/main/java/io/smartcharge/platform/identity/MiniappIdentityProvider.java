package io.smartcharge.platform.identity;

interface MiniappIdentityProvider {
    boolean supports(String provider, String tenantCode);
    ExternalIdentity exchange(String authorizationCode);

    record ExternalIdentity(String providerSubject, String unionSubject) { }
}
