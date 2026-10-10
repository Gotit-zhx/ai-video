package com.yingdao.aicommerce.shop.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
public class MarketplaceHttp {
    private static final int MAX_ERROR_BODY = 3000;
    private static final Pattern QUOTED_SECRET = Pattern.compile(
            "(?i)([\\\"']?(?:access[_-]?token|refresh[_-]?token|app[_-]?secret|partner[_-]?key|"
                    + "authorization[_-]?code|auth[_-]?code|x-tts-access-token|authorization|sign)"
                    + "[\\\"']?\\s*[:=]\\s*)([\\\"'])(.*?)\\2");
    private static final Pattern TEXT_SECRET = Pattern.compile(
            "(?i)((?:access[_-]?token|refresh[_-]?token|app[_-]?secret|partner[_-]?key|"
                    + "authorization[_-]?code|auth[_-]?code|x-tts-access-token|authorization|sign)"
                    + "\\s*[:=]\\s*)(?:Bearer\\s+)?[^&,}\\s]+"
    );
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public MarketplaceHttp(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    MarketplaceHttp(ObjectMapper objectMapper, HttpClient client) {
        this.objectMapper = objectMapper;
        this.client = client;
    }

    public String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new MarketplaceApiException("无法序列化店铺接口请求", exception);
        }
    }

    public Map<String, Object> get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90)).GET();
        headers.forEach(builder::header);
        return sendJson(builder.build());
    }

    public Map<String, Object> postJson(URI uri, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return sendJson(builder.build());
    }

    public Map<String, Object> postMultipart(URI uri, Map<String, String> headers,
                                             String fileField, String filename,
                                             String contentType, byte[] bytes,
                                             Map<String, String> fields) {
        String boundary = "----heyi-" + UUID.randomUUID();
        byte[] body = multipart(boundary, fileField, filename, contentType, bytes, fields);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        return sendJson(builder.build());
    }

    public static URI uri(String baseUrl, String path, Map<String, ?> query) {
        StringBuilder value = new StringBuilder(trimSlash(baseUrl));
        if (!path.startsWith("/")) value.append('/');
        value.append(path);
        boolean first = true;
        for (Map.Entry<String, ?> entry : query.entrySet()) {
            if (entry.getValue() == null) continue;
            value.append(first ? '?' : '&');
            first = false;
            value.append(encode(entry.getKey())).append('=').append(encode(String.valueOf(entry.getValue())));
        }
        return URI.create(value.toString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    Map<String, Object> sendJson(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            long timeoutNanos = request.timeout().orElse(Duration.ofSeconds(60)).toNanos();
            long started = System.nanoTime();
            pending = client.sendAsync(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // JDK HttpRequest.timeout can expire only up to response headers. Bound the
            // entire body as well, and cancel its real exchange when the deadline elapses.
            long remaining = timeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException("Marketplace response deadline exceeded");
            HttpResponse<String> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MarketplaceApiException("店铺官方接口 HTTP " + response.statusCode()
                        + "：" + safeBody(body));
            }
            if (body.isBlank()) throw new MarketplaceApiException("店铺官方接口返回空响应");
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (MarketplaceApiException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            if (pending != null) pending.cancel(true);
            var timeout = new HttpTimeoutException("店铺官方接口完整响应超时");
            timeout.initCause(exception);
            throw new MarketplaceApiException("店铺官方接口完整响应超时，请稍后重试", timeout);
        } catch (InterruptedException exception) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new MarketplaceApiException("店铺官方接口请求被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            for (int depth = 0; cause instanceof java.util.concurrent.CompletionException
                    && cause.getCause() != null && depth < 16; depth++) cause = cause.getCause();
            if (cause instanceof MarketplaceApiException marketplace) throw marketplace;
            // Keep the underlying IOException/HttpTimeoutException available to media-only
            // retry policy. This common transport never automatically repeats a POST.
            throw new MarketplaceApiException("店铺官方接口请求失败", cause);
        } catch (Exception exception) {
            throw new MarketplaceApiException("店铺官方接口请求失败", exception);
        }
    }

    private byte[] multipart(String boundary, String fileField, String filename,
                             String contentType, byte[] bytes, Map<String, String> fields) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 2048);
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                        + entry.getKey() + "\"\r\n\r\n" + entry.getValue() + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                    + fileField + "\"; filename=\"" + filename.replace("\"", "")
                    + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception exception) {
            throw new MarketplaceApiException("无法构建图片上传请求", exception);
        }
    }

    String safeBody(String value) {
        String cleaned = structuredRedaction(value);
        return cleaned.length() <= MAX_ERROR_BODY ? cleaned : cleaned.substring(0, MAX_ERROR_BODY);
    }

    private String structuredRedaction(String value) {
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            return objectMapper.writeValueAsString(redact(parsed));
        } catch (Exception ignored) {
            String quoted = QUOTED_SECRET.matcher(value).replaceAll("$1$2REDACTED$2");
            return TEXT_SECRET.matcher(quoted).replaceAll("$1REDACTED");
        }
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                result.put(name, sensitiveKey(name) ? "REDACTED" : redact(item));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value;
    }

    private boolean sensitiveKey(String value) {
        String key = value == null ? "" : value.toLowerCase()
                .replace("_", "").replace("-", "");
        return key.equals("accesstoken") || key.equals("refreshtoken")
                || key.equals("appsecret") || key.equals("partnerkey")
                || key.equals("authorizationcode") || key.equals("authcode")
                || key.equals("xttsaccesstoken") || key.equals("authorization")
                || key.equals("sign");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimSlash(String value) {
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
