package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** Reviews a retained master. It never generates images or retries a model call. */
@Service
public class CommerceDrapeStyleReview {
    public static final String CONTRACT_VERSION = "COMMERCE_DRAPE_STYLE_V1";
    public static final String NECKLINE_CONTRACT_VERSION = "COMMERCE_DRAPE_STYLE_V4";
    private static final String DETAIL_GUARD_VERSION = "DRAPE_DETAIL_GUARD_V1";
    static final List<String> CHECKS = List.of("hasScene", "hasSupportContact", "hasNaturalDrape",
            "preservesGarment", "preservesPrintPlacement", "fullyVisible");
    static final List<String> NECKLINE_CHECKS = java.util.stream.Stream.concat(
            CHECKS.stream(), java.util.stream.Stream.of("preservesNeckline",
                    "preservesSleeveBadge", "preservesSideLabel")).toList();
    private static final int PANEL_SIZE = 640;
    private static final int LABEL_HEIGHT = 48;
    private final ModelConfigService models;
    private final RestClient vision;
    private final RestClient detailGuard;
    private final Path cache;
    private final Object[] locks = new Object[32];
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public CommerceDrapeStyleReview(ModelConfigService models,
            @Value("${app.pi-runtime-url}") String url,
            @Value("${app.pi-runtime-token}") String token,
            @Value("${app.product-capture.watermark.local-url:http://watermark-detector:8091}") String detectorUrl,
            @Value("${app.product-capture.watermark.local-token:}") String detectorToken,
            @Value("${app.ai-video-task-storage-root:${app.ai-video-task-storage:./data/ai-video-tasks}}") String storage) {
        this.models = models;
        this.cache = Path.of(storage, "commerce-drape-style-review").toAbsolutePath().normalize();
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        // The Pi runtime waits for meaningful model progress and times out idle
        // streams itself; an outer wall-clock cutoff would discard active work.
        factory.setReadTimeout(0);
        vision = RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token", token)
                .requestFactory(factory).build();
        var detectorFactory = new SimpleClientHttpRequestFactory();
        detectorFactory.setConnectTimeout(5000);
        // Local processing shares a serialized image queue with rendering. It
        // can wait behind another job without the visual model being stalled.
        detectorFactory.setReadTimeout(300000);
        detailGuard = RestClient.builder().baseUrl(detectorUrl)
                .defaultHeader("X-Internal-Token", detectorToken)
                .requestFactory(detectorFactory).build();
    }

    CommerceDrapeStyleReview(ModelConfigService models, RestClient vision, Path cache) {
        this(models, vision, null, cache);
    }

    CommerceDrapeStyleReview(ModelConfigService models, RestClient vision, RestClient detailGuard, Path cache) {
        this.models = models;
        this.vision = vision;
        this.detailGuard = detailGuard;
        this.cache = cache;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    /** A valid negative review returns passed=false; an unavailable review must not trigger regeneration. */
    public Map<String, Object> review(String organizationId, byte[] guide, byte[] scene, byte[] master) {
        return review(organizationId, guide, scene, master, false);
    }

    public Map<String, Object> review(String organizationId, byte[] guide, byte[] scene, byte[] master,
            boolean requireNeckline) {
        if (organizationId == null || organizationId.isBlank())
            throw new IllegalArgumentException("搭放母图验收缺少组织信息");
        if (guide == null || scene == null || master == null)
            throw new IllegalArgumentException("搭放母图验收参考图缺失");
        var model = models.driverConnection(organizationId)
                .orElseThrow(() -> unavailable("搭放母图验收需要配置视觉模型"));
        String contract = requireNeckline ? NECKLINE_CONTRACT_VERSION : CONTRACT_VERSION;
        var checks = requireNeckline ? NECKLINE_CHECKS : CHECKS;
        var binding = Map.<String, Object>of("contractVersion", contract,
                "guideSha256", CommerceImageArchive.sha(guide), "sceneSha256", CommerceImageArchive.sha(scene),
                "masterSha256", CommerceImageArchive.sha(master), "modelFingerprint", modelFingerprint(model),
                "organizationSha256", CommerceImageArchive.sha(organizationId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "reviewSheetVersion", requireNeckline ? "ORIGINAL_EDGE_NECKLINE_V3" : "STANDARD_V1");
        // Stable key order is independent of Map iteration and survives runtime restarts.
        String key = CommerceImageArchive.sha(json.writeValueAsBytes(new java.util.TreeMap<>(binding)));
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            Path path = cache.resolve(key + ".json");
            Map<String, Object> saved = cached(path, binding, checks);
            if (saved != null) return saved;
            Path visionPath = cache.resolve("vision-" + key + ".json");
            Map<String, Object> analyzed = requireNeckline ? cachedVision(visionPath, binding, checks) : null;
            if (analyzed == null) {
                byte[] sheet = requireNeckline ? detailContactSheet(guide, scene, master)
                        : contactSheet(guide, scene, master);
                analyzed = analyze(model, sheet, binding, checks);
                // This is only an observation, never an approval. Retain it so
                // an overloaded local detail queue cannot trigger a paid model call again.
                if (requireNeckline) store(visionPath, analyzed);
            }
            Map<String, Object> verdict = analyzed;
            boolean potentiallyCorrectable = requireNeckline
                    && NECKLINE_CHECKS.stream()
                        .filter(flag -> !List.of("fullyVisible", "preservesSleeveBadge", "preservesSideLabel").contains(flag))
                        .allMatch(flag -> Boolean.TRUE.equals(verdict.get(flag)));
            Map<String, Object> report = potentiallyCorrectable
                    ? verifySmallDetails(analyzed, guide, master) : analyzed;
            if (Boolean.TRUE.equals(report.get("passed"))) store(path, report);
            return report;
        }
    }

    private Map<String, Object> verifySmallDetails(Map<String, Object> modelReport, byte[] guide, byte[] master) {
        if (detailGuard == null) throw unavailable("服装细节本地核查服务不可用；已保留母图");
        Map<?, ?> observed;
        try {
            observed = detailGuard.post().uri("/drape-detail-check").contentType(MediaType.APPLICATION_JSON)
                    // The local HTTP server requires Content-Length. A Map body
                    // can be sent chunked by the streaming request factory and
                    // was rejected with 413 before it ever ran the checker.
                    .body(json.writeValueAsBytes(Map.of("guide", Base64.getEncoder().encodeToString(guide),
                            "master", Base64.getEncoder().encodeToString(master))))
                    .retrieve().body(Map.class);
        } catch (RuntimeException failure) {
            throw new DetailGuardUnavailableException("服装细节核查暂未返回；已保留母图，后台自动续试");
        }
        if (observed == null || !DETAIL_GUARD_VERSION.equals(observed.get("algorithm"))
                || !Objects.equals(modelReport.get("guideSha256"), observed.get("guideSha256"))
                || !Objects.equals(modelReport.get("masterSha256"), observed.get("masterSha256"))
                || !(observed.get("geometryFullyVisible") instanceof Boolean)
                || !(observed.get("borderMargins") instanceof Map<?, ?>))
            throw unavailable("服装细节核查结果与当前模板或母图不一致；已保留母图");
        if (!List.of("PASSED", "REJECTED", "UNAVAILABLE").contains(observed.get("status")))
            throw unavailable("服装细节核查结果不完整；已保留母图");
        var report = new LinkedHashMap<String, Object>(modelReport);
        report.put("detailGuardVersion", DETAIL_GUARD_VERSION);
        report.put("detailGuardStatus", observed.get("status"));
        report.put("borderMargins", observed.get("borderMargins"));
        if (!Boolean.TRUE.equals(modelReport.get("fullyVisible"))
                && Boolean.TRUE.equals(observed.get("geometryFullyVisible"))) {
            report.put("modelFullyVisible", false);
            report.put("modelReason", modelReport.get("reason"));
            report.put("fullyVisible", true);
            report.put("visibilityEvidence", "LOCAL_GARMENT_BORDER_V1");
        }
        if ("PASSED".equals(observed.get("status"))) {
            if (!Boolean.TRUE.equals(observed.get("sleevePresent"))
                    || !Boolean.TRUE.equals(observed.get("sidePresent")))
                throw unavailable("服装细节核查结论缺少袖标或侧标证据；已保留母图");
            for (String flag : List.of("preservesSleeveBadge", "preservesSideLabel")) {
                if (!Boolean.TRUE.equals(modelReport.get(flag))) {
                    report.put("model" + Character.toUpperCase(flag.charAt(0)) + flag.substring(1), false);
                    report.put("modelReason", modelReport.get("reason"));
                    report.put(flag, true);
                    report.put("detailEvidence", DETAIL_GUARD_VERSION);
                }
            }
            boolean passed = NECKLINE_CHECKS.stream().allMatch(flag -> Boolean.TRUE.equals(report.get(flag)));
            report.put("passed", passed);
            if (passed && report.containsKey("modelReason"))
                report.put("reason", "领口、版型和场景验收通过；原图边距及袖标、侧标经独立本地核查证实完整，保留视觉模型原判断供追溯");
            return Map.copyOf(report);
        }
        // Absence or an unlocatable template detail never turns a model claim
        // into an approval. Both flags fail closed for an ambiguous template.
        if (!"REJECTED".equals(observed.get("status"))
                || !(observed.get("sleevePresent") instanceof Boolean)
                || !(observed.get("sidePresent") instanceof Boolean))
            throw unavailable("模板上的袖标或侧标无法可靠定位；已保留母图");
        boolean sleeve = Boolean.TRUE.equals(observed.get("sleevePresent"));
        boolean side = Boolean.TRUE.equals(observed.get("sidePresent"));
        report.put("preservesSleeveBadge", sleeve);
        report.put("preservesSideLabel", side);
        report.put("passed", false);
        report.put("reason", (!sleeve ? "袖口徽标缺失或不清晰；" : "")
                + (!side ? "衣身侧边标签缺失或不清晰；" : "") + "本地图像检查未通过");
        return Map.copyOf(report);
    }

    private Map<String, Object> analyze(ModelConfigService.RuntimeModelConnection model,
            byte[] sheet, Map<String, Object> binding, List<String> checks) {
        var context = Map.of("analysisType", binding.get("contractVersion"), "panels", List.of(
                Map.of("index", 1, "role", "GUIDE"),
                Map.of("index", 2, "role", "SOURCE"),
                Map.of("index", 3, "role", "MASTER")));
        var payload = Map.of("model", model.driverRequestModel(), "context", context,
                "image", Map.of("mimeType", "image/jpeg", "data", Base64.getEncoder().encodeToString(sheet)));
        Map<?, ?> response;
        try {
            response = vision.post().uri("/v1/product/analyze").contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().body(Map.class);
        } catch (RuntimeException failure) {
            // Do not persist provider bodies, image data, or credentials as failure evidence.
            throw unavailable("搭放母图验收暂未取得结果，请重试验收；已保存母图，不追加生图");
        }
        if (response == null || !(response.get("result") instanceof Map<?, ?> result))
            throw unavailable("搭放母图验收未返回有效结果；已保存母图，不追加生图");
        var report = new LinkedHashMap<String, Object>();
        boolean passed = true;
        for (String field : checks) {
            if (!(result.get(field) instanceof Boolean value))
                throw unavailable("搭放母图验收结果不完整；已保存母图，不追加生图");
            report.put(field, value);
            passed &= value;
        }
        if (!(result.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 300)
            throw unavailable("搭放母图验收缺少有效图像依据；已保存母图，不追加生图");
        report.put("passed", passed);
        report.put("reason", reason.strip());
        report.putAll(binding);
        return Map.copyOf(report);
    }

    private Map<String, Object> cached(Path path, Map<String, Object> binding, List<String> checks) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16000)
                throw unavailable("搭放母图验收缓存无法读取；保留母图，不追加生图");
            var raw = json.readValue(Files.readString(path), Map.class);
            if (raw == null || !Boolean.TRUE.equals(raw.get("passed"))
                    || checks.stream().anyMatch(field -> !Boolean.TRUE.equals(raw.get(field)))
                    || (NECKLINE_CONTRACT_VERSION.equals(binding.get("contractVersion"))
                        && (!DETAIL_GUARD_VERSION.equals(raw.get("detailGuardVersion"))
                            || !"PASSED".equals(raw.get("detailGuardStatus"))))
                    || !(raw.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 300
                    || binding.entrySet().stream().anyMatch(entry -> !Objects.equals(raw.get(entry.getKey()), entry.getValue())))
                throw unavailable("搭放母图验收缓存与输入不一致；保留母图，不追加生图");
            var report = new LinkedHashMap<String, Object>(binding);
            report.put("passed", true);
            report.put("reason", reason);
            checks.forEach(field -> report.put(field, true));
            if (NECKLINE_CONTRACT_VERSION.equals(binding.get("contractVersion"))) {
                report.put("detailGuardVersion", DETAIL_GUARD_VERSION);
                report.put("detailGuardStatus", "PASSED");
            }
            return Map.copyOf(report);
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            throw unavailable("搭放母图验收缓存无法读取；保留母图，不追加生图");
        }
    }

    private Map<String, Object> cachedVision(Path path, Map<String, Object> binding, List<String> checks) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16000)
                throw unavailable("视觉验收暂存记录无法读取；保留母图，不重复调用模型");
            var raw = json.readValue(Files.readString(path), Map.class);
            if (raw == null || checks.stream().anyMatch(field -> !(raw.get(field) instanceof Boolean))
                    || !Objects.equals(raw.get("passed"),
                        checks.stream().allMatch(field -> Boolean.TRUE.equals(raw.get(field))))
                    || !(raw.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 300
                    || binding.entrySet().stream().anyMatch(entry -> !Objects.equals(raw.get(entry.getKey()), entry.getValue())))
                throw unavailable("视觉验收暂存记录与输入不一致；保留母图，不重复调用模型");
            return Map.copyOf(raw);
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            throw unavailable("视觉验收暂存记录无法读取；保留母图，不重复调用模型");
        }
    }

    private void store(Path path, Map<String, Object> report) {
        Path temp = null;
        try {
            Files.createDirectories(cache);
            temp = Files.createTempFile(cache, "review-", ".tmp");
            Files.writeString(temp, json.writeValueAsString(report));
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw unavailable("搭放母图验收结果暂未保存，请重试验收；保留母图，不追加生图");
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    private String modelFingerprint(ModelConfigService.RuntimeModelConnection model) {
        var identity = List.of(Objects.toString(model.id(), ""), Objects.toString(model.baseUrl(), ""),
                Objects.toString(model.modelId(), ""), Objects.toString(model.apiFormat(), ""),
                Objects.toString(model.provider(), ""), Objects.toString(model.apiKey(), ""),
                model.fallbackModels().stream().map(this::modelFingerprint).toList());
        return CommerceImageArchive.sha(json.writeValueAsBytes(identity));
    }

    /** Contains all three original aspect ratios. The labeled header is outside the images. */
    static byte[] contactSheet(byte[] guide, byte[] scene, byte[] master) {
        return contactSheet(guide, scene, master, false);
    }

    static byte[] detailContactSheet(byte[] guide, byte[] scene, byte[] master) {
        return contactSheet(guide, scene, master, true);
    }

    private static byte[] contactSheet(byte[] guide, byte[] scene, byte[] master, boolean details) {
        var inputs = List.of(CommerceMockupComposer.decode(guide), CommerceMockupComposer.decode(scene),
                CommerceMockupComposer.decode(master));
        var sheet = new BufferedImage(PANEL_SIZE * 3, PANEL_SIZE + LABEL_HEIGHT + (details ? 480 : 0),
                BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        try {
            graphics.setColor(new Color(236, 236, 236));
            graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 23));
            var labels = List.of("1 GUIDE / garment + print", "2 SOURCE / scene + pose", "3 MASTER / candidate");
            for (int i = 0; i < inputs.size(); i++) {
                int x = i * PANEL_SIZE;
                graphics.setColor(Color.WHITE);
                graphics.fillRect(x, 0, PANEL_SIZE, LABEL_HEIGHT);
                graphics.setColor(Color.BLACK);
                graphics.drawString(labels.get(i), x + 16, 31);
                var input = inputs.get(i);
                // Keep the original MASTER boundary visible inside the panel.
                // A near-edge sleeve otherwise appears cut off by the contact
                // sheet itself even when the archived image has real margins.
                int inset = details && i == 2 ? 38 : 0;
                double scale = Math.min((double) (PANEL_SIZE - 2 * inset) / input.getWidth(),
                        (double) (PANEL_SIZE - 2 * inset) / input.getHeight());
                int width = Math.max(1, (int) Math.round(input.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(input.getHeight() * scale));
                int imageX = x + (PANEL_SIZE - width) / 2;
                int imageY = LABEL_HEIGHT + (PANEL_SIZE - height) / 2;
                graphics.drawImage(input, imageX, imageY, width, height, null);
                if (inset > 0) {
                    graphics.setColor(new Color(80, 80, 80));
                    graphics.drawRect(imageX - 1, imageY - 1, width + 1, height + 1);
                }
            }
            if (details) {
                var labelsDetail = List.of("GUIDE / sleeve badge", "MASTER / sleeve badge",
                        "GUIDE / side label", "MASTER / side label");
                for (int i = 0; i < 4; i++) {
                    var image = inputs.get(i % 2 == 0 ? 0 : 2);
                    boolean sleeve = i < 2;
                    int sx = (int) (image.getWidth() * (sleeve ? 0 : .65));
                    int sy = (int) (image.getHeight() * (sleeve ? .30 : .60));
                    int sw = Math.max(1, (int) (image.getWidth() * (sleeve ? .37 : .35)));
                    int sh = Math.max(1, (int) (image.getHeight() * (sleeve ? .38 : .40)));
                    int x = i * 480, y = PANEL_SIZE + LABEL_HEIGHT;
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(x, y, 480, 240);
                    graphics.setColor(Color.BLACK);
                    graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
                    graphics.drawString(labelsDetail.get(i), x + 10, y + 24);
                    graphics.drawImage(image, x, y + 30, x + 480, y + 240,
                            sx, sy, Math.min(image.getWidth(), sx + sw),
                            Math.min(image.getHeight(), sy + sh), null);
                }
                // Show the actual collar edge and its woven label at a readable
                // size. The top full-view panels remain the authority for shape.
                for (int i = 0; i < 2; i++) {
                    var image = inputs.get(i == 0 ? 0 : 2);
                    int x = i * 480, y = PANEL_SIZE + LABEL_HEIGHT + 240;
                    int sx = image.getWidth() / 4;
                    int sy = image.getHeight() / 50;
                    int sw = image.getWidth() / 2;
                    int sh = Math.max(1, image.getHeight() * 11 / 50);
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(x, y, 480, 240);
                    graphics.setColor(Color.BLACK);
                    graphics.drawString(i == 0 ? "GUIDE / neckline" : "MASTER / neckline", x + 10, y + 24);
                    graphics.drawImage(image, x, y + 30, x + 480, y + 240,
                            sx, sy, sx + sw, Math.min(image.getHeight(), sy + sh), null);
                }
            }
        } finally {
            graphics.dispose();
        }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var bytes = new ByteArrayOutputStream(); var output = ImageIO.createImageOutputStream(bytes)) {
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(.92f);
            writer.setOutput(output);
            writer.write(null, new IIOImage(sheet, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("搭放母图验收参考图无法读取");
        } finally {
            writer.dispose();
        }
    }

    private static ReviewUnavailableException unavailable(String message) {
        return new ReviewUnavailableException(message);
    }

    /** No quality verdict exists. The caller may retry this read, never interpret it as rejection. */
    public static class ReviewUnavailableException extends IllegalStateException {
        public ReviewUnavailableException(String message) { super(message); }
    }

    /** A completed visual verdict is safe to reuse while the local queue retries. */
    public static class DetailGuardUnavailableException extends ReviewUnavailableException {
        public DetailGuardUnavailableException(String message) { super(message); }
    }
}
