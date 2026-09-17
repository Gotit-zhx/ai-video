package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

/** A failed or uncertain review retains its evidence and can never authorize a paid redraw. */
@Service
public class VideoFirstFrameAuditService {
    private static final Set<String> LAYOUTS = Set.of("SINGLE_SCENE", "GRID", "SPLIT_SCREEN", "COLLAGE", "UNKNOWN");
    private static final long MAX_VIDEO_BYTES = 512L * 1024 * 1024;
    private static final long MAX_FRAME_BYTES = 4L * 1024 * 1024;
    private static final Object[] LOCKS = new Object[64];
    static { for (int index = 0; index < LOCKS.length; index++) LOCKS[index] = new Object(); }

    private final ModelConfigService models;
    private final OssStorageService oss;
    private final ObjectMapper json;
    private final RestClient runtime;
    private final Path storageRoot;
    private final FrameExtractor extractor;

    @Autowired
    public VideoFirstFrameAuditService(ModelConfigService models, ObjectProvider<OssStorageService> oss,
                                      ObjectMapper json,
                                      @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storageRoot,
                                      @Value("${app.pi-runtime-url}") String runtimeUrl,
                                      @Value("${app.pi-runtime-token}") String runtimeToken) {
        this(models, oss.getIfAvailable(), json, client(runtimeUrl, runtimeToken), Path.of(storageRoot),
                VideoFirstFrameAuditService::extractWithFfmpeg);
    }

    VideoFirstFrameAuditService(ModelConfigService models, OssStorageService oss, ObjectMapper json,
                               RestClient runtime, Path storageRoot, FrameExtractor extractor) {
        this.models = models;
        this.oss = oss;
        this.json = json;
        this.runtime = runtime;
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.extractor = extractor;
    }

    private static RestClient client(String url, String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(210_000);
        return RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader("X-Internal-Token", token).build();
    }

    public AuditResult audit(AiVideoTask task, AiVideoTaskItem item, String savedVideoLocation, String reviewKey) {
        if (task == null || item == null || !safeId(reviewKey)
                || !safeId(task.getOrganizationId()) || !safeId(task.getId()) || !safeId(item.getId())
                || !task.getId().equals(item.getTaskId()) || !task.getOrganizationId().equals(item.getOrganizationId())
                || savedVideoLocation == null || savedVideoLocation.isBlank()) {
            return unknown("首帧审核参数或任务归属无效，已暂停", null);
        }
        synchronized (LOCKS[Math.floorMod((task.getId() + item.getId() + reviewKey).hashCode(), LOCKS.length)]) {
            return auditLocked(task, item, savedVideoLocation, reviewKey);
        }
    }

    private AuditResult auditLocked(AiVideoTask task, AiVideoTaskItem item, String location, String key) {
        Path frame = null;
        try {
            Path directory = reviewDirectory(task, item, key);
            Path identity = directory.resolve("source-location.sha256");
            String locationHash = hash(location.getBytes(StandardCharsets.UTF_8));
            if (Files.exists(identity) && !Files.readString(identity).equals(locationHash)) {
                return unknown("同一审核编号的视频发生变化，已暂停并保留旧审核证据", null);
            }
            if (!Files.exists(identity)) writePrivate(identity, locationHash.getBytes(StandardCharsets.UTF_8));
            Path candidate = directory.resolve("first-frame.jpg");
            if (!Files.exists(candidate)) extractFrame(location, directory, candidate);
            validateFrame(candidate);
            frame = candidate;

            Path resultFile = directory.resolve("audit-result.json");
            if (Files.exists(resultFile)) {
                Map<?, ?> cached = json.readValue(Files.readString(resultFile), Map.class);
                if (!"VIDEO_FRAME_LAYOUT_V1".equals(cached.get("version"))
                        || !hash(Files.readAllBytes(frame)).equals(cached.get("frameSha256"))) {
                    return unknown("首帧审核缓存与截图不一致，已暂停", frame);
                }
                return parseResult(cached.get("result"), frame);
            }

            AuditResult result;
            try {
                Path started = directory.resolve("audit-request-started");
                var model = Files.exists(started) ? null : models.driverConnection(task.getOrganizationId()).orElse(null);
                if (Files.exists(started)) {
                    result = unknown("上次首帧审核可能已受理但未保存结果，已暂停，避免重复调用模型", frame);
                } else if (model == null) result = unknown("没有可用的驱动模型，首帧审核已暂停", frame);
                else {
                    // Persist the network boundary before sending a potentially chargeable request.
                    writePrivate(started, "SENDING".getBytes(StandardCharsets.US_ASCII));
                    Object response = runtime.post().uri("/v1/video/frame-audit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("model", model.driverRequestModel(),
                                    "image", Map.of("mimeType", "image/jpeg", "data",
                                            Base64.getEncoder().encodeToString(Files.readAllBytes(frame)))))
                            .retrieve().body(Map.class);
                    result = parseResult(response, frame);
                }
            } catch (Exception exception) {
                String code = PiRuntimeFailure.from(exception).failureCode();
                if (code == null || !code.matches("[A-Z0-9_]{1,80}")) code = "AUDIT_REQUEST_FAILED";
                result = unknown("首帧审核接口失败 [" + code + "]，已保留视频和截图并暂停", frame);
            }
            // Also checkpoint UNKNOWN: restarting this job must not silently repeat a paid audit.
            writePrivate(resultFile, json.writeValueAsBytes(Map.of("version", "VIDEO_FRAME_LAYOUT_V1",
                    "frameSha256", hash(Files.readAllBytes(frame)), "result", Map.of("layout", result.layout(),
                            "confidence", result.confidence(), "reason", result.reason()))));
            return result;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return unknown(frame == null ? "视频首帧提取失败或没有可解码画面，已暂停"
                    : "首帧审核证据保存或读取失败，已保留截图并暂停", frame);
        }
    }

    private Path reviewDirectory(AiVideoTask task, AiVideoTaskItem item, String key) throws Exception {
        Files.createDirectories(storageRoot);
        Path directory = storageRoot.toRealPath();
        for (String segment : List.of(task.getOrganizationId(), task.getId(), item.getId(), "private-video-review", key)) {
            directory = directory.resolve(segment);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("审核目录无效");
            if (segment.equals("private-video-review") || segment.equals(key)) privatePermissions(directory, true);
        }
        return directory;
    }

    private void extractFrame(String location, Path directory, Path frame) throws Exception {
        Path downloaded = null;
        Path output = directory.resolve("first-frame-" + UUID.randomUUID() + ".jpg");
        try {
            Path input;
            if (oss != null && oss.isOssLocation(location)) {
                downloaded = directory.resolve("video-" + UUID.randomUUID() + ".mp4");
                downloadVideo(location, downloaded);
                input = downloaded;
            } else {
                input = Path.of(location).toAbsolutePath().normalize().toRealPath();
                if (!input.startsWith(storageRoot.toRealPath())) throw new IllegalStateException("视频路径无效");
            }
            if (!Files.isRegularFile(input) || Files.size(input) <= 0 || Files.size(input) > MAX_VIDEO_BYTES) {
                throw new IllegalStateException("视频无效");
            }
            extractor.extract(input, output);
            validateFrame(output);
            privatePermissions(output, false);
            Files.move(output, frame, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(output);
            if (downloaded != null) Files.deleteIfExists(downloaded);
        }
    }

    private void downloadVideo(String location, Path target) throws Exception {
        AtomicReference<InputStream> activeStream = new AtomicReference<>();
        FutureTask<Void> download = new FutureTask<>(() -> {
            try (InputStream source = oss.open(location)) {
                activeStream.set(source);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try (var destination = Files.newOutputStream(target)) {
                    privatePermissions(target, false);
                    byte[] block = new byte[1024 * 1024];
                    long total = 0;
                    for (int count; (count = source.read(block)) >= 0;) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        total += count;
                        if (total > MAX_VIDEO_BYTES) throw new IllegalStateException("视频超限");
                        destination.write(block, 0, count);
                    }
                }
                return null;
            } finally {
                activeStream.set(null);
                if (Thread.currentThread().isInterrupted()) Files.deleteIfExists(target);
            }
        });
        Thread.ofVirtual().name("video-audit-download").start(download);
        try {
            download.get(120, TimeUnit.SECONDS);
        } catch (Exception failure) {
            download.cancel(true);
            InputStream source = activeStream.get();
            if (source != null) {
                // Closing a stalled network stream must not hold the review lease indefinitely.
                Thread.ofVirtual().start(() -> { try { source.close(); } catch (Exception ignored) { } });
            }
            throw failure;
        }
    }

    private static void extractWithFfmpeg(Path input, Path output) throws Exception {
        // No seek or fps filter: retain the very first decoded frame, including black or transition frames.
        Process process = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-protocol_whitelist", "file,pipe", "-i", input.toString(), "-map", "0:v:0", "-frames:v", "1",
                "-vf", "scale=1280:1280:force_original_aspect_ratio=decrease", "-q:v", "3", output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("首帧提取失败");
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    static AuditResult parseResult(Object raw, Path frame) {
        if (!(raw instanceof Map<?, ?> value) || !value.keySet().equals(Set.of("layout", "confidence", "reason"))
                || !(value.get("layout") instanceof String layout) || !LAYOUTS.contains(layout)
                || !(value.get("confidence") instanceof Number confidence) || !Double.isFinite(confidence.doubleValue())
                || confidence.doubleValue() < 0 || confidence.doubleValue() > 1
                || !(value.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 600) {
            return unknown("首帧审核返回格式无效，已保留截图并暂停", frame);
        }
        return new AuditResult(layout, confidence.doubleValue(), reason.trim(), frame, size(frame));
    }

    private static void validateFrame(Path frame) throws Exception {
        if (!Files.isRegularFile(frame, LinkOption.NOFOLLOW_LINKS) || Files.size(frame) <= 0
                || Files.size(frame) > MAX_FRAME_BYTES) throw new IllegalStateException("没有首帧截图");
        var image = ImageIO.read(frame.toFile());
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0
                || image.getWidth() > 1280 || image.getHeight() > 1280) throw new IllegalStateException("截图无效");
    }

    private static void writePrivate(Path target, byte[] bytes) throws Exception {
        Path partial = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.write(partial, bytes);
            privatePermissions(partial, false);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(partial); }
    }

    private static void privatePermissions(Path path, boolean directory) throws Exception {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); }
        catch (UnsupportedOperationException ignored) { /* Non-POSIX development filesystem. */ }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static boolean safeId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,100}"); }
    private static long size(Path path) {
        try { return path == null ? 0 : Files.size(path); } catch (Exception ignored) { return 0; }
    }
    private static AuditResult unknown(String reason, Path frame) { return new AuditResult("UNKNOWN", 0, reason, frame, size(frame)); }

    @FunctionalInterface
    interface FrameExtractor { void extract(Path input, Path output) throws Exception; }

    public record AuditResult(String layout, double confidence, String reason, Path framePath, long frameSizeBytes) {
        public boolean passed() { return confidence >= 0.8 && confidence <= 1 && "SINGLE_SCENE".equals(layout); }
        public boolean rejected() { return confidence >= 0.8 && confidence <= 1
                && ("GRID".equals(layout) || "SPLIT_SCREEN".equals(layout) || "COLLAGE".equals(layout)); }
    }
}
