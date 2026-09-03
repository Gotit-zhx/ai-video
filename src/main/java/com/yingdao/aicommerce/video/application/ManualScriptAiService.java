package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ManualScriptAiService {
    private static final int SHOT_COUNT = 9;
    private static final BigDecimal TOTAL_SECONDS = new BigDecimal("15.0");
    private static final int MAX_PRODUCT_IMAGE_COUNT = 6;
    private static final long MAX_PRODUCT_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_PRODUCT_IMAGES_TOTAL_BYTES = 60L * 1024 * 1024;
    private static final Pattern SPEECH_PREFIX = Pattern.compile("^(对白|旁白|口播)[：:]\\s*");

    private final ModelConfigService modelConfigService;
    private final RestClient piClient;

    public ManualScriptAiService(ModelConfigService modelConfigService,
                                 @Value("${app.pi-runtime-url}") String piRuntimeUrl,
                                 @Value("${app.pi-runtime-token}") String piRuntimeToken) {
        this.modelConfigService = modelConfigService;
        this.piClient = RestClient.builder().baseUrl(piRuntimeUrl)
                .defaultHeader("X-Internal-Token", piRuntimeToken).build();
    }

    public Draft analyze(String prompt, String targetMarket, String existingName,
                         String existingVisualTone, String presentationMode,
                         PlatformPrincipal principal) {
        String requirement = text(prompt, 12000);
        if (requirement.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请先粘贴业务人员的完整提示词");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("analysisMode", "BUSINESS_PROMPT_TO_SCRIPT");
        context.put("businessPrompt", requirement);
        context.put("targetMarket", text(targetMarket, 80));
        context.put("existingName", text(existingName, 160));
        context.put("existingVisualTone", text(existingVisualTone, 4000));
        context.put("presentationMode", normalizePresentationMode(presentationMode));
        context.put("shotCount", SHOT_COUNT);
        context.put("totalDurationSeconds", 15);
        return callModel(context, null, existingName, existingVisualTone, principal);
    }

    public Draft analyzeProduct(List<MultipartFile> images, List<String> imageContexts,
                                String requirements, String productContext, String targetMarket,
                                String existingName, String existingVisualTone, String presentationMode,
                                PlatformPrincipal principal) {
        validateProductImages(images, imageContexts);
        String normalizedProductContext = text(productContext, 4000);
        if (normalizedProductContext.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "请填写参考图如何组合以及整体脚本重点");
        }
        List<Map<String, String>> imageGuide = new ArrayList<>(images.size());
        for (int index = 0; index < images.size(); index++) {
            imageGuide.add(Map.of(
                    "label", imageLabel(index),
                    "description", text(imageContexts.get(index), 1200)
            ));
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("analysisMode", "PRODUCT_IMAGES_TO_SCRIPT");
        context.put("businessPrompt", text(requirements, 12000));
        context.put("userProductContext", normalizedProductContext);
        context.put("imageGuide", imageGuide);
        context.put("targetMarketHint", text(targetMarket, 80));
        context.put("existingName", text(existingName, 160));
        context.put("existingVisualTone", text(existingVisualTone, 4000));
        context.put("presentationMode", normalizePresentationMode(presentationMode));
        context.put("shotCount", SHOT_COUNT);
        context.put("totalDurationSeconds", 15);
        context.put("instruction", "按图一、图二的顺序逐张识别，并严格结合 imageGuide 中用户指定的内容和用途；多张图可以分别承担商品、细节、使用场景、动作或效果参考。脚本重点表现用户指定的真实使用方式和效果；图片只用于识别产品与功能，不得把商品颜色、形状、标签或包装装饰写进可复用脚本；不得虚构剂量、参数或未提供的功效数字");
        try {
            List<Map<String, Object>> productImages = new ArrayList<>(images.size());
            for (int index = 0; index < images.size(); index++) {
                MultipartFile image = images.get(index);
                productImages.add(Map.of(
                        "label", imageLabel(index),
                        "description", text(imageContexts.get(index), 1200),
                        "mimeType", String.valueOf(image.getContentType()),
                        "data", Base64.getEncoder().encodeToString(image.getBytes())
                ));
            }
            return callModel(context, productImages, existingName, existingVisualTone, principal);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "参考图片读取失败");
        }
    }

    private Draft callModel(Map<String, Object> context, List<Map<String, Object>> productImages,
                            String existingName, String existingVisualTone, PlatformPrincipal principal) {
        ModelConfigService.RuntimeModelConnection model = modelConfigService
                .driverConnection(principal.organizationId())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "请先在模型接入中配置并启用驱动模型"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", Map.of("baseUrl", model.baseUrl(), "modelId", model.modelId(),
                "apiFormat", model.apiFormat(), "apiKey", model.apiKey()));
        request.put("context", context);
        if (productImages != null && !productImages.isEmpty()) request.put("images", productImages);
        try {
            PiResponse response = piClient.post().uri("/v1/video-script/manual-analyze")
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(PiResponse.class);
            if (response == null || response.result() == null) {
                throw new IllegalStateException("AI 没有返回可用的分镜内容");
            }
            return normalize(response.result(), existingName, existingVisualTone);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "AI 分析失败：" + readable(exception));
        }
    }

    private void validateProductImages(List<MultipartFile> images, List<String> imageContexts) {
        if (images == null || images.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请先添加至少一张参考图片");
        }
        if (images.size() > MAX_PRODUCT_IMAGE_COUNT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "参考图片最多支持 6 张");
        }
        if (imageContexts == null || imageContexts.size() != images.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "每张参考图都必须有对应的内容和用途说明");
        }
        long totalBytes = 0;
        for (int index = 0; index < images.size(); index++) {
            MultipartFile image = images.get(index);
            if (image == null || image.isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, imageLabel(index) + "内容为空");
            }
            if (image.getSize() > MAX_PRODUCT_IMAGE_BYTES) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, imageLabel(index) + "不能超过 20MB");
            }
            totalBytes += image.getSize();
            String mimeType = image.getContentType() == null ? "" : image.getContentType().toLowerCase();
            if (!List.of("image/jpeg", "image/png", "image/webp").contains(mimeType)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, imageLabel(index) + "仅支持 JPG、PNG 或 WebP");
            }
            if (text(imageContexts.get(index), 1200).isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "请填写" + imageLabel(index) + "是什么、在脚本中用于什么");
            }
        }
        if (totalBytes > MAX_PRODUCT_IMAGES_TOTAL_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "所有参考图合计不能超过 60MB");
        }
    }

    private String imageLabel(int index) {
        return "图" + List.of("一", "二", "三", "四", "五", "六").get(index);
    }

    private String normalizePresentationMode(String value) {
        String normalized = text(value, 32).toUpperCase();
        if (!List.of("MODEL_WEAR", "PRODUCT_ONLY", "CAST_PRODUCT").contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请选择人物穿搭、纯商品或人物＋商品脚本");
        }
        return normalized;
    }

    private Draft normalize(Map<String, Object> result, String existingName, String existingVisualTone) {
        Object rawShots = result.get("shots");
        if (!(rawShots instanceof List<?> values) || values.size() != SHOT_COUNT) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "AI 返回的分镜数量不是 9 个，请重试");
        }
        List<ShotDraft> shots = new ArrayList<>(SHOT_COUNT);
        for (int index = 0; index < SHOT_COUNT; index++) {
            if (!(values.get(index) instanceof Map<?, ?> shot)) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "AI 返回的第 " + (index + 1) + " 个分镜格式无效");
            }
            String title = text(shot.get("title"), 160);
            String scene = text(shot.get("sceneDescription"), 8000);
            if (title.isBlank() || scene.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "AI 返回的第 " + (index + 1) + " 个分镜内容不完整");
            }
            String motion = text(shot.get("motionDescription"), 8000);
            shots.add(new ShotDraft(index + 1, defaultStage(text(shot.get("stage"), 20), index), title,
                    boundary(index), boundary(index + 1), scene, motion.isBlank() ? scene : motion,
                    normalizeOffCameraNarration(text(shot.get("narration"), 8000))));
        }
        String name = text(result.get("name"), 160);
        String tone = text(result.get("visualTone"), 4000);
        if (name.isBlank()) name = text(existingName, 160);
        if (tone.isBlank()) tone = text(existingVisualTone, 4000);
        if (name.isBlank()) name = "AI 生成的 15 秒带货脚本";
        if (tone.isBlank()) tone = "真实商业摄影｜自然光影｜画面清晰｜节奏紧凑";
        String detectedLanguage = text(result.get("detectedLanguage"), 80);
        String detectedTargetMarket = text(result.get("detectedTargetMarket"), 80);
        return new Draft(name, tone, TOTAL_SECONDS, detectedLanguage, detectedTargetMarket, shots);
    }

    static BigDecimal boundary(int index) {
        return TOTAL_SECONDS.multiply(BigDecimal.valueOf(index))
                .divide(BigDecimal.valueOf(SHOT_COUNT), 1, RoundingMode.HALF_UP);
    }

    static String normalizeOffCameraNarration(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) return "";
        normalized = SPEECH_PREFIX.matcher(normalized).replaceFirst("").trim();
        return normalized.isBlank() ? "" : "旁白：" + normalized;
    }

    private String defaultStage(String requested, int index) {
        String normalized = requested.toUpperCase();
        if (List.of("HOOK", "SOLUTION", "TRUST", "CTA", "CONTENT").contains(normalized)) return normalized;
        return index < 3 ? "HOOK" : index < 6 ? "SOLUTION" : index < 8 ? "TRUST" : "CTA";
    }

    private String text(Object value, int maxLength) {
        if (value == null) return "";
        String normalized = String.valueOf(value).replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String readable(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public record Draft(String name, String visualTone, BigDecimal durationSeconds,
                        String detectedLanguage, String detectedTargetMarket, List<ShotDraft> shots) {}
    public record ShotDraft(int sequence, String stage, String title, BigDecimal startSecond,
                            BigDecimal endSecond, String sceneDescription, String motionDescription,
                            String narration) {}
    private record PiResponse(Map<String, Object> result, String modelId, long latencyMs) {}
}
