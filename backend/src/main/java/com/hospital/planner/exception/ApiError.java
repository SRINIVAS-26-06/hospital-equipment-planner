package com.hospital.planner.exception;

import java.time.Instant;
import java.util.Map;

/** Every error response has this shape. */
public record ApiError(String code, String message, Instant timestamp, Map<String, String> fieldErrors) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now(), Map.of());
    }
}
