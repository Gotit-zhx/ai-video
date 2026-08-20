package com.yingdao.aicommerce.video.application.provider;

import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** High-speed reference-image video route backed by the customer media SSE API. */
@Component
public class FastVideoGenerationProvider extends ProviderJsonSupport implements VideoGenerationProvider {
    private static final int MAX_REFERENCE_IMAGES = 4;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public FastVideoGenerationProvider(ObjectMapper objectMapper) { super(objectMapper); }

    @Override public String code() { return "FAST"; }
    @Override public String effectiveResolution(String requestedResolution) { return "厂商默认"; }

    @Override
    public SubmitResult submit(RuntimeModelConnection connection, GenerationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", connection.modelId());
        body.put("prompt", request.prompt());
        body.put("duration", request.durationSeconds());

        List<String> imageUrls = request.references().stream()
                .filter(reference -> reference.mediaType() != null && reference.mediaType().startsWith("image/"))
                .map(Reference::url)
                .filter(this::isPublicUrl)
                .limit(MAX_REFERENCE_IMAGES)
                .toList();
        if (!imageUrls.isEmpty()) body.put("image_urls", imageUrls);
        if (request.audioUrl() != null && isPublicUrl(request.audioUrl())) {
            body.put("audio_reference", request.audioUrl());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("aspect_ratio", request.aspectRatio());
        body.put("metadata", metadata);

        String endpoint = apiRoot(connection.baseUrl()) + "/cpa-file/stream/video";
        String requestJson = json(body);
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + connection.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String responseBody;
                try (InputStream input = response.body()) {
                    responseBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new VideoProviderException("高速线路拒绝请求（" + response.statusCode() + "）：" + responseMessage(responseBody),
                        true, requestJson, rawJson(responseBody));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isBlank() || "[DONE]".equals(payload)) continue;
                    Map<String, Object> event = objectMapper.readValue(payload, new TypeReference<>() {});
                    String taskId = text(event, "task_id");
                    String type = text(event, "type").toLowerCase();
                    if ("failed".equals(type)) {
                        String reason = text(event, "fail_reason");
                        throw new VideoProviderException(reason.isBlank() ? "高速线路生成请求失败" : reason,
                                true, requestJson, json(event));
                    }
                    if (!taskId.isBlank()) {
                        return new SubmitResult(taskId, "QUEUED", requestJson, json(event));
                    }
                }
            }
            throw new VideoProviderException("高速线路没有返回视频任务 ID", true, requestJson, "{}");
        } catch (VideoProviderException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VideoProviderException("高速线路提交被中断，未取得任务 ID", true, requestJson, "{}");
        } catch (Exception exception) {
            throw new VideoProviderException("高速线路未返回任务 ID：" + rootMessage(exception), true, requestJson, "{}");
        }
    }

    @Override
    public QueryResult query(RuntimeModelConnection connection, String providerTaskId) {
        Map<String, Object> request = Map.of("ids", List.of(providerTaskId));
        String requestJson = json(request);
        Map<String, Object> response;
        try {
            response = post(apiRoot(connection.baseUrl()) + "/cpa-file/fetch", connection.apiKey(), request);
        } catch (VideoProviderException exception) {
            throw exception.withRequest(requestJson);
        }
        String responseJson = json(response);
        List<Map<String, Object>> rows = maps(response.get("data"));
        if (rows.isEmpty()) return new QueryResult("RUNNING", "", null, Map.of(), "", requestJson, responseJson);
        Map<String, Object> row = rows.getFirst();
        String status = text(row, "status").toUpperCase();
        if (List.of("SUBMITTED", "QUEUED", "IN_PROGRESS", "RUNNING").contains(status)) {
            return new QueryResult("RUNNING", "", null, Map.of("progress", text(row, "progress")), "", requestJson, responseJson);
        }
        if (List.of("FAILURE", "FAILED", "CANCELLED").contains(status)) {
            String reason = text(row, "fail_reason");
            if (reason.isBlank()) reason = text(row, "message");
            return new QueryResult("FAILED", "", null, Map.of(), reason, requestJson, responseJson);
        }
        Map<String, Object> resultData = map(row, "data");
        List<Map<String, Object>> items = maps(resultData.get("items"));
        Map<String, Object> item = items.isEmpty() ? Map.of() : items.getFirst();
        List<Map<String, Object>> outputs = maps(item.get("data"));
        String outputUrl = outputs.isEmpty() ? "" : text(outputs.getFirst(), "url");
        BigDecimal cost = decimal(item.get("credits_used"));
        if ("SUCCESS".equals(status) && !outputUrl.isBlank()) {
            return new QueryResult("SUCCEEDED", absoluteUrl(connection.baseUrl(), outputUrl), cost,
                    Map.of("credits_used", cost == null ? 0 : cost), "", requestJson, responseJson);
        }
        return new QueryResult("RUNNING", "", null, Map.of("progress", text(row, "progress")), "", requestJson, responseJson);
    }

    private boolean isPublicUrl(String value) {
        return value != null && (value.startsWith("https://") || value.startsWith("http://"));
    }

    private String apiRoot(String baseUrl) {
        return baseUrl.replaceAll("/+$", "").replaceFirst("/v1$", "");
    }

    private String absoluteUrl(String baseUrl, String value) {
        if (isPublicUrl(value)) return value;
        return apiRoot(baseUrl) + (value.startsWith("/") ? value : "/" + value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        return result;
    }

    private BigDecimal decimal(Object value) {
        try { return value == null ? null : new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private String responseMessage(String body) {
        if (body == null || body.isBlank()) return "无响应内容";
        try {
            Map<String, Object> value = objectMapper.readValue(body, new TypeReference<>() {});
            String message = text(value, "message");
            if (message.isBlank()) message = text(value, "detail");
            return message.isBlank() ? "请求失败" : message;
        } catch (Exception ignored) {
            return body.substring(0, Math.min(200, body.length()));
        }
    }

    private String rootMessage(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
