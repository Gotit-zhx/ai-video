package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductFunctionAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ProductFunctionAnalysisService.class);
    private static final String BLOCK_START = "【功能商品物理约束】";
    private static final String BLOCK_END = "【功能商品物理约束结束】";
    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;

    private final AiVideoTaskItemMapper itemMapper;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;
    private final RestClient piClient;

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
    }

    /**
     * Analyze a pure-product item once. Failure is deliberately non-fatal: image generation can still
     * continue with the existing visual-only constraints when the driver model is unavailable.
     */
    public String ensureAnalysis(AiVideoTask task, AiVideoTaskItem item) {
        if ("MODEL_WEAR".equals(task.getGenerationMode())
                || !isDailyUseCategory(task.getProductCategory())) return null;
        if (item.getProductFunctionAnalysisJson() != null
                && !item.getProductFunctionAnalysisJson().isBlank()) {
            return item.getProductFunctionAnalysisJson();
        }
        try {
            ModelConfigService.RuntimeModelConnection model = modelConfigService
                    .driverConnection(task.getOrganizationId()).orElse(null);
            if (model == null) {
                log.warn("Product function analysis skipped for item {}: no active driver model", item.getId());
                return null;
            }
            Path source = Path.of(item.getSourceFilePath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source) || Files.size(source) <= 0 || Files.size(source) > MAX_SOURCE_BYTES) {
                log.warn("Product function analysis skipped for item {}: source image is unavailable", item.getId());
                return null;
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", Map.of(
                    "baseUrl", model.baseUrl(),
                    "modelId", model.modelId(),
                    "apiFormat", model.apiFormat(),
                    "apiKey", model.apiKey()
            ));
            request.put("context", Map.of(
                    "productCategory", "日用品",
                    "scriptActionContext", functionalActionContext(task.getScriptJson())
            ));
            request.put("image", Map.of(
                    "mimeType", item.getSourceContentType(),
                    "data", Base64.getEncoder().encodeToString(Files.readAllBytes(source))
            ));
            PiProductAnalysisResponse response = piClient.post().uri("/v1/product/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PiProductAnalysisResponse.class);
            if (response == null || response.result() == null) return null;
            String json = normalizeAnalysis(response.result());
            item.updateProductFunctionAnalysis(json);
            item.updateVideoPrompt(applyToVideoPrompt(task, item.getVideoPromptText(), json));
            itemMapper.updateById(item);
            return json;
        } catch (Exception exception) {
            log.warn("Product function analysis unavailable for item {}: {}",
                    item.getId(), readable(exception));
            return null;
        }
    }

    public String promptBlock(AiVideoTask task, String analysisJson) {
        if (!isDailyUseCategory(task.getProductCategory())) return "";
        if (analysisJson == null || analysisJson.isBlank()) return "";
        try {
            Map<String, Object> value = objectMapper.readValue(analysisJson, new TypeReference<>() {});
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

    private String normalizeAnalysis(Map<String, Object> source) throws Exception {
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
        int start = value.indexOf(BLOCK_START);
        if (start < 0) return value.trim();
        int end = value.indexOf(BLOCK_END, start);
        if (end < 0) return value.substring(0, start).trim();
        return (value.substring(0, start) + value.substring(end + BLOCK_END.length()))
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

    private String readable(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record PiProductAnalysisResponse(Map<String, Object> result, String modelId, long latencyMs) {}
}
