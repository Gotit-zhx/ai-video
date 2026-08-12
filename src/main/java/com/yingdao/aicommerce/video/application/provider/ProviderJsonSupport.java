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
        try {
            String value = RestClient.create().post().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(String.class);
            return read(value);
        } catch (RestClientResponseException exception) {
            throw new VideoProviderException(readable(exception), true);
        } catch (ResourceAccessException exception) {
            throw new VideoProviderException("连接厂商时网络结果未知：" + rootMessage(exception), false);
        }
    }

    protected Map<String, Object> get(String url, String apiKey) {
        try {
            String value = RestClient.create().get().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve().body(String.class);
            return read(value);
        } catch (RestClientResponseException exception) {
            throw new VideoProviderException(readable(exception), false);
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
