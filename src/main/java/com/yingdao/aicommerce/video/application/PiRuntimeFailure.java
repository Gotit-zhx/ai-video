package com.yingdao.aicommerce.video.application;

import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** Reads the runtime's explicit retry contract without interpreting words inside its message. */
final class PiRuntimeFailure {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PiRuntimeFailure() {}

    record Details(Boolean retryable, String failureCode, String message, Integer upstreamStatus) {
        boolean explicitlyNonRetryable() { return Boolean.FALSE.equals(retryable); }
    }

    static Details parse(String response) {
        if (response == null) return new Details(null, null, null, null);
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) return new Details(null, null, null, null);
        try {
            Map<?, ?> value = JSON.readValue(response.substring(start, end + 1), Map.class);
            return new Details(value.get("retryable") instanceof Boolean retryable ? retryable : null,
                    text(value.get("failureCode")), text(value.get("message")),
                    value.get("upstreamStatus") instanceof Number status ? status.intValue() : null);
        } catch (Exception ignored) {
            return new Details(null, null, null, null);
        }
    }

    static Details from(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException response) {
                Details details = parse(response.getResponseBodyAsString());
                return new Details(details.retryable(), details.failureCode() == null
                        ? "UPSTREAM_HTTP_" + response.getStatusCode().value() : details.failureCode(),
                        details.message() == null ? response.getStatusText() : details.message(),
                        details.upstreamStatus());
            }
        }
        Details details = parse(failure == null ? null : failure.getMessage());
        if (details.failureCode() != null || details.retryable() != null) {
            return new Details(details.retryable(), details.failureCode() == null ? "MODEL_REQUEST_FAILED" : details.failureCode(),
                    details.message() == null && failure != null ? failure.getMessage() : details.message(), details.upstreamStatus());
        }
        return new Details(null, "MODEL_REQUEST_FAILED", failure == null ? "未知错误" : failure.getMessage(), null);
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
