package com.yingdao.aicommerce.video.application.provider;

import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class H3MultimodalVideoGenerationProvider extends ProviderJsonSupport implements VideoGenerationProvider {
    private static final long MAX_REFERENCE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_TOTAL_REFERENCE_BYTES = 384L * 1024 * 1024;
    private static final String PROVIDER_RESOLUTION = "768P";
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public H3MultimodalVideoGenerationProvider(ObjectMapper objectMapper) { super(objectMapper); }

    @Override public String code() { return "H3_MULTI"; }
    @Override public boolean supportsReferenceVideo(RuntimeModelConnection connection) { return true; }
    @Override public boolean requiresAuthenticatedDownload() { return true; }
    @Override public String effectiveResolution(String requestedResolution) {
        return is720pTier(requestedResolution) ? PROVIDER_RESOLUTION : requestedResolution;
    }

    @Override
    public SubmitResult submit(RuntimeModelConnection connection, GenerationRequest request) {
        String resolution = providerResolution(request.resolution());
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("prompt", request.prompt());
        multipart.part("mode", "ref2va");
        multipart.part("resolution", resolution);
        multipart.part("duration", String.valueOf(request.durationSeconds()));
        multipart.part("steps", "10");
        multipart.part("aspectRatio", request.aspectRatio());

        List<Map<String, Object>> fileManifest = new ArrayList<>();
        long totalBytes = 0;
        int imageIndex = 0;
        int videoIndex = 0;
        try {
            for (Reference reference : request.references()) {
                LoadedReference loaded = load(reference.url(), reference.mediaType());
                totalBytes += loaded.bytes().length;
                if (totalBytes > MAX_TOTAL_REFERENCE_BYTES) {
                    throw new VideoProviderException("多模态参考素材合计超过 384MB", true);
                }
                boolean video = loaded.mediaType().startsWith("video/");
                String field = video ? "referenceVideos" : "referenceImages";
                String filename = (video ? "reference-video-" + (++videoIndex) : "reference-image-" + (++imageIndex))
                        + extension(loaded.mediaType());
                multipart.part(field, new NamedBytes(loaded.bytes(), filename))
                        .contentType(MediaType.parseMediaType(loaded.mediaType()));
                fileManifest.add(Map.of("field", field, "filename", filename,
                        "contentType", loaded.mediaType(), "sizeBytes", loaded.bytes().length,
                        "sourceUrl", safeSource(reference.url())));
            }
            if (request.audioUrl() != null && !request.audioUrl().isBlank()) {
                LoadedReference audio = load(request.audioUrl(), "audio/mpeg");
                totalBytes += audio.bytes().length;
                if (totalBytes > MAX_TOTAL_REFERENCE_BYTES) {
                    throw new VideoProviderException("多模态参考素材合计超过 384MB", true);
                }
                String filename = "reference-audio" + extension(audio.mediaType());
                multipart.part("referenceAudios", new NamedBytes(audio.bytes(), filename))
                        .contentType(MediaType.parseMediaType(audio.mediaType()));
                fileManifest.add(Map.of("field", "referenceAudios", "filename", filename,
                        "contentType", audio.mediaType(), "sizeBytes", audio.bytes().length,
                        "sourceUrl", safeSource(request.audioUrl())));
            }
        } catch (VideoProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VideoProviderException("读取多模态参考素材失败：" + rootMessage(exception), true);
        }

        Map<String, Object> requestManifest = new LinkedHashMap<>();
        requestManifest.put("prompt", request.prompt());
        requestManifest.put("mode", "ref2va");
        requestManifest.put("resolution", resolution);
        requestManifest.put("duration", request.durationSeconds());
        requestManifest.put("steps", 10);
        requestManifest.put("aspectRatio", request.aspectRatio());
        requestManifest.put("files", fileManifest);
        String requestJson = json(requestManifest);
        try {
            String raw = RestClient.create().post().uri(tasksEndpoint(connection.baseUrl()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + connection.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart.build()).retrieve().body(String.class);
            Map<String, Object> response = parse(raw);
            Map<String, Object> job = map(response, "job");
            Map<String, Object> data = map(response, "data");
            Map<String, Object> dataJob = map(data, "job");
            String id = firstNonBlank(
                    firstText(response, "id", "jobId", "job_id", "taskId", "task_id"),
                    firstText(job, "id", "jobId", "job_id", "taskId", "task_id"),
                    firstText(data, "id", "jobId", "job_id", "taskId", "task_id"),
                    firstText(dataJob, "id", "jobId", "job_id", "taskId", "task_id"));
            if (id.isBlank()) throw new VideoProviderException("多模态高速线路没有返回任务 ID", true,
                    requestJson, rawJson(raw));
            String status = firstNonBlank(firstText(response, "status", "state"),
                    firstText(job, "status", "state", "internalStatus", "internal_status"),
                    firstText(data, "status", "state"),
                    firstText(dataJob, "status", "state", "internalStatus", "internal_status"));
            return new SubmitResult(id, normalize(status), requestJson, rawJson(raw));
        } catch (RestClientResponseException exception) {
            throw new VideoProviderException("多模态高速线路拒绝请求（" + exception.getStatusCode().value() + "）",
                    true, requestJson, rawJson(exception.getResponseBodyAsString()));
        } catch (ResourceAccessException exception) {
            throw new VideoProviderException("多模态高速线路连接中断：" + rootMessage(exception),
                    false, requestJson, "{}");
        }
    }

    @Override
    public QueryResult query(RuntimeModelConnection connection, String providerTaskId) {
        String endpoint = tasksEndpoint(connection.baseUrl()) + "/" + providerTaskId;
        String requestJson = json(Map.of("method", "GET", "url", endpoint));
        try {
            String raw = RestClient.create().get().uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + connection.apiKey())
                    .retrieve().body(String.class);
            Map<String, Object> response = parse(raw);
            Map<String, Object> job = map(response, "job");
            Map<String, Object> data = map(response, "data");
            Map<String, Object> dataJob = map(data, "job");
            String status = normalize(firstNonBlank(firstText(response, "status", "state"),
                    firstText(job, "status", "state", "internalStatus", "internal_status"),
                    firstText(data, "status", "state"),
                    firstText(dataJob, "status", "state", "internalStatus", "internal_status")));
            String error = firstNonBlank(firstText(response, "error", "message", "failReason", "fail_reason"),
                    firstText(job, "error", "message", "failReason", "fail_reason"),
                    firstText(data, "error", "message", "failReason", "fail_reason"),
                    firstText(dataJob, "error", "message", "failReason", "fail_reason"));
            String outputUrl = "SUCCEEDED".equals(status)
                    ? tasksEndpoint(connection.baseUrl()) + "/" + providerTaskId + "/content" : "";
            Object cost = firstValue(response, job, data, dataJob, "cost");
            Map<String, Object> usage = new LinkedHashMap<>(firstMap(response, job, data, dataJob, "usage"));
            Object progress = firstValue(response, job, data, dataJob, "progress");
            if (progress != null) usage.put("progress", progress);
            return new QueryResult(status, outputUrl, decimal(cost), usage,
                    error, requestJson, rawJson(raw));
        } catch (RestClientResponseException exception) {
            throw new VideoProviderException("查询多模态高速线路失败（" + exception.getStatusCode().value() + "）",
                    false, requestJson, rawJson(exception.getResponseBodyAsString()));
        } catch (ResourceAccessException exception) {
            throw new VideoProviderException("查询多模态高速线路连接中断：" + rootMessage(exception),
                    false, requestJson, "{}");
        }
    }

    private LoadedReference load(String value, String fallbackType) throws Exception {
        if (value != null && value.startsWith("data:")) {
            int comma = value.indexOf(',');
            String header = comma > 5 ? value.substring(5, comma) : "";
            String mediaType = header.split(";", 2)[0];
            byte[] bytes = Base64.getDecoder().decode(value.substring(comma + 1));
            validateSize(bytes.length);
            return new LoadedReference(bytes, mediaType.isBlank() ? fallbackType : mediaType);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(value)).timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        validateSize(response.body().length);
        String mediaType = response.headers().firstValue("content-type").orElse(fallbackType).split(";", 2)[0];
        return new LoadedReference(response.body(), mediaType);
    }

    private void validateSize(long size) {
        if (size <= 0) throw new VideoProviderException("多模态参考素材为空", true);
        if (size > MAX_REFERENCE_BYTES) throw new VideoProviderException("单个多模态参考素材超过 128MB", true);
    }

    private Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() {}); }
        catch (Exception exception) { throw new VideoProviderException("多模态高速线路返回了无法解析的数据", false, null, rawJson(raw)); }
    }

    private String firstText(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            String result = text(value, key);
            if (!result.isBlank()) return result;
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private Object firstValue(Map<String, Object> first, Map<String, Object> second,
                              Map<String, Object> third, Map<String, Object> fourth, String key) {
        for (Map<String, Object> source : List.of(first, second, third, fourth)) {
            Object value = source.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private Map<String, Object> firstMap(Map<String, Object> first, Map<String, Object> second,
                                         Map<String, Object> third, Map<String, Object> fourth, String key) {
        for (Map<String, Object> source : List.of(first, second, third, fourth)) {
            Map<String, Object> value = map(source, key);
            if (!value.isEmpty()) return value;
        }
        return Map.of();
    }

    private String normalize(String status) {
        return switch (status == null ? "" : status.trim().toLowerCase()) {
            case "success", "succeeded", "completed", "complete", "done" -> "SUCCEEDED";
            case "failure", "failed", "error", "cancelled", "canceled" -> "FAILED";
            case "queued", "created", "submitted", "pending" -> "QUEUED";
            default -> "RUNNING";
        };
    }

    private String tasksEndpoint(String baseUrl) {
        String normalized = baseUrl.replaceAll("/+$", "");
        return normalized.endsWith("/api/videos") ? normalized : normalized + "/api/videos";
    }

    private String providerResolution(String requestedResolution) {
        if (is720pTier(requestedResolution)) return PROVIDER_RESOLUTION;
        throw new VideoProviderException(
                "多模态高速线路固定输出 768P，不支持请求的分辨率 " + requestedResolution,
                true);
    }

    private boolean is720pTier(String resolution) {
        return "720p".equalsIgnoreCase(resolution == null ? "" : resolution.trim())
                || PROVIDER_RESOLUTION.equalsIgnoreCase(resolution == null ? "" : resolution.trim());
    }

    private String extension(String mediaType) {
        if (mediaType.contains("png")) return ".png";
        if (mediaType.contains("webp")) return ".webp";
        if (mediaType.contains("jpeg") || mediaType.contains("jpg")) return ".jpg";
        if (mediaType.contains("wav")) return ".wav";
        if (mediaType.contains("mpeg") || mediaType.contains("mp3")) return ".mp3";
        if (mediaType.contains("webm")) return ".webm";
        return mediaType.startsWith("video/") ? ".mp4" : ".bin";
    }

    private String safeSource(String value) {
        return value != null && value.startsWith("data:") ? "inline-data" : String.valueOf(value);
    }

    private BigDecimal decimal(Object value) {
        try { return value == null ? null : new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private String rootMessage(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record LoadedReference(byte[] bytes, String mediaType) {}

    private static final class NamedBytes extends ByteArrayResource {
        private final String filename;
        private NamedBytes(byte[] bytes, String filename) { super(bytes); this.filename = filename; }
        @Override public String getFilename() { return filename; }
    }
}
