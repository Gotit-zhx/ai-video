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
public class WengaoVideoGenerationProvider extends ProviderJsonSupport implements VideoGenerationProvider {
    public WengaoVideoGenerationProvider(ObjectMapper objectMapper) { super(objectMapper); }
    @Override public String code() { return "WENGAO"; }

    @Override
    public SubmitResult submit(RuntimeModelConnection connection, GenerationRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", request.prompt()));
        request.references().forEach(reference -> content.add(Map.of(
                "type", "image_url", "image_url", Map.of("url", reference.url()), "role", "reference_image")));
        if (request.audioUrl() != null && !request.audioUrl().isBlank()) {
            content.add(Map.of("type", "audio_url", "audio_url", Map.of("url", request.audioUrl()), "role", "reference_audio"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", connection.modelId());
        body.put("content", content);
        body.put("duration", Math.max(4, Math.min(15, request.durationSeconds())));
        body.put("ratio", request.aspectRatio());
        body.put("resolution", "768P");
        Map<String, Object> response = post(tasksEndpoint(connection.baseUrl()), connection.apiKey(), body);
        String id = text(response, "id");
        if (id.isBlank()) throw new VideoProviderException("文高云没有返回视频任务 ID", false);
        return new SubmitResult(id, normalize(text(response, "status")));
    }

    @Override
    public QueryResult query(RuntimeModelConnection connection, String providerTaskId) {
        Map<String, Object> response = get(tasksEndpoint(connection.baseUrl()) + "/" + providerTaskId, connection.apiKey());
        Map<String, Object> content = map(response, "content");
        String error = text(response, "error_message");
        if (error.isBlank()) error = text(response, "message");
        return new QueryResult(normalize(text(response, "status")), text(content, "video_url"), decimal(response.get("cost")), map(response, "usage"), error);
    }

    private String normalize(String status) {
        return switch (status == null ? "" : status.trim().toLowerCase()) {
            case "succeeded" -> "SUCCEEDED";
            case "failed", "cancelled", "expired" -> "FAILED";
            case "queued" -> "QUEUED";
            default -> "RUNNING";
        };
    }

    private BigDecimal decimal(Object value) {
        try { return value == null ? null : new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private String tasksEndpoint(String baseUrl) {
        String normalized = baseUrl.replaceAll("/+$", "");
        return normalized.endsWith("/videos/generations")
                ? normalized : endpoint(normalized, "/v1", "/videos/generations");
    }
}
