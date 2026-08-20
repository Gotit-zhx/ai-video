package com.yingdao.aicommerce.video.application.provider;

import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DayanVideoGenerationProvider extends ProviderJsonSupport implements VideoGenerationProvider {
    public DayanVideoGenerationProvider(ObjectMapper objectMapper) { super(objectMapper); }
    @Override public String code() { return "DAYAN"; }
    @Override public boolean supportsReferenceVideo(RuntimeModelConnection connection) { return true; }

    @Override
    public SubmitResult submit(RuntimeModelConnection connection, GenerationRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", request.prompt()));
        request.references().forEach(reference -> {
            boolean video = reference.mediaType() != null && reference.mediaType().startsWith("video/");
            content.add(Map.of(
                    "type", video ? "video_url" : "image_url",
                    video ? "video_url" : "image_url",
                    reference.url()
            ));
        });
        if (request.audioUrl() != null && !request.audioUrl().isBlank()) {
            content.add(Map.of("type", "audio_url", "audio_url", request.audioUrl()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", connection.modelId());
        body.put("content", content);
        body.put("duration", request.durationSeconds());
        body.put("ratio", request.aspectRatio());
        body.put("resolution", request.resolution());
        String requestJson = json(body);
        Map<String, Object> response;
        try {
            response = post(tasksEndpoint(connection.baseUrl()), connection.apiKey(), body);
        } catch (VideoProviderException exception) {
            throw exception.withRequest(requestJson);
        }
        String id = text(response, "id");
        if (id.isBlank()) throw new VideoProviderException("大衍没有返回视频任务 ID", false,
                requestJson, json(response));
        return new SubmitResult(id, normalize(text(response, "status")), requestJson, json(response));
    }

    @Override
    public QueryResult query(RuntimeModelConnection connection, String providerTaskId) {
        String endpoint = tasksEndpoint(connection.baseUrl()) + "/" + providerTaskId;
        String requestJson = json(Map.of("method", "GET", "url", endpoint));
        Map<String, Object> response;
        try {
            response = get(endpoint, connection.apiKey());
        } catch (VideoProviderException exception) {
            throw exception.withRequest(requestJson);
        }
        Map<String, Object> content = map(response, "content");
        String error = text(response, "error_message");
        if (error.isBlank()) error = text(response, "message");
        return new QueryResult(normalize(text(response, "status")), text(content, "video_url"), decimal(response.get("cost")),
                map(response, "usage"), error, requestJson, json(response));
    }

    private String normalize(String status) {
        return switch (status == null ? "" : status.trim().toLowerCase()) {
            case "succeeded" -> "SUCCEEDED";
            case "failed" -> "FAILED";
            case "created", "queued" -> "QUEUED";
            default -> "RUNNING";
        };
    }

    private BigDecimal decimal(Object value) {
        try { return value == null ? null : new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private String tasksEndpoint(String baseUrl) {
        String normalized = baseUrl.replaceAll("/+$", "");
        return normalized.endsWith("/contents/generations/tasks")
                ? normalized : endpoint(normalized, "/api/v3", "/contents/generations/tasks");
    }
}
