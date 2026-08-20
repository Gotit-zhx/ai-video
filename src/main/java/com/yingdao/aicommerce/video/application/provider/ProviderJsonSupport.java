package com.yingdao.aicommerce.video.application.provider;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

abstract class ProviderJsonSupport {
    protected final ObjectMapper objectMapper;

    protected ProviderJsonSupport(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    protected Map<String, Object> post(String url, String apiKey, Object body) {
        for (int attempt = 0; ; attempt++) {
            try {
                String value = RestClient.create().post().uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().body(String.class);
                return read(value);
            } catch (RestClientResponseException exception) {
                if (retryableSubmitFailure(exception) && attempt < 3) {
                    waitBeforeRetry(attempt);
                    continue;
                }
                throw new VideoProviderException(readable(exception), true, null,
                        rawJson(exception.getResponseBodyAsString()));
            } catch (ResourceAccessException exception) {
                throw new VideoProviderException("连接厂商时网络结果未知：" + rootMessage(exception), false);
            }
        }
    }

    private boolean retryableSubmitFailure(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        String value = exception.getResponseBodyAsString();
        return status == 429 || status == 502 || status == 503 || status == 504
                || value.contains("素材暂时无法读取")
                || value.contains("生成服务暂不可用")
                || value.toLowerCase().contains("temporarily unable to read");
    }

    private void waitBeforeRetry(int attempt) {
        long[] delays = {1_000L, 3_000L, 8_000L};
        try {
            Thread.sleep(delays[Math.min(attempt, delays.length - 1)]);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VideoProviderException("视频提交重试被中断", true);
        }
    }

    protected Map<String, Object> get(String url, String apiKey) {
        try {
            String value = RestClient.create().get().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve().body(String.class);
            return read(value);
        } catch (RestClientResponseException exception) {
            throw new VideoProviderException(readable(exception), false, null,
                    rawJson(exception.getResponseBodyAsString()));
        } catch (ResourceAccessException exception) {
            throw new VideoProviderException("查询厂商任务失败：" + rootMessage(exception), false);
        }
    }

    protected String endpoint(String baseUrl, String root, String suffix) {
        String base = baseUrl.replaceAll("/+$", "");
        if (base.endsWith(root)) return base + suffix;
        return base + root + suffix;
    }

    protected String text(Map<String, Object> value, String key) {
        Object raw = value == null ? null : value.get(key);
        return raw == null ? "" : String.valueOf(raw);
    }

    protected String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { return String.valueOf(value); }
    }

    protected String rawJson(String value) {
        if (value == null || value.isBlank()) return "{}";
        try { return json(objectMapper.readValue(value, Object.class)); }
        catch (Exception ignored) { return json(Map.of("raw", value)); }
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> map(Map<String, Object> value, String key) {
        Object raw = value == null ? null : value.get(key);
        return raw instanceof Map<?, ?> nested ? (Map<String, Object>) nested : Map.of();
    }

    private Map<String, Object> read(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try { return objectMapper.readValue(body, new TypeReference<>() {}); }
        catch (Exception exception) { throw new VideoProviderException("厂商返回了无法解析的数据", false); }
    }

    private String readable(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        try {
            Map<String, Object> parsed = read(body);
            String message = text(parsed, "message");
            if (!message.isBlank()) return "厂商拒绝请求（" + exception.getStatusCode().value() + "）：" + message;
        } catch (Exception ignored) {}
        return "厂商拒绝请求（" + exception.getStatusCode().value() + "）";
    }

    private String rootMessage(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
