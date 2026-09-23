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
    static final List<String> CHECKS = List.of("hasScene", "hasSupportContact", "hasNaturalDrape",
            "preservesGarment", "preservesPrintPlacement", "fullyVisible");
    private static final int PANEL_SIZE = 640;
    private static final int LABEL_HEIGHT = 48;
    private final ModelConfigService models;
    private final RestClient vision;
    private final Path cache;
    private final Object[] locks = new Object[32];
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public CommerceDrapeStyleReview(ModelConfigService models,
            @Value("${app.pi-runtime-url}") String url,
            @Value("${app.pi-runtime-token}") String token,
            @Value("${app.ai-video-task-storage-root:${app.ai-video-task-storage:./data/ai-video-tasks}}") String storage) {
        this.models = models;
        this.cache = Path.of(storage, "commerce-drape-style-review").toAbsolutePath().normalize();
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(110000);
        vision = RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token", token)
                .requestFactory(factory).build();
    }

    CommerceDrapeStyleReview(ModelConfigService models, RestClient vision, Path cache) {
        this.models = models;
        this.vision = vision;
        this.cache = cache;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    /** A valid negative review returns passed=false; an unavailable review must not trigger regeneration. */
    public Map<String, Object> review(String organizationId, byte[] guide, byte[] scene, byte[] master) {
        if (organizationId == null || organizationId.isBlank())
            throw new IllegalArgumentException("搭放母图验收缺少组织信息");
        if (guide == null || scene == null || master == null)
            throw new IllegalArgumentException("搭放母图验收参考图缺失");
        var model = models.driverConnection(organizationId)
                .orElseThrow(() -> unavailable("搭放母图验收需要配置视觉模型"));
        var binding = Map.<String, Object>of("contractVersion", CONTRACT_VERSION,
                "guideSha256", CommerceImageArchive.sha(guide), "sceneSha256", CommerceImageArchive.sha(scene),
                "masterSha256", CommerceImageArchive.sha(master), "modelFingerprint", modelFingerprint(model),
                "organizationSha256", CommerceImageArchive.sha(organizationId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // Stable key order is independent of Map iteration and survives runtime restarts.
        String key = CommerceImageArchive.sha(json.writeValueAsBytes(new java.util.TreeMap<>(binding)));
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            Path path = cache.resolve(key + ".json");
            Map<String, Object> saved = cached(path, binding);
            if (saved != null) return saved;
            byte[] sheet = contactSheet(guide, scene, master);
            Map<String, Object> report = analyze(model, sheet, binding);
            if (Boolean.TRUE.equals(report.get("passed"))) store(path, report);
            return report;
        }
    }

    private Map<String, Object> analyze(ModelConfigService.RuntimeModelConnection model,
            byte[] sheet, Map<String, Object> binding) {
        var context = Map.of("analysisType", CONTRACT_VERSION, "panels", List.of(
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
        for (String field : CHECKS) {
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

    private Map<String, Object> cached(Path path, Map<String, Object> binding) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16000)
                throw unavailable("搭放母图验收缓存无法读取；保留母图，不追加生图");
            var raw = json.readValue(Files.readString(path), Map.class);
            if (raw == null || !Boolean.TRUE.equals(raw.get("passed"))
                    || CHECKS.stream().anyMatch(field -> !Boolean.TRUE.equals(raw.get(field)))
                    || !(raw.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 300
                    || binding.entrySet().stream().anyMatch(entry -> !Objects.equals(raw.get(entry.getKey()), entry.getValue())))
                throw unavailable("搭放母图验收缓存与输入不一致；保留母图，不追加生图");
            var report = new LinkedHashMap<String, Object>(binding);
            report.put("passed", true);
            report.put("reason", reason);
            CHECKS.forEach(field -> report.put(field, true));
            return Map.copyOf(report);
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            throw unavailable("搭放母图验收缓存无法读取；保留母图，不追加生图");
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
        var inputs = List.of(CommerceMockupComposer.decode(guide), CommerceMockupComposer.decode(scene),
                CommerceMockupComposer.decode(master));
        var sheet = new BufferedImage(PANEL_SIZE * 3, PANEL_SIZE + LABEL_HEIGHT, BufferedImage.TYPE_INT_RGB);
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
                double scale = Math.min((double) PANEL_SIZE / input.getWidth(), (double) PANEL_SIZE / input.getHeight());
                int width = Math.max(1, (int) Math.round(input.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(input.getHeight() * scale));
                graphics.drawImage(input, x + (PANEL_SIZE - width) / 2,
                        LABEL_HEIGHT + (PANEL_SIZE - height) / 2, width, height, null);
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
}
