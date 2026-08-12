package com.yingdao.aicommerce.common.api;

import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, "OK", Instant.now());
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, data, "CREATED", Instant.now());
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, null, message, Instant.now());
    }
}

