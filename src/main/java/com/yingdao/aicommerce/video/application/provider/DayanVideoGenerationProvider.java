package com.yingdao.aicommerce.video.application.provider;

import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DayanVideoGenerationProvider extends ProviderJsonSupport implements VideoGenerationProvider {
    public static final String MODEL = "doubao-seedance-2-0-mini-260615";
    private final HttpClient catalogClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, ModelCatalog> catalogs = new ConcurrentHashMap<>();

    public DayanVideoGenerationProvider(ObjectMapper objectMapper) { super(objectMapper); }
    @Override public String code() { return "DAYAN"; }
    @Override public boolean supportsReferenceVideo(RuntimeModelConnection connection) { return true; }
    @Override public String effectiveResolution(String requestedResolution) { return "720p"; }

    @Override
    public Set<String> availableModels(RuntimeModelConnection connection) {
        String identity = catalogIdentity(connection);
        return catalogs.compute(connection.id(), (id, cached) -> {
            Instant now = Instant.now();
            boolean sameCredentials = cached != null && cached.identity().equals(identity);
            if (sameCredentials && cached.expiresAt().isAfter(now)) return cached;
            var available = fetchModels(connection);
            Set<String> fallback = sameCredentials ? cached.models() : Set.of(connection.modelId());
            return new ModelCatalog(identity, available.orElse(fallback), now.plusSeconds(available.isPresent() ? 300 : 30));
        }).models();
    }

    private Optional<Set<String>> fetchModels(RuntimeModelConnection connection) {
        String base = connection.baseUrl().replaceAll("/+$", "")
                .replaceFirst("(?:/api/v3(?:/contents/generations/tasks)?|/v1)$", "");
        try {
            var request = HttpRequest.newBuilder(URI.create(base + "/v1/models"))
                    .header("Authorization", "Bearer " + connection.apiKey())
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = catalogClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 401 || response.statusCode() == 403) return Optional.of(Set.of());
            if (response.statusCode() != 200 || response.body().length > 1024 * 1024) return Optional.empty();
            var data = objectMapper.readTree(response.body()).path("data");
            if (!data.isArray()) return Optional.empty();
            Set<String> ids = new HashSet<>();
            for (var entry : data) {
                String id = entry.path("id").asText();
                if (!id.isBlank()) ids.add(id);
            }
            return Optional.of(Set.copyOf(ids));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (java.io.IOException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private String catalogIdentity(RuntimeModelConnection connection) {
        try {
            byte[] key = connection.apiKey().getBytes(StandardCharsets.UTF_8);
            return connection.baseUrl() + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ModelCatalog(String identity, Set<String> models, Instant expiresAt) {}

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
        body.put("model", request.useRequestedOptions() ? connection.modelId() : MODEL);
        body.put("content", content);
        body.put("duration", request.useRequestedOptions() ? request.durationSeconds() : -1);
        body.put("ratio", request.useRequestedOptions() ? request.aspectRatio() : "adaptive");
        body.put("resolution", request.useRequestedOptions() ? request.resolution() : "720p");
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
