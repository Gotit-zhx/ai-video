package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import java.util.Set;

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

/** Reviews a retained model-worn detail image. It never generates images or retries a model call. */
@Service
public class CommerceModelDetailReview {
    public static final String CONTRACT_VERSION = "COMMERCE_MODEL_DETAIL_V1";
    public static final String REVIEW_POLICY_VERSION = "COMMERCE_DETAIL_GARMENT_FIDELITY_V2";
    static final List<String> CHECKS = List.of("hasModel", "preservesGarment", "preservesPrint",
            "preservesColor", "coherentPose", "usableComposition");
    private static final int PANEL_SIZE = 640;
    private static final int LABEL_HEIGHT = 48;
    private final ModelConfigService models;
    private final RestClient vision;
    private final Path cache;
    private final Object[] locks = new Object[32];
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public CommerceModelDetailReview(ModelConfigService models,
            @Value("${app.pi-runtime-url}") String url,
            @Value("${app.pi-runtime-token}") String token,
            @Value("${app.ai-video-task-storage-root:${app.ai-video-task-storage:./data/ai-video-tasks}}") String storage) {
        this.models = models;
        this.cache = Path.of(storage, "commerce-model-detail-review").toAbsolutePath().normalize();
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        // Pi watches valid model activity; wait for its complete result without a wall-clock cutoff.
        factory.setReadTimeout(0);
        vision = RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token", token)
                .requestFactory(factory).build();
    }

    CommerceModelDetailReview(ModelConfigService models, RestClient vision, Path cache) {
        this.models = models;
        this.vision = vision;
        this.cache = cache;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    /** A valid negative review returns passed=false; an unavailable review must not trigger regeneration. */
    public Map<String, Object> review(String organizationId, byte[] product, byte[] style, byte[] outputImage) {
        if (organizationId == null || organizationId.isBlank())
            throw new IllegalArgumentException("模特详情图验收缺少组织信息");
        if (product == null || style == null || outputImage == null)
            throw new IllegalArgumentException("模特详情图验收参考图缺失");
        var model = models.driverConnection(organizationId)
                .orElseThrow(() -> unavailable("IDENTITY_MODEL_UNCONFIGURED",false,false,"模特详情图验收需要配置视觉模型"));
        var binding = Map.<String, Object>of("contractVersion", CONTRACT_VERSION, "reviewPolicyVersion", REVIEW_POLICY_VERSION,
                "productSha256", CommerceImageArchive.sha(product), "styleSha256", CommerceImageArchive.sha(style),
                "outputSha256", CommerceImageArchive.sha(outputImage), "modelFingerprint", modelFingerprint(model),
                "organizationSha256", CommerceImageArchive.sha(organizationId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // Stable key order is independent of Map iteration and survives runtime restarts.
        String key = CommerceImageArchive.sha(json.writeValueAsBytes(new java.util.TreeMap<>(binding)));
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            Path path = cache.resolve(key + ".json");
            Map<String, Object> saved = cached(path, binding);
            if (saved != null) return saved;
            byte[] sheet = contactSheet(product, style, outputImage);
            Map<String, Object> report = analyze(model, sheet, binding);
            if (Boolean.TRUE.equals(report.get("passed"))) store(path, report);
            return report;
        }
    }

    private Map<String, Object> analyze(ModelConfigService.RuntimeModelConnection model,
            byte[] sheet, Map<String, Object> binding) {
        var context = Map.of("analysisType", CONTRACT_VERSION, "reviewPolicyVersion", REVIEW_POLICY_VERSION, "panels", List.of(
                Map.of("index", 1, "role", "PRODUCT"),
                Map.of("index", 2, "role", "POSE"),
                Map.of("index", 3, "role", "OUTPUT")));
        var payload = Map.of("model", model.driverRequestModel(), "context", context,
                "image", Map.of("mimeType", "image/jpeg", "data", Base64.getEncoder().encodeToString(sheet)));
        Map<?, ?> response;
        try {
            response = vision.post().uri("/v1/product/analyze").contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().body(Map.class);
        } catch (ResourceAccessException failure) {
            throw unavailable("IDENTITY_NETWORK",true,false,"详情商品检查连接中断或超时，稍后自动继续；图片保留");
        } catch (RestClientResponseException failure) { throw responseFailure(failure); }
        catch (RuntimeException failure) {
            throw unavailable("IDENTITY_RESPONSE_INVALID",false,true,"详情商品检查返回格式无效；图片保留");
        }
        if (response == null || !(response.get("result") instanceof Map<?, ?> result))
            throw unavailable("IDENTITY_RESPONSE_INVALID",false,true,"模特详情图验收未返回有效结果；已保存详情图，不追加生图");
        var report = new LinkedHashMap<String, Object>();
        boolean passed = true;
        for (String field : CHECKS) {
            if (!(result.get(field) instanceof Boolean value))
                throw unavailable("IDENTITY_RESPONSE_INVALID",false,true,"模特详情图验收结果不完整；已保存详情图，不追加生图");
            report.put(field, value);
            passed &= value;
        }
        if (!(result.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 300)
            throw unavailable("IDENTITY_RESPONSE_INVALID",false,true,"模特详情图验收缺少有效图像依据；已保存详情图，不追加生图");
        report.put("passed", passed);
        report.put("reason", reason.strip());
        report.putAll(binding);
        return Map.copyOf(report);
    }

    private Map<String, Object> cached(Path path, Map<String, Object> binding) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16000)
                throw unavailable("IDENTITY_CACHE_INVALID",false,false,"模特详情图验收缓存无法读取；保留详情图，不追加生图");
            var raw = json.readValue(Files.readString(path), Map.class);
            if (raw == null || !Boolean.TRUE.equals(raw.get("passed"))
                    || CHECKS.stream().anyMatch(field -> !Boolean.TRUE.equals(raw.get(field)))
                    || !(raw.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 300
                    || binding.entrySet().stream().anyMatch(entry -> !Objects.equals(raw.get(entry.getKey()), entry.getValue())))
                throw unavailable("IDENTITY_CACHE_INVALID",false,false,"模特详情图验收缓存与输入不一致；保留详情图，不追加生图");
            var report = new LinkedHashMap<String, Object>(binding);
            report.put("passed", true);
            report.put("reason", reason);
            CHECKS.forEach(field -> report.put(field, true));
            return Map.copyOf(report);
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            throw unavailable("IDENTITY_CACHE_INVALID",false,false,"模特详情图验收缓存无法读取；保留详情图，不追加生图");
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
            throw unavailable("IDENTITY_CACHE_WRITE_UNAVAILABLE",true,false,"模特详情图验收结果暂未保存，请重试验收；保留详情图，不追加生图");
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
    static byte[] contactSheet(byte[] product, byte[] style, byte[] outputImage) {
        var inputs = List.of(CommerceMockupComposer.decode(product), CommerceMockupComposer.decode(style),
                CommerceMockupComposer.decode(outputImage));
        var sheet = new BufferedImage(PANEL_SIZE * 3, PANEL_SIZE + LABEL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        try {
            graphics.setColor(new Color(236, 236, 236));
            graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 23));
            var labels = List.of("1 PRODUCT / garment + print", "2 POSE / body position only", "3 OUTPUT / model wearing");
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
            throw new IllegalArgumentException("模特详情图验收参考图无法读取");
        } finally {
            writer.dispose();
        }
    }

    private ReviewUnavailableException responseFailure(RestClientResponseException http) {
        String code="";Boolean retryable=null;
        try {var body=json.readTree(http.getResponseBodyAsString());code=body.path("failureCode").asText("");if(body.path("retryable").isBoolean())retryable=body.path("retryable").asBoolean();}
        catch(RuntimeException ignored){}
        if(Set.of("COMMERCE_MODEL_DETAIL_INVALID_SCHEMA","CHAT_INVALID_RESPONSE","CHAT_EMPTY_RESPONSE","CHAT_INCOMPLETE_RESPONSE","CHAT_OUTPUT_TRUNCATED","CHAT_INVALID_TOOL_CALL").contains(code))
            return unavailable(code,false,true,"详情商品检查返回结果无效，保留全部图片");
        if(Set.of("COMMERCE_MODEL_DETAIL_INVALID_MODEL","COMMERCE_MODEL_DETAIL_INVALID_IMAGE","CHAT_INVALID_CONFIGURATION","CHAT_AUTHENTICATION_FAILED","CHAT_MODEL_UNAVAILABLE","CHAT_PARAMETERS_REJECTED","CHAT_VISION_UNSUPPORTED","CHAT_STREAM_REQUIRED","CHAT_CONTENT_FILTERED").contains(code))
            return unavailable(code,false,false,"详情商品检查模型配置、访问权限或输入要求不满足，请检查模型接入");
        int status=http.getStatusCode().value();boolean temporary=Boolean.TRUE.equals(retryable)||(!Boolean.FALSE.equals(retryable)&&(status==408||status==429||status>=500));
        String reason=Set.of("CHAT_TIMEOUT","CHAT_NETWORK_INTERRUPTED","CHAT_RATE_LIMITED","CHAT_UPSTREAM_FAILED").contains(code)?code:"IDENTITY_HTTP_"+status;
        return unavailable(reason,temporary,false,temporary?"详情商品检查连接暂不可用，稍后自动继续":"详情商品检查请求被拒绝，请检查模型接入");
    }
    private static ReviewUnavailableException unavailable(String code,boolean retryable,boolean invalid,String message) {
        return new ReviewUnavailableException(code,retryable,invalid,message);
    }
    /** Transport availability never represents a negative garment identity verdict. */
    public static class ReviewUnavailableException extends IllegalStateException {
        private final String reasonCode;private final boolean retryable,invalidResponse;
        public ReviewUnavailableException(String message){this("IDENTITY_UNAVAILABLE",true,false,message);}
        public ReviewUnavailableException(String code,boolean retryable,boolean invalid,String message){
            super(message);this.reasonCode=code!=null&&code.matches("[A-Z_0-9]{3,80}")?code:"IDENTITY_UNAVAILABLE";this.retryable=retryable;this.invalidResponse=invalid;
        }
        public String reasonCode(){return reasonCode;}public boolean retryable(){return retryable;}public boolean invalidResponse(){return invalidResponse;}
    }
}
