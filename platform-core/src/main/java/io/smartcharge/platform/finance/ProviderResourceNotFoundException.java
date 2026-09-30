package io.smartcharge.platform.finance;

final class ProviderResourceNotFoundException extends RuntimeException {
    ProviderResourceNotFoundException() {
        super("Payment provider confirmed that the requested transaction does not exist");
    }
}
