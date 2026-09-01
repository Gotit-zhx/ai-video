package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Runs the two manual-script AI entry points outside the browser request lifecycle.
 * A model call can legitimately take several minutes; Redis keeps the result available
 * even after the original tab's short polling requests have disconnected.
 */
@Service
public class ManualScriptAnalysisJobService {
    private static final String KEY_PREFIX = "heyi:video-script:manual-analysis:";
    private static final Duration JOB_TTL = Duration.ofHours(24);
    private static final int MAX_IMAGE_COUNT = 6;
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_BYTES = 60L * 1024 * 1024;

    private final ManualScriptAiService analysisService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public ManualScriptAnalysisJobService(
            ManualScriptAiService analysisService,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${app.manual-script-analysis-concurrency:4}") int concurrency
    ) {
        this.analysisService = analysisService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        int poolSize = Math.max(1, Math.min(concurrency, 8));
        ThreadFactory threadFactory = Thread.ofPlatform().name("manual-script-analysis-", 0).factory();
        this.executor = Executors.newFixedThreadPool(poolSize, threadFactory);
    }

    public JobView startPrompt(String prompt, String targetMarket, String existingName,
                               String existingVisualTone, String presentationMode,
                               PlatformPrincipal principal) {
        return start(principal, "BUSINESS_PROMPT_TO_SCRIPT", () -> analysisService.analyze(
                prompt, targetMarket, existingName, existingVisualTone, presentationMode, principal));
    }

    public JobView startProduct(List<MultipartFile> images, List<String> imageContexts,
                                String requirements, String productContext, String targetMarket,
                                String existingName, String existingVisualTone, String presentationMode,
                                PlatformPrincipal principal) {
        List<MultipartFile> snapshots = snapshotImages(images);
        List<String> contextSnapshots = imageContexts == null ? List.of() : List.copyOf(imageContexts);
        return start(principal, "PRODUCT_IMAGES_TO_SCRIPT", () -> analysisService.analyzeProduct(
                snapshots, contextSnapshots, requirements, productContext, targetMarket,
                existingName, existingVisualTone, presentationMode, principal));
    }

    public JobView get(String jobId, PlatformPrincipal principal) {
        String normalizedId = jobId == null ? "" : jobId.trim();
        Map<Object, Object> values = redis.opsForHash().entries(key(normalizedId));
        if (values.isEmpty()
                || !principal.organizationId().equals(string(values.get("organizationId")))
                || !principal.userId().equals(string(values.get("userId")))) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "AI 分析任务不存在或已过期");
        }
        ManualScriptAiService.Draft result = null;
        String resultJson = string(values.get("result"));
        if (!resultJson.isBlank()) {
            try {
                result = objectMapper.readValue(resultJson, ManualScriptAiService.Draft.class);
            } catch (Exception exception) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "AI 分析结果暂时无法读取");
            }
        }
        return new JobView(normalizedId, string(values.get("status")), string(values.get("message")),
                string(values.get("mode")), result, string(values.get("createdAt")),
                string(values.get("updatedAt")));
    }

    private JobView start(PlatformPrincipal principal, String mode, AnalysisWork work) {
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        save(jobId, Map.of(
                "organizationId", principal.organizationId(),
                "userId", principal.userId(),
                "mode", mode,
                "status", "QUEUED",
                "message", "分析任务已进入后台队列",
                "createdAt", now.toString(),
                "updatedAt", now.toString()
        ));
        try {
            executor.execute(() -> execute(jobId, work));
        } catch (RuntimeException exception) {
            update(jobId, "FAILED", "分析队列繁忙，请稍后重试", null);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "分析队列繁忙，请稍后重试");
        }
        return get(jobId, principal);
    }

    private void execute(String jobId, AnalysisWork work) {
        update(jobId, "RUNNING", "AI 正在理解需求并编排 9 个分镜", null);
        try {
            ManualScriptAiService.Draft result = work.run();
            update(jobId, "SUCCEEDED", "9 个分镜分析完成", objectMapper.writeValueAsString(result));
        } catch (Exception exception) {
            update(jobId, "FAILED", readable(exception), null);
        }
    }

    private void update(String jobId, String status, String message, String resultJson) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("status", status);
        values.put("message", message == null ? "" : message);
        values.put("updatedAt", Instant.now().toString());
        if (resultJson != null) values.put("result", resultJson);
        save(jobId, values);
    }

    private void save(String jobId, Map<String, String> values) {
        String key = key(jobId);
        redis.opsForHash().putAll(key, values);
        redis.expire(key, JOB_TTL);
    }

    private List<MultipartFile> snapshotImages(List<MultipartFile> images) {
        if (images == null || images.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请先添加至少一张参考图片");
        }
        if (images.size() > MAX_IMAGE_COUNT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "参考图片最多支持 6 张");
        }
        long totalBytes = 0;
        java.util.ArrayList<MultipartFile> snapshots = new java.util.ArrayList<>(images.size());
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty() || image.getSize() > MAX_IMAGE_BYTES) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "参考图片为空或超过单张 20MB 限制");
            }
            totalBytes += image.getSize();
            if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "所有参考图合计不能超过 60MB");
            }
            try {
                snapshots.add(new MemoryMultipartFile(image.getName(), image.getOriginalFilename(),
                        image.getContentType(), image.getBytes()));
            } catch (IOException exception) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "参考图片读取失败");
            }
        }
        return List.copyOf(snapshots);
    }

    private String readable(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) return "AI 分析失败，请稍后重试";
        return message.replaceFirst("^AI 分析失败：", "");
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String key(String jobId) {
        return KEY_PREFIX + jobId;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    private interface AnalysisWork {
        ManualScriptAiService.Draft run();
    }

    public record JobView(String id, String status, String message, String mode,
                          ManualScriptAiService.Draft result, String createdAt, String updatedAt) {}

    private record MemoryMultipartFile(String name, String originalFilename, String contentType,
                                       byte[] bytes) implements MultipartFile {
        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) throws IOException {
            Files.write(destination.toPath(), bytes);
        }
    }
}
