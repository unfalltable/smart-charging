package io.smartcharge.platform.shared.domain;

public final class AuthenticationFailureException extends RuntimeException {
    public AuthenticationFailureException(String message) {
        super(message);
    }
}
