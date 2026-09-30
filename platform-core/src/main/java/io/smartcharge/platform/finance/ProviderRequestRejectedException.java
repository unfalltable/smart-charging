package io.smartcharge.platform.finance;

final class ProviderRequestRejectedException extends RuntimeException {
    private final String code;

    ProviderRequestRejectedException(String code) {
        super("Payment provider rejected request: " + code);
        this.code = code;
    }

    String code() { return code; }
}
