package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Frozen original photographic design and selective old-print regions; never silently blurs a whole scene. */
@Service
public class CommerceModelDetailStyleGuide {
    public static final String CONTRACT_VERSION = "COMMERCE_MODEL_DETAIL_STYLE_GUIDE_V1";
    public static final String MATCH_VERSION = "COMMERCE_MODEL_DETAIL_STYLE_MATCH_V1";
    public static final String POSE_ONLY_POLICY = "POSE_ONLY_TEMPLATE_V1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SHOTS = Set.of("SEATED_MODEL", "STANDING_MODEL", "WORN_CLOSEUP", "PRINT_MACRO", "FLAT_LAY", "OTHER");
    private static final Set<String> GUIDE_FIELDS = Set.of("shotType", "composition", "pose", "setting", "lighting", "hasVisiblePrint", "regions", "complete");
    private static final Set<String> MATCH_FIELDS = Set.of("matchesShotType", "matchesPose", "matchesSetting", "reason");
    private final ModelConfigService models;
    private final RestClient vision;
    private final Path cache;
    private final Object[] locks = new Object[32];

    public record Region(double x, double y, double width, double height) {
        public Region {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
                    || x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > 1 || y + height > 1)
                throw new IllegalArgumentException("原详情印花坐标无效");
        }
    }

    public record Guide(String contractVersion, String sourceSha256, String modelFingerprint, String organizationSha256,
                        String shotType, String composition, String pose, String setting, String lighting,
                        boolean hasVisiblePrint, List<Region> regions, String description) {
        public Guide {
            regions = regions == null ? null : List.copyOf(regions);
            validateGuide(contractVersion, sourceSha256, modelFingerprint, organizationSha256, shotType, composition,
                    pose, setting, lighting, hasVisiblePrint, regions, description);
        }
        public boolean printMacro() { return "PRINT_MACRO".equals(shotType); }
    }

    public record StyleMatch(boolean passed, boolean matchesShotType, boolean matchesPose, boolean matchesSetting,
                             String reason, String contractVersion, String sourceSha256, String outputSha256,
                             String guideSha256, String modelFingerprint, String organizationSha256, String renderPolicy) {
        public StyleMatch(boolean passed, boolean matchesShotType, boolean matchesPose, boolean matchesSetting,
                          String reason, String contractVersion, String sourceSha256, String outputSha256,
                          String guideSha256, String modelFingerprint, String organizationSha256) {
            this(passed,matchesShotType,matchesPose,matchesSetting,reason,contractVersion,sourceSha256,outputSha256,guideSha256,modelFingerprint,organizationSha256,null);
        }
        public StyleMatch {
            if (renderPolicy != null && !POSE_ONLY_POLICY.equals(renderPolicy)) throw new IllegalArgumentException("不支持的详情姿势参考规则");
            if (passed != (matchesShotType && matchesPose && matchesSetting) || !MATCH_VERSION.equals(contractVersion)
                    || !validText(reason, 300) || !hash(sourceSha256) || !hash(outputSha256) || !hash(guideSha256)
                    || !hash(modelFingerprint) || !hash(organizationSha256)) throw new IllegalArgumentException("详情摄影设计对照结果无效");
        }
    }

    @Autowired
    public CommerceModelDetailStyleGuide(ModelConfigService models,
            @Value("${app.pi-runtime-url}") String url, @Value("${app.pi-runtime-token}") String token,
            @Value("${app.ai-video-task-storage-root:${app.ai-video-task-storage:./data/ai-video-tasks}}") String storage) {
        this.models = models;
        cache = Path.of(storage, "commerce-model-detail-style").toAbsolutePath().normalize();
        Arrays.setAll(locks, ignored -> new Object());
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(5000);
        // Pi watches token activity and returns only a complete analysis. Active reasoning has no wall-clock cutoff here.
        factory.setReadTimeout(0);
        vision = RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token", token).requestFactory(factory).build();
    }

    CommerceModelDetailStyleGuide(ModelConfigService models, RestClient vision, Path cache) {
        this.models = models; this.vision = vision; this.cache = cache; Arrays.setAll(locks, ignored -> new Object());
    }

    public Guide prepare(String org, byte[] original) {
        String tenant = tenant(org); requireBytes(original);
        var model = models.driverConnection(org).orElseThrow(() -> unavailable("STYLE_MODEL_UNCONFIGURED", false, false, "原详情设计识别需要配置视觉模型"));
        String sourceHash = CommerceImageArchive.sha(original), fingerprint = fingerprint(model);
        String key = key(CONTRACT_VERSION, sourceHash, fingerprint, tenant);
        synchronized (lock(key)) {
            Path path = cache.resolve(key + ".json");
            var saved = cached(path, Guide.class);
            if (saved != null) {
                if (!sourceHash.equals(saved.sourceSha256()) || !fingerprint.equals(saved.modelFingerprint()) || !tenant.equals(saved.organizationSha256()))
                    throw unavailable("STYLE_CACHE_BINDING_INVALID", false, false, "原详情设计缓存与当前图片或模型不一致");
                return validateFrozen(saved);
            }
            byte[] image = analysisImage(original);
            var result = analyze(model, CONTRACT_VERSION, image, Map.of("imageRole", "ORIGINAL_STYLE"));
            Guide guide;
            try {
                if (!result.keySet().equals(GUIDE_FIELDS) || !Boolean.TRUE.equals(result.get("complete")) || !(result.get("hasVisiblePrint") instanceof Boolean visible)
                        || !(result.get("regions") instanceof List<?> rawRegions)) throw new IllegalArgumentException();
                var regions = new ArrayList<Region>();
                for (Object raw : rawRegions) {
                    if (!(raw instanceof Map<?, ?> region) || !region.keySet().equals(Set.of("x", "y", "width", "height"))) throw new IllegalArgumentException();
                    regions.add(new Region(number(region.get("x")), number(region.get("y")), number(region.get("width")), number(region.get("height"))));
                }
                String shot = string(result.get("shotType")), composition = string(result.get("composition")), pose = string(result.get("pose")),
                        setting = string(result.get("setting")), lighting = string(result.get("lighting"));
                guide = new Guide(CONTRACT_VERSION, sourceHash, fingerprint, tenant, shot, composition, pose, setting, lighting,
                        visible, regions, description(shot, composition, pose, setting, lighting));
            } catch (RuntimeException invalid) {
                throw unavailable("STYLE_GUIDE_INVALID", false, true, "原详情的构图或旧印花区域未能完整识别；保留原图，不回退全图模糊");
            }
            store(path, guide); return guide;
        }
    }

    /** Completed preparation is reusable only under the same tenant and frozen driver configuration. */
    public void requireCurrentModel(String org,Guide guide) {
        validateFrozen(guide);
        var model=models.driverConnection(org).orElseThrow(()->unavailable("STYLE_MODEL_UNCONFIGURED",false,false,"原详情设计识别需要配置视觉模型"));
        if(!tenant(org).equals(guide.organizationSha256())||!fingerprint(model).equals(guide.modelFingerprint()))
            throw unavailable("STYLE_GUIDE_BINDING_INVALID",false,false,"原详情设计说明与当前公司或模型配置不一致，保留原结果");
    }

    public StyleMatch compare(String org, byte[] original, byte[] output, Guide guide) {
        return compare(org, original, output, guide, null);
    }

    public StyleMatch compare(String org, byte[] original, byte[] output, Guide guide, String renderPolicy) {
        if (renderPolicy != null && !POSE_ONLY_POLICY.equals(renderPolicy)) throw new IllegalArgumentException("不支持的详情姿势参考规则");
        validateFrozen(guide); String tenant = tenant(org); requireBytes(original); requireBytes(output);
        String sourceHash = CommerceImageArchive.sha(original), outputHash = CommerceImageArchive.sha(output), guideHash = sha(guide);
        if (!sourceHash.equals(guide.sourceSha256()) || !tenant.equals(guide.organizationSha256()))
            throw new IllegalArgumentException("摄影设计说明与当前公司或原详情图片不一致");
        var model = models.driverConnection(org).orElseThrow(() -> unavailable("STYLE_MODEL_UNCONFIGURED", false, false, "详情摄影设计对照需要配置视觉模型"));
        String fingerprint = fingerprint(model), key = renderPolicy == null
                ? key(MATCH_VERSION, sourceHash, outputHash, guideHash, fingerprint, tenant)
                : key(MATCH_VERSION, sourceHash, outputHash, guideHash, fingerprint, tenant, renderPolicy);
        synchronized (lock(key)) {
            Path path = cache.resolve(key + ".json"); var saved = cached(path, StyleMatch.class);
            if (saved != null) {
                if (!saved.passed() || !Objects.equals(renderPolicy, saved.renderPolicy()) || !sourceHash.equals(saved.sourceSha256()) || !outputHash.equals(saved.outputSha256())
                        || !guideHash.equals(saved.guideSha256()) || !fingerprint.equals(saved.modelFingerprint()) || !tenant.equals(saved.organizationSha256()))
                    throw unavailable("STYLE_CACHE_BINDING_INVALID", false, false, "详情摄影设计对照缓存与当前图片不一致");
                return saved;
            }
            var context = new LinkedHashMap<String,Object>(); context.put("guide",guide);
            context.put("panels",List.of(Map.of("index",1,"role","ORIGINAL"),Map.of("index",2,"role","OUTPUT")));
            if (renderPolicy != null) context.put("renderPolicy",renderPolicy);
            var result = analyze(model, MATCH_VERSION, comparison(original, output), context);
            StyleMatch match;
            try {
                if (!result.keySet().equals(MATCH_FIELDS) || !(result.get("matchesShotType") instanceof Boolean shot)
                        || !(result.get("matchesPose") instanceof Boolean pose) || !(result.get("matchesSetting") instanceof Boolean setting)) throw new IllegalArgumentException();
                match = new StyleMatch(shot && pose && setting, shot, pose, setting, string(result.get("reason")), MATCH_VERSION,
                        sourceHash, outputHash, guideHash, fingerprint, tenant, renderPolicy);
            } catch (RuntimeException invalid) { throw unavailable("STYLE_MATCH_INVALID", false, true, "详情摄影设计对照结果不完整；已生成图片保留，仅重试检查"); }
            if (match.passed()) store(path, match);
            return match;
        }
    }

    public static Guide validateFrozen(Guide guide) {
        if (guide == null) throw new IllegalArgumentException("缺少固定原详情设计说明");
        validateGuide(guide.contractVersion(), guide.sourceSha256(), guide.modelFingerprint(), guide.organizationSha256(), guide.shotType(),
                guide.composition(), guide.pose(), guide.setting(), guide.lighting(), guide.hasVisiblePrint(), guide.regions(), guide.description());
        return guide;
    }
    public static String sha(Guide guide) { return CommerceImageArchive.sha(JSON.writeValueAsBytes(validateFrozen(guide))); }

    private static void validateGuide(String contract, String source, String model, String org, String shot, String composition,
            String pose, String setting, String lighting, boolean visible, List<Region> regions, String description) {
        if (!CONTRACT_VERSION.equals(contract) || !hash(source) || !hash(model) || !hash(org) || !SHOTS.contains(Objects.toString(shot, ""))
                || !validText(composition, 180) || !validText(pose, 180) || !validText(setting, 180) || !validText(lighting, 180)
                || regions == null || regions.size() > 8 || visible != !regions.isEmpty()
                || !description(shot, composition, pose, setting, lighting).equals(description)) throw new IllegalArgumentException("原详情设计说明不完整");
        if ("PRINT_MACRO".equals(shot)) {
            if (regions.size() != 1 || !regions.getFirst().equals(new Region(0, 0, 1, 1))) throw new IllegalArgumentException("印花特写需要明确全图范围");
        } else if (regions.stream().anyMatch(r -> r.width() * r.height() > .55) || unionArea(regions) > .65 + 1e-9)
            throw new IllegalArgumentException("原详情印花遮罩面积过大，不得覆盖人物和背景");
    }

    private Map<?, ?> analyze(ModelConfigService.RuntimeModelConnection model, String type, byte[] image, Map<String, Object> detail) {
        var context = new LinkedHashMap<String, Object>(detail); context.put("analysisType", type);
        var payload = Map.of("model", model.driverRequestModel(), "context", context,
                "image", Map.of("mimeType", "image/jpeg", "data", Base64.getEncoder().encodeToString(image)));
        // The worker owns durable backoff. One invocation sends at most one analysis request.
        try {
            var response = vision.post().uri("/v1/product/analyze").contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(Map.class);
            if (response == null || !(response.get("result") instanceof Map<?, ?> result))
                throw unavailable("STYLE_RESPONSE_INVALID", false, true, "原详情设计分析未返回完整结果；不提交图片生成");
            return result;
        } catch (UnavailableException classified) { throw classified; }
        catch (ResourceAccessException network) { throw unavailable("STYLE_NETWORK", true, false, "原详情设计分析连接中断或超时，稍后自动重试"); }
        catch (RestClientResponseException http) { throw responseFailure(http); }
        catch (RuntimeException invalid) { throw unavailable("STYLE_RESPONSE_INVALID", false, true, "原详情设计分析返回格式无效，保留全部图片"); }
    }

    private static UnavailableException responseFailure(RestClientResponseException http) {
        String code=""; Boolean retryable=null;
        try {
            var body=JSON.readTree(http.getResponseBodyAsString());code=body.path("failureCode").asText("");
            if(body.path("retryable").isBoolean())retryable=body.path("retryable").asBoolean();
        } catch(RuntimeException ignored) {}
        if(Set.of("COMMERCE_MODEL_DETAIL_STYLE_INVALID","CHAT_INVALID_RESPONSE","CHAT_EMPTY_RESPONSE","CHAT_INCOMPLETE_RESPONSE","CHAT_OUTPUT_TRUNCATED","CHAT_INVALID_TOOL_CALL").contains(code))
            return unavailable(code,false,true,"详情设计分析返回结果不完整或格式无效，保留全部图片");
        if(Set.of("CHAT_INVALID_CONFIGURATION","CHAT_AUTHENTICATION_FAILED","CHAT_MODEL_UNAVAILABLE","CHAT_PARAMETERS_REJECTED","CHAT_VISION_UNSUPPORTED","CHAT_STREAM_REQUIRED","CHAT_CONTENT_FILTERED").contains(code))
            return unavailable(code,false,false,"详情设计分析模型配置、访问权限或输入要求不满足，请检查模型接入");
        int status=http.getStatusCode().value();
        boolean temporary=Boolean.TRUE.equals(retryable)||(!Boolean.FALSE.equals(retryable)&&(status==408||status==429||status>=500));
        String reason=Set.of("CHAT_TIMEOUT","CHAT_NETWORK_INTERRUPTED","CHAT_RATE_LIMITED","CHAT_UPSTREAM_FAILED").contains(code)?code:"STYLE_HTTP_"+status;
        return unavailable(reason,temporary,false,temporary?"详情设计分析连接暂不可用，稍后自动重试":"详情设计分析请求被拒绝，请检查模型接入");
    }

    private <T> T cached(Path path, Class<T> type) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 24000) throw unavailable("STYLE_CACHE_INVALID", false, false, "原详情设计缓存不可读");
            var node = JSON.readTree(Files.readString(path));
            for (String field : type == Guide.class ? List.of("hasVisiblePrint") : List.of("passed", "matchesShotType", "matchesPose", "matchesSetting"))
                if (!node.path(field).isBoolean()) throw unavailable("STYLE_CACHE_INVALID", false, false, "原详情设计缓存字段无效");
            return JSON.treeToValue(node, type);
        } catch (IOException | RuntimeException invalid) { throw unavailable("STYLE_CACHE_INVALID", false, false, "原详情设计缓存损坏或绑定无效，不能绕过设计检查"); }
    }

    private void store(Path path, Object value) {
        Path pending = null;
        try {
            Files.createDirectories(cache);
            if (Files.isSymbolicLink(cache)) throw new IOException("cache path");
            pending = Files.createTempFile(cache, "style-", ".tmp"); Files.writeString(pending, JSON.writeValueAsString(value));
            Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw unavailable("STYLE_CACHE_WRITE_UNAVAILABLE", true, false, "原详情设计检查结果尚未保存，请重试读取，不追加生图"); }
        finally { if (pending != null) try { Files.deleteIfExists(pending); } catch (IOException ignored) {} }
    }

    private static BufferedImage decode(byte[] bytes) {
        requireBytes(bytes);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("原详情图片无效");
            var reader = readers.next();
            try {
                if (!Set.of("png", "jpeg", "jpg", "webp").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("原详情图片格式无效");
                reader.setInput(input, true, true); int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > 8192 || h > 8192 || (long) w * h > 16_777_216L) throw new IllegalArgumentException("原详情图片像素超限");
                var result = reader.read(0); if (result == null) throw new IllegalArgumentException("原详情图片无法读取"); return result;
            } finally { reader.dispose(); }
        } catch (IOException error) { throw new IllegalArgumentException("原详情图片无法读取", error); }
    }
    private static byte[] analysisImage(byte[] bytes) {
        var source = decode(bytes);
        double scale = Math.min(1, 1280.0 / Math.max(source.getWidth(), source.getHeight()));
        var image = new BufferedImage(Math.max(1, (int) Math.round(source.getWidth() * scale)), Math.max(1, (int) Math.round(source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, image.getWidth(), image.getHeight()); graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null); return jpeg(image); }
        finally { graphics.dispose(); source.flush(); image.flush(); }
    }
    private static byte[] comparison(byte[] original, byte[] output) {
        var inputs = List.of(decode(original), decode(output)); var sheet = new BufferedImage(1280, 688, BufferedImage.TYPE_INT_RGB); var graphics = sheet.createGraphics();
        try {
            graphics.setColor(new Color(236, 236, 236)); graphics.fillRect(0, 0, 1280, 688); graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 23));
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for (int i = 0; i < inputs.size(); i++) {
                graphics.setColor(Color.WHITE); graphics.fillRect(i * 640, 0, 640, 48); graphics.setColor(Color.BLACK); graphics.drawString(i == 0 ? "1 ORIGINAL / design" : "2 OUTPUT / new product", i * 640 + 16, 31);
                var image = inputs.get(i); double scale = Math.min(640.0 / image.getWidth(), 640.0 / image.getHeight());
                int w = Math.max(1, (int) Math.round(image.getWidth() * scale)), h = Math.max(1, (int) Math.round(image.getHeight() * scale));
                graphics.drawImage(image, i * 640 + (640 - w) / 2, 48 + (640 - h) / 2, w, h, null);
            }
            return jpeg(sheet);
        } finally { graphics.dispose(); sheet.flush(); inputs.forEach(BufferedImage::flush); }
    }
    private static byte[] jpeg(BufferedImage image) {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
            var options = writer.getDefaultWriteParam(); options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); options.setCompressionQuality(.94f);
            writer.setOutput(output); writer.write(null, new IIOImage(image, null, null), options); output.flush(); return bytes.toByteArray();
        } catch (IOException error) { throw new IllegalArgumentException("原详情分析图片编码失败", error); } finally { writer.dispose(); }
    }
    private static double unionArea(List<Region> regions) {
        var xs = regions.stream().flatMap(r -> java.util.stream.Stream.of(r.x(), r.x() + r.width())).distinct().sorted().toList(); double area = 0;
        for (int i = 1; i < xs.size(); i++) {
            double left = xs.get(i - 1), right = xs.get(i), length = 0, end = -1;
            var intervals = regions.stream().filter(r -> r.x() < right && r.x() + r.width() > left).sorted(Comparator.comparingDouble(Region::y)).toList();
            for (var region : intervals) { double next = region.y() + region.height(); length += Math.max(0, next - Math.max(region.y(), end)); end = Math.max(end, next); }
            area += (right - left) * length;
        }
        return area;
    }
    private static String description(String shot, String composition, String pose, String setting, String lighting) {
        return "景别=" + shot + "；构图=" + composition + "；姿态=" + pose + "；场景=" + setting + "；光线=" + lighting
                + ("PRINT_MACRO".equals(shot) ? "；保留印花近景，转换为可见领口、肩线或衣服轮廓与身体贴合的实穿局部，让小胸花位置和相对衣身尺寸可辨，不要求露脸或全身，不改为全身站姿。" : "");
    }
    private String fingerprint(ModelConfigService.RuntimeModelConnection model) {
        return CommerceImageArchive.sha(JSON.writeValueAsBytes(List.of(Objects.toString(model.id(), ""), Objects.toString(model.baseUrl(), ""), Objects.toString(model.modelId(), ""),
                Objects.toString(model.apiFormat(), ""), Objects.toString(model.provider(), ""), Objects.toString(model.apiKey(), ""), model.fallbackModels().stream().map(this::fingerprint).toList())));
    }
    private Object lock(String key) { return locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]; }
    private static String key(String... values) { return CommerceImageArchive.sha(JSON.writeValueAsBytes(List.of(values))); }
    private static String tenant(String org) { if (org == null || org.isBlank()) throw new IllegalArgumentException("缺少公司信息"); return CommerceImageArchive.sha(org.getBytes(StandardCharsets.UTF_8)); }
    private static String string(Object value) { if (!(value instanceof String text)) throw new IllegalArgumentException(); return text.strip(); }
    private static double number(Object value) { if (!(value instanceof Number number)) throw new IllegalArgumentException(); return number.doubleValue(); }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean validText(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static void requireBytes(byte[] value) { if (value == null || value.length < 12 || value.length > 8 * 1024 * 1024) throw new IllegalArgumentException("原详情图片缺失或超过8MiB"); }
    private static UnavailableException unavailable(String code,boolean retryable,boolean invalid,String message) { return new UnavailableException(code,retryable,invalid,message); }
    public static class UnavailableException extends IllegalStateException {
        private final String reasonCode;private final boolean retryable,invalidResponse;
        public UnavailableException(String message){this("STYLE_ANALYSIS_UNAVAILABLE",true,false,message);}
        public UnavailableException(String code,boolean retryable,boolean invalidResponse,String message){
            super(message);this.reasonCode=code!=null&&code.matches("[A-Z_0-9]{3,80}")?code:"STYLE_ANALYSIS_UNAVAILABLE";this.retryable=retryable;this.invalidResponse=invalidResponse;
        }
        public String reasonCode(){return reasonCode;}public boolean retryable(){return retryable;}public boolean invalidResponse(){return invalidResponse;}
    }
}
