package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Service
public class ProductFunctionAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ProductFunctionAnalysisService.class);
    private static final String BLOCK_START = "【功能商品物理约束】";
    private static final String BLOCK_END = "【功能商品物理约束结束】";
    private static final String GARMENT_BLOCK_START = "【服装结构与穿法契约】";
    private static final String GARMENT_BLOCK_END = "【服装结构与穿法契约结束】";
    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    private static final int VALIDATION_MAX_EDGE = 1280;
    private static final int MAX_CONCURRENT_VALIDATIONS = 3;
    private static final String GARMENT_TOPOLOGY_VERSION = "GARMENT_TOPOLOGY_V2";

    private final AiVideoTaskItemMapper itemMapper;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;
    private final RestClient piClient;
    private final RestClient piValidationClient;
    private final Semaphore validationSlots = new Semaphore(MAX_CONCURRENT_VALIDATIONS, true);

    public ProductFunctionAnalysisService(AiVideoTaskItemMapper itemMapper,
                                          ModelConfigService modelConfigService,
                                          ObjectMapper objectMapper,
                                          @Value("${app.pi-runtime-url}") String piRuntimeUrl,
                                          @Value("${app.pi-runtime-token}") String piRuntimeToken) {
        this.itemMapper = itemMapper;
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
        this.piClient = RestClient.builder().baseUrl(piRuntimeUrl)
                .defaultHeader("X-Internal-Token", piRuntimeToken).build();
        SimpleClientHttpRequestFactory validationRequestFactory = new SimpleClientHttpRequestFactory();
        validationRequestFactory.setConnectTimeout(5_000);
        validationRequestFactory.setReadTimeout(190_000);
        this.piValidationClient = RestClient.builder().baseUrl(piRuntimeUrl)
                .requestFactory(validationRequestFactory)
                .defaultHeader("X-Internal-Token", piRuntimeToken).build();
    }

    /** Analyze a product once. Garments fail closed because a missing topology contract corrupts every later frame. */
    public String ensureAnalysis(AiVideoTask task, AiVideoTaskItem item) {
        boolean garment = isGarmentTask(task);
        if (!garment && !isDailyUseCategory(task.getProductCategory())) return null;
        if (item.getProductFunctionAnalysisJson() != null
                && !item.getProductFunctionAnalysisJson().isBlank()
                && (!garment || hasGarmentTopologyVersion(
                        item.getProductFunctionAnalysisJson(), GARMENT_TOPOLOGY_VERSION))) {
            return item.getProductFunctionAnalysisJson();
        }
        Exception lastFailure = null;
        int attempts = garment ? 3 : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) try {
            ModelConfigService.RuntimeModelConnection model = modelConfigService
                    .driverConnection(task.getOrganizationId()).orElse(null);
            if (model == null) {
                throw new IllegalStateException("没有可用的结构识别模型");
            }
            Path source = Path.of(item.getSourceFilePath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source) || Files.size(source) <= 0 || Files.size(source) > MAX_SOURCE_BYTES) {
                throw new IllegalStateException("商品原图不可用于结构识别");
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", Map.of(
                    "baseUrl", model.baseUrl(),
                    "modelId", model.modelId(),
                    "apiFormat", model.apiFormat(),
                    "apiKey", model.apiKey()
            ));
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("analysisType", garment ? GARMENT_TOPOLOGY_VERSION : "PRODUCT_FUNCTION_V1");
            context.put("productCategory", garment ? "服装" : "日用品");
            if (!garment) {
                context.put("itemName", safe(item.getName(), 100));
                context.put("scriptActionContext", functionalActionContext(task.getScriptJson()));
            }
            request.put("context", context);
            request.put("image", Map.of(
                    "mimeType", item.getSourceContentType(),
                    "data", Base64.getEncoder().encodeToString(Files.readAllBytes(source))
            ));
            PiProductAnalysisResponse response = piClient.post().uri("/v1/product/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PiProductAnalysisResponse.class);
            if (response == null || response.result() == null) {
                throw new IllegalStateException("结构识别模型没有返回结果");
            }
            String json = normalizeAnalysis(response.result(), garment);
            item.updateProductFunctionAnalysis(json);
            item.updateVideoPrompt(applyToVideoPrompt(task, item.getVideoPromptText(), json));
            itemMapper.updateById(item);
            return json;
        } catch (Exception exception) {
            lastFailure = exception;
            log.warn("Product function analysis unavailable for item {}: {}",
                    item.getId(), readable(exception));
            if (attempt < attempts) {
                try {
                    Thread.sleep(750L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("服装结构识别被中断", interrupted);
                }
            }
        }
        if (garment) {
            throw new IllegalStateException("服装结构识别暂时失败，已阻止无结构契约的错误生图："
                    + readable(lastFailure == null ? new IllegalStateException("未知错误") : lastFailure), lastFailure);
        }
        return null;
    }

    public GarmentValidation validateGenerated(AiVideoTask task, AiVideoTaskItem item,
                                                String assetType, byte[] candidateBytes,
                                                String candidateMimeType) {
        if (!isGarmentTask(task) || !requiresTopologyValidation(item.getProductFunctionAnalysisJson())) {
            return new GarmentValidation(true, "", List.of(), 1.0);
        }
        boolean validationSlotAcquired = false;
        try {
            validationSlots.acquire();
            validationSlotAcquired = true;
            ModelConfigService.RuntimeModelConnection model = modelConfigService
                    .driverConnection(task.getOrganizationId())
                    .orElseThrow(() -> new IllegalStateException("没有可用的结构复核模型"));
            Path source = Path.of(item.getSourceFilePath()).toAbsolutePath().normalize();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", Map.of("baseUrl", model.baseUrl(), "modelId", model.modelId(),
                    "apiFormat", model.apiFormat(), "apiKey", model.apiKey()));
            request.put("context", Map.of(
                    "analysisType", "GARMENT_TOPOLOGY_VALIDATION_V1",
                    "assetType", assetType,
                    "expectedTopology", objectMapper.readValue(item.getProductFunctionAnalysisJson(), Map.class),
                    "framingContract", Map.of(
                            "personFraming", "头顶至大腿中下段、膝盖上方的中近景",
                            "minimumFaceHeightRatio", 0.15,
                            "forbidden", List.of("远景", "全景", "全身", "膝盖以下入镜", "双脚或鞋子入镜", "人物突然缩小", "镜头拉远"),
                            "productOnlyFraming", "纯商品格必须采用商品近景并让主体填满主要画面"
                    )
            ));
            ValidationImage sourceImage = compactForValidation(
                    Files.readAllBytes(source), item.getSourceContentType());
            ValidationImage candidateImage = compactForValidation(candidateBytes, candidateMimeType);
            request.put("image", Map.of("mimeType", sourceImage.mimeType(),
                    "data", Base64.getEncoder().encodeToString(sourceImage.bytes())));
            request.put("candidateImage", Map.of("mimeType", candidateImage.mimeType(),
                    "data", Base64.getEncoder().encodeToString(candidateImage.bytes())));
            PiProductAnalysisResponse response = piValidationClient.post().uri("/v1/product/analyze")
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
                    .body(PiProductAnalysisResponse.class);
            if (response == null || response.result() == null) {
                throw new IllegalStateException("结构复核模型没有返回结果");
            }
            Map<String, Object> value = response.result();
            boolean valid = Boolean.TRUE.equals(value.get("valid"));
            List<String> violations = stringList(value.get("violations"), 12, 180);
            int violationCount = validationViolationCount(value.get("violationCount"), valid, violations);
            return new GarmentValidation(valid, text(value.get("correctionPrompt"), 600),
                    violations,
                    value.get("confidence") instanceof Number number ? number.doubleValue() : 0.0,
                    violationCount);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Garment validation unavailable for item {}; the private candidate will be rechecked: {}",
                    item.getId(), readable(exception));
            throw new IllegalStateException("服装结构复核暂时失败，已私有保存本次候选图并将在后台继续复核："
                    + readable(exception), exception);
        } finally {
            if (validationSlotAcquired) validationSlots.release();
        }
    }

    static boolean isTransientValidationFailure(String message) {
        if (message == null || message.isBlank()) return false;
        String value = message.toLowerCase();
        return value.contains("stream ended without finish_reason")
                || value.contains("stream ended") || value.contains("unexpected end")
                || value.contains("connection reset") || value.contains("connection closed")
                || value.contains("eof") || value.contains("timeout") || value.contains("timed out")
                || value.contains("temporarily unavailable") || value.contains("没有返回结果")
                || value.contains("502") || value.contains("503") || value.contains("504");
    }

    static ValidationImage compactForValidation(byte[] sourceBytes, String sourceMimeType) {
        if (sourceBytes == null || sourceBytes.length == 0) {
            return new ValidationImage(sourceMimeType, sourceBytes == null ? new byte[0] : sourceBytes);
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return new ValidationImage(sourceMimeType, sourceBytes);
            }
            double scale = Math.min(1.0, (double) VALIDATION_MAX_EDGE
                    / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage compact = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = compact.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(compact, "jpeg", output) || output.size() == 0) {
                return new ValidationImage(sourceMimeType, sourceBytes);
            }
            return new ValidationImage("image/jpeg", output.toByteArray());
        } catch (Exception ignored) {
            return new ValidationImage(sourceMimeType, sourceBytes);
        }
    }

    boolean requiresTopologyValidation(String analysisJson) {
        if (analysisJson == null || analysisJson.isBlank()) return false;
        try {
            Map<String, Object> value = objectMapper.readValue(analysisJson, new TypeReference<>() {});
            // Normal collars and sleeves can drift just as easily as special/asymmetric garments,
            // especially in the last storyboard cells. Every analyzed garment therefore needs the
            // same source-vs-candidate gate; the validator decides which visible structures matter.
            return text(value.get("version"), 40).startsWith("GARMENT_TOPOLOGY_V");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasGarmentTopologyVersion(String analysisJson, String expectedVersion) {
        try {
            Map<String, Object> value = objectMapper.readValue(analysisJson, new TypeReference<>() {});
            return expectedVersion.equals(text(value.get("version"), 40));
        } catch (Exception ignored) {
            return false;
        }
    }

    public String promptBlock(AiVideoTask task, String analysisJson) {
        if (analysisJson == null || analysisJson.isBlank()) return "";
        try {
            Map<String, Object> value = objectMapper.readValue(analysisJson, new TypeReference<>() {});
            if (text(value.get("version"), 40).startsWith("GARMENT_TOPOLOGY_V")) {
                if (!isGarmentTask(task)) return "";
                return garmentPromptBlock(value);
            }
            if (!isDailyUseCategory(task.getProductCategory())) return "";
            if (!Boolean.TRUE.equals(value.get("functional"))) return "";
            StringBuilder block = new StringBuilder(BLOCK_START).append('\n');
            append(block, "物品", text(value.get("productType"), 40));
            append(block, "功能依据", join(value.get("functionEvidence"), 70));
            append(block, "部件", join(value.get("keyComponents"), 65));
            append(block, "操作", join(value.get("operationSequence"), 85));
            append(block, "允许变化", join(value.get("allowedStateChanges"), 75));
            append(block, "固定", join(value.get("fixedStructures"), 70));
            append(block, "禁止", join(value.get("forbiddenChanges"), 85));
            block.append("仅改变操作影响的局部，其余结构、数量、颜色和位置不变。\n")
                    .append(BLOCK_END);
            return block.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public String applyToVideoPrompt(AiVideoTask task, String prompt, String analysisJson) {
        String base = removeBlock(prompt == null ? "" : prompt);
        String block = promptBlock(task, analysisJson);
        if (block.isBlank()) return base;
        String timeline = "【时间轴】";
        int index = base.indexOf(timeline);
        if (index >= 0) return base.substring(0, index) + block + "\n\n" + base.substring(index);
        return (base + "\n\n" + block).trim();
    }

    private String normalizeAnalysis(Map<String, Object> source, boolean garment) throws Exception {
        if (garment) return normalizeGarmentAnalysis(source);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("version", "PRODUCT_FUNCTION_V1");
        normalized.put("functional", Boolean.TRUE.equals(source.get("functional")));
        normalized.put("productType", text(source.get("productType"), 100));
        normalized.put("functionEvidence", stringList(source.get("functionEvidence"), 6, 120));
        normalized.put("keyComponents", stringList(source.get("keyComponents"), 8, 90));
        normalized.put("operationSequence", stringList(source.get("operationSequence"), 6, 120));
        normalized.put("allowedStateChanges", stringList(source.get("allowedStateChanges"), 6, 120));
        normalized.put("fixedStructures", stringList(source.get("fixedStructures"), 8, 120));
        normalized.put("forbiddenChanges", stringList(source.get("forbiddenChanges"), 10, 120));
        Object confidence = source.get("confidence");
        normalized.put("confidence", confidence instanceof Number number
                ? Math.max(0.0, Math.min(1.0, number.doubleValue())) : 0.0);
        return objectMapper.writeValueAsString(normalized);
    }

    private String normalizeGarmentAnalysis(Map<String, Object> source) throws Exception {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("version", GARMENT_TOPOLOGY_VERSION);
        normalized.put("garmentType", text(source.get("garmentType"), 100));
        normalized.put("topologyClass", text(source.get("topologyClass"), 100));
        if (source.get("asymmetrical") instanceof Boolean asymmetrical) {
            normalized.put("asymmetrical", asymmetrical);
        }
        normalized.put("structureSymmetry", text(source.get("structureSymmetry"), 100));
        normalized.put("wearingSidePolicy", text(source.get("wearingSidePolicy"), 100));
        normalized.put("imageLeftShoulderState", text(source.get("imageLeftShoulderState"), 100));
        normalized.put("imageRightShoulderState", text(source.get("imageRightShoulderState"), 100));
        normalized.put("wearerLeftShoulderState", text(source.get("wearerLeftShoulderState"), 100));
        normalized.put("wearerRightShoulderState", text(source.get("wearerRightShoulderState"), 100));
        normalized.put("imageLeftSleeve", text(source.get("imageLeftSleeve"), 100));
        normalized.put("imageRightSleeve", text(source.get("imageRightSleeve"), 100));
        normalized.put("wearerLeftSleeve", text(source.get("wearerLeftSleeve"), 100));
        normalized.put("wearerRightSleeve", text(source.get("wearerRightSleeve"), 100));
        Object visibleSleeveCount = source.get("visibleSleeveCount");
        if (visibleSleeveCount instanceof Number number) {
            normalized.put("visibleSleeveCount", Math.max(0, Math.min(4, number.intValue())));
        }
        normalized.put("openingInventory", stringList(source.get("openingInventory"), 8, 140));
        normalized.put("seamConnections", stringList(source.get("seamConnections"), 10, 140));
        normalized.put("necklinePath", text(source.get("necklinePath"), 220));
        normalized.put("supportElements", stringList(source.get("supportElements"), 8, 140));
        normalized.put("viewpointProjectionRule", text(source.get("viewpointProjectionRule"), 240));
        normalized.put("wearingMethod", stringList(source.get("wearingMethod"), 6, 140));
        normalized.put("fixedStructures", stringList(source.get("fixedStructures"), 10, 140));
        normalized.put("forbiddenChanges", stringList(source.get("forbiddenChanges"), 12, 140));
        normalized.put("visualEvidence", stringList(source.get("visualEvidence"), 8, 140));
        normalized.put("uncertainties", stringList(source.get("uncertainties"), 6, 140));
        Object confidence = source.get("confidence");
        normalized.put("confidence", confidence instanceof Number number
                ? Math.max(0.0, Math.min(1.0, number.doubleValue())) : 0.0);
        return objectMapper.writeValueAsString(normalized);
    }

    private String garmentPromptBlock(Map<String, Object> value) {
        StringBuilder block = new StringBuilder(GARMENT_BLOCK_START).append('\n');
        block.append("原图可见像素是最高真值；以下内容只是按原图整理的结构证据，若有冲突以原图为准。\n");
        append(block, "原图画面左侧肩部", text(value.get("imageLeftShoulderState"), 60));
        append(block, "原图画面右侧肩部", text(value.get("imageRightShoulderState"), 60));
        append(block, "原图画面左侧袖", text(value.get("imageLeftSleeve"), 60));
        append(block, "原图画面右侧袖", text(value.get("imageRightSleeve"), 60));
        append(block, "原图可见袖子总数", text(value.get("visibleSleeveCount"), 20));
        append(block, "开口清单", join(value.get("openingInventory"), 220));
        append(block, "缝线连接清单", join(value.get("seamConnections"), 260));
        append(block, "领口路径", text(value.get("necklinePath"), 180));
        append(block, "肩带与支撑", join(value.get("supportElements"), 180));
        append(block, "像素证据", join(value.get("visualEvidence"), 240));
        append(block, "穿着者左肩", text(value.get("wearerLeftShoulderState"), 60));
        append(block, "穿着者右肩", text(value.get("wearerRightShoulderState"), 60));
        append(block, "穿着者左袖", text(value.get("wearerLeftSleeve"), 60));
        append(block, "穿着者右袖", text(value.get("wearerRightSleeve"), 60));
        append(block, "穿着侧约束", text(value.get("wearingSidePolicy"), 100));
        append(block, "视角投影", text(value.get("viewpointProjectionRule"), 200));
        append(block, "实际穿法", join(value.get("wearingMethod"), 220));
        append(block, "固定结构", join(value.get("fixedStructures"), 260));
        append(block, "禁止变化", join(value.get("forbiddenChanges"), 300));
        block.append("所有出镜状态只复现上述当前商品证据；不要重新分类，也不要套用其他款式的通用规则。\n")
                .append(GARMENT_BLOCK_END);
        return block.toString();
    }

    @SuppressWarnings("unchecked")
    String functionalActionContext(String scriptJson) {
        if (scriptJson == null || scriptJson.isBlank()) return "";
        try {
            Map<String, Object> root = objectMapper.readValue(scriptJson, Map.class);
            Object rawShots = root.get("shots");
            if (!(rawShots instanceof List<?> shots)) return "";
            List<Map<String, String>> actions = new ArrayList<>();
            for (Object raw : shots) {
                if (!(raw instanceof Map<?, ?> shot)) continue;
                Map<String, String> action = new LinkedHashMap<>();
                action.put("sequence", text(shot.get("sequence"), 4));
                action.put("title", text(shot.get("title"), 100));
                action.put("scene", text(shot.get("sceneDescription"), 240));
                action.put("motion", text(shot.get("motionDescription"), 240));
                action.put("speech", text(shot.get("narration"), 180));
                actions.add(action);
            }
            return objectMapper.writeValueAsString(actions);
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean isDailyUseCategory(String value) {
        return value != null && "日用品".equals(value.trim());
    }

    static boolean isGarmentCategory(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase();
        return List.of("服装", "服饰", "上衣", "女装", "男装", "童装", "t恤", "t-shirt", "shirt",
                        "衬衫", "裙", "裤", "外套", "毛衣", "卫衣", "背心", "内衣")
                .stream().anyMatch(normalized::contains);
    }

    boolean supportsAnalysis(AiVideoTask task) {
        return isGarmentTask(task) || isDailyUseCategory(task.getProductCategory());
    }

    private boolean isGarmentTask(AiVideoTask task) {
        return "MODEL_WEAR".equals(task.getGenerationMode())
                || isGarmentCategory(task.getProductCategory());
    }

    private List<String> stringList(Object raw, int maxItems, int maxLength) {
        if (!(raw instanceof List<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String text = text(value, maxLength);
            if (!text.isBlank() && !result.contains(text)) result.add(text);
            if (result.size() >= maxItems) break;
        }
        return result;
    }

    private String join(Object raw, int maxLength) {
        if (!(raw instanceof List<?> values)) return "";
        String joined = values.stream().map(value -> text(value, 100))
                .filter(value -> !value.isBlank()).reduce((left, right) -> left + "；" + right).orElse("");
        return joined.length() <= maxLength ? joined : joined.substring(0, maxLength);
    }

    private void append(StringBuilder target, String label, String value) {
        if (!value.isBlank()) target.append(label).append('：').append(value).append('。').append('\n');
    }

    private String removeBlock(String value) {
        return removeOneBlock(removeOneBlock(value, BLOCK_START, BLOCK_END),
                GARMENT_BLOCK_START, GARMENT_BLOCK_END).trim();
    }

    private String removeOneBlock(String value, String blockStart, String blockEnd) {
        int start = value.indexOf(blockStart);
        if (start < 0) return value.trim();
        int end = value.indexOf(blockEnd, start);
        if (end < 0) return value.substring(0, start).trim();
        return (value.substring(0, start) + value.substring(end + blockEnd.length()))
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String text(Object value, int maxLength) {
        if (value == null) return "";
        String normalized = String.valueOf(value).replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String safe(String value, int maxLength) {
        return text(value, maxLength);
    }

    static int validationViolationCount(Object rawCount, boolean valid, List<String> violations) {
        if (valid) return 0;
        if (rawCount instanceof Number number) {
            long count = number.longValue();
            if (count > 0) {
                long listedCount = violations == null ? 0 : violations.size();
                return (int) Math.min(Integer.MAX_VALUE, Math.max(count, listedCount));
            }
        }
        return violations == null || violations.isEmpty() ? Integer.MAX_VALUE : violations.size();
    }

    private String readable(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record PiProductAnalysisResponse(Map<String, Object> result, String modelId, long latencyMs) {}

    public record GarmentValidation(boolean valid, String correctionPrompt,
                                    List<String> violations, double confidence, int violationCount) {
        public GarmentValidation(boolean valid, String correctionPrompt,
                                 List<String> violations, double confidence) {
            this(valid, correctionPrompt, violations, confidence,
                    validationViolationCount(null, valid, violations));
        }
    }

    record ValidationImage(String mimeType, byte[] bytes) {}
}
