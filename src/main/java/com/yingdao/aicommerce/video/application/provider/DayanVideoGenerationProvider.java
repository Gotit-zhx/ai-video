package com.yingdao.aicommerce.video.application.provider;

import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DayanVideoGenerationProvider extends ProviderJsonSupport implements VideoGenerationProvider {
    public static final String MODEL = "doubao-seedance-2-0-mini-260615";
    public DayanVideoGenerationProvider(ObjectMapper objectMapper) { super(objectMapper); }
    @Override public String code() { return "DAYAN"; }
    @Override public boolean supportsReferenceVideo(RuntimeModelConnection connection) { return true; }
    @Override public String effectiveResolution(String requestedResolution) { return "720p"; }

    @Override
    public SubmitResult submit(RuntimeModelConnection connection, GenerationRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", request.prompt()));
        request.references().forEach(reference -> {
            String media = reference.mediaType() == null ? "" : reference.mediaType();
            String type = media.startsWith("video/") ? "video" : media.startsWith("audio/") ? "audio" : "image";
            content.add(Map.of(
                    "type", type + "_url",
                    "role", "reference_" + type,
                    type + "_url", Map.of("url", reference.url())
            ));
        });
        if (request.audioUrl() != null && !request.audioUrl().isBlank()) {
            content.add(Map.of("type", "audio_url", "role", "reference_audio",
                    "audio_url", Map.of("url", request.audioUrl())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("content", content);
        // The workspace's Seedance contract uses automatic duration/framing for every video entry point.
        body.put("duration", -1);
        body.put("ratio", "adaptive");
        body.put("resolution", "720p");
        body.put("generate_audio", false);
        body.put("watermark", false);
        body.put("return_last_frame", true);
        String requestJson = json(body);
        Map<String, Object> response;
        try {
            response = post(tasksEndpoint(connection.baseUrl()), connection.apiKey(), body,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
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
        if (error.isBlank()) error = text(map(response, "error"), "message");
        if (error.isBlank()) error = text(response, "message");
        String status = normalize(text(response, "status"));
        return new QueryResult(status, text(content, "video_url"),
                "SUCCEEDED".equals(status) ? decimal(response.get("cost")) : null,
                map(response, "usage"), error, requestJson, json(response));
    }

    private String normalize(String status) {
        return switch (status == null ? "" : status.trim().toLowerCase()) {
            case "succeeded" -> "SUCCEEDED";
            case "failed", "cancelled", "canceled", "expired" -> "FAILED";
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
