package io.smartcharge.platform.shared.web;

import java.time.Instant;
import java.util.List;

public record ApiError(String code, String message, Instant timestamp, List<FieldViolation> violations) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now(), List.of());
    }

    public record FieldViolation(String field, String message) { }
}
