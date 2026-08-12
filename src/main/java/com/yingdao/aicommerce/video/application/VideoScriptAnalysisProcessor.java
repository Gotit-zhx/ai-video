package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.domain.VideoScriptShot;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VideoScriptAnalysisProcessor {

    private static final Logger log = LoggerFactory.getLogger(VideoScriptAnalysisProcessor.class);
    private static final int REQUIRED_SHOT_COUNT = 9;

    private final VideoScriptMapper scriptMapper;
    private final VideoScriptShotMapper shotMapper;
    private final ModelConfigService modelConfigService;
    private final VideoFrameExtractor frameExtractor;
    private final VideoCameraMotionAnalyzer cameraMotionAnalyzer;
    private final ObjectMapper objectMapper;
    private final RestClient piClient;
    private final TransactionTemplate transactionTemplate;

    public VideoScriptAnalysisProcessor(
            VideoScriptMapper scriptMapper,
            VideoScriptShotMapper shotMapper,
            ModelConfigService modelConfigService,
            VideoFrameExtractor frameExtractor,
            VideoCameraMotionAnalyzer cameraMotionAnalyzer,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            @Value("${app.pi-runtime-url}") String piRuntimeUrl,
            @Value("${app.pi-runtime-token}") String piRuntimeToken
    ) {
        this.scriptMapper = scriptMapper;
        this.shotMapper = shotMapper;
        this.modelConfigService = modelConfigService;
        this.frameExtractor = frameExtractor;
        this.cameraMotionAnalyzer = cameraMotionAnalyzer;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.piClient = RestClient.builder()
                .baseUrl(piRuntimeUrl)
                .defaultHeader("X-Internal-Token", piRuntimeToken)
                .build();
    }

    @Async("automationDiagnosisExecutor")
    public void analyze(String scriptId) {
        VideoScript script = scriptMapper.selectById(scriptId);
        if (script == null) return;
        try {
            script.markExtracting();
            scriptMapper.updateById(script);
            Path source = Path.of(script.getSourceFilePath()).toAbsolutePath().normalize();
            VideoFrameExtractor.ExtractedFrames frames = frameExtractor.extract(
                    source,
                    source.getParent().resolve("frames")
            );
            VideoCameraMotionAnalyzer.MotionAnalysis cameraMotion = analyzeCameraMotion(
                    source,
                    source.getParent().resolve("motion-frames"),
                    frames.durationSeconds()
            );

            ModelConfigService.RuntimeModelConnection model = modelConfigService
                    .driverConnection(script.getOrganizationId())
                    .orElseThrow(() -> new IllegalStateException("请先在模型接入中配置并启用驱动模型"));
            script.markAnalyzing(model.id(), model.modelId());
            scriptMapper.updateById(script);

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("sessionId", "video-script:" + script.getId());
            request.put("model", Map.of(
                    "baseUrl", model.baseUrl(),
                    "modelId", model.modelId(),
                    "apiFormat", model.apiFormat(),
                    "apiKey", model.apiKey()
            ));
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("requestedName", script.getName());
            context.put("sourceFileName", script.getSourceFileName());
            context.put("targetMarket", script.getTargetMarket() == null ? "" : script.getTargetMarket());
            context.put("requirements", script.getRequirements() == null ? "" : script.getRequirements());
            context.put("durationSeconds", frames.durationSeconds());
            context.put("frameCount", frames.files().size());
            context.put("semanticSamplingFps", frames.samplingFps());
            context.put("analysisMode", "DENSE_KEYFRAMES_WITH_MOTION_PATH_AND_BACKGROUND_FLOW");
            context.put("desiredShotCount", REQUIRED_SHOT_COUNT);
            context.put("contentExtractionPolicy", Map.of(
                    "extractOnlyReusableMotionCameraSceneTiming", true,
                    "excludeOriginalPersonIdentityHairStylingOutfit", true,
                    "excludeOriginalVideoProductDetails", true,
                    "excludeOnScreenSubtitlesTextWatermarks", true,
                    "productionProductSource", "USER_UPLOADED_TASK_PRODUCT_IMAGE_ONLY"
            ));
            context.put("cameraMotionEvidence", cameraMotion);
            request.put("context", context);
            List<Map<String, Object>> encodedFrames = new ArrayList<>();
            for (int index = 0; index < frames.files().size(); index++) {
                byte[] bytes = Files.readAllBytes(frames.files().get(index));
                encodedFrames.add(Map.of(
                        "index", index + 1,
                        "timestampSeconds", Math.round(frames.timestampSeconds(index) * 100.0) / 100.0,
                        "mimeType", "image/jpeg",
                        "data", Base64.getEncoder().encodeToString(bytes)
                ));
            }
            request.put("frames", encodedFrames);

            PiAnalysisResponse response = piClient.post()
                    .uri("/v1/video-script/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PiAnalysisResponse.class);
            if (response == null || response.result() == null) {
                throw new IllegalStateException("Pi Agent 没有返回视频脚本");
            }
            VideoScriptAnalysisResult result = objectMapper.convertValue(
                    response.result(),
                    VideoScriptAnalysisResult.class
            );
            validateResult(result);
            result = applyCameraMotionEvidence(result, cameraMotion);
            persistResult(script, result);
        } catch (Exception exception) {
            String message = readableMessage(exception);
            log.error("Video script analysis failed for {}: {}", scriptId, message, exception);
            VideoScript failed = scriptMapper.selectById(scriptId);
            if (failed != null) {
                failed.fail(message);
                scriptMapper.updateById(failed);
            }
        }
    }

    private VideoCameraMotionAnalyzer.MotionAnalysis analyzeCameraMotion(
            Path source, Path motionFrameDirectory, double durationSeconds) {
        try {
            VideoFrameExtractor.ExtractedFrames motionFrames = frameExtractor.extractMotionFrames(
                    source, motionFrameDirectory, durationSeconds
            );
            VideoCameraMotionAnalyzer.MotionAnalysis analysis = cameraMotionAnalyzer.analyze(motionFrames);
            log.info("Camera motion analysis completed: frames={}, fps={}, windows={}",
                    analysis.analyzedFrameCount(), analysis.samplingFps(), analysis.windows().size());
            return analysis;
        } catch (Exception exception) {
            log.warn("Camera motion pre-analysis unavailable, falling back to Pi visual inference: {}",
                    readableMessage(exception));
            return new VideoCameraMotionAnalyzer.MotionAnalysis(
                    "BACKGROUND_FEATURE_FLOW_V1", 0, 0, List.of()
            );
        }
    }

    private VideoScriptAnalysisResult applyCameraMotionEvidence(
            VideoScriptAnalysisResult result,
            VideoCameraMotionAnalyzer.MotionAnalysis cameraMotion) {
        if (result == null || result.shots() == null) return result;
        List<VideoScriptAnalysisResult.Shot> adjusted = result.shots().stream().map(shot ->
                new VideoScriptAnalysisResult.Shot(
                        shot.sequence(), shot.stage(), shot.title(), shot.startSecond(), shot.endSecond(),
                        shot.durationSeconds(), shot.sceneDescription(), shot.motionDescription(),
                        shot.motionIntensity(), null, shot.shotSize(),
                        cameraMotion.windows().isEmpty()
                                ? shot.cameraMovement()
                                : cameraMotionAnalyzer.cameraMovementFor(
                                        cameraMotion, shot.startSecond(), shot.endSecond(), shot.cameraMovement()
                                ),
                        shot.emotion(), shot.imagePrompt()
                )
        ).toList();
        return new VideoScriptAnalysisResult(
                result.title(), result.summary(), result.globalVisualTone(), result.durationSeconds(), adjusted
        );
    }

    private void persistResult(VideoScript script, VideoScriptAnalysisResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            shotMapper.delete(
                    new LambdaQueryWrapper<VideoScriptShot>()
                            .eq(VideoScriptShot::getScriptId, script.getId())
            );
            int fallbackSequence = 1;
            for (VideoScriptAnalysisResult.Shot shot : result.shots()) {
                int sequence = shot.sequence() == null ? fallbackSequence : shot.sequence();
                fallbackSequence = sequence + 1;
                shotMapper.insert(new VideoScriptShot(
                        script.getOrganizationId(),
                        script.getId(),
                        sequence,
                        safe(shot.stage(), "CONTENT", 40),
                        safe(shot.title(), "分镜 " + sequence, 160),
                        shot.startSecond(),
                        shot.endSecond(),
                        shot.durationSeconds(),
                        safe(shot.sceneDescription(), "画面待补充", 8000),
                        safeNullable(shot.motionDescription(), 8000),
                        safeNullable(shot.motionIntensity(), 20),
                        safeNullable(shot.narration(), 8000),
                        safeNullable(shot.shotSize(), 80),
                        safeNullable(shot.cameraMovement(), 120),
                        safeNullable(shot.emotion(), 120),
                        toJson(shot.imagePrompt())
                ));
            }
            script.complete(
                    safeNullable(result.summary(), 8000),
                    safeNullable(result.globalVisualTone(), 8000),
                    toJson(result)
            );
            scriptMapper.updateById(script);
        });
    }

    private void validateResult(VideoScriptAnalysisResult result) {
        if (result == null || result.shots() == null || result.shots().isEmpty()) {
            throw new IllegalStateException("驱动模型没有生成有效分镜");
        }
        if (result.shots().size() != REQUIRED_SHOT_COUNT) {
            throw new IllegalStateException(
                    "驱动模型返回了 " + result.shots().size() + " 个分镜，平台要求完整生成 " + REQUIRED_SHOT_COUNT + " 个"
            );
        }
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("视频脚本 JSON 保存失败", exception);
        }
    }

    private String safe(String value, String fallback, int max) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.substring(0, Math.min(normalized.length(), max));
    }

    private String safeNullable(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.substring(0, Math.min(normalized.length(), max));
    }

    private String readableMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "视频脚本拆解服务调用失败";
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 1200));
    }

    private record PiAnalysisResponse(
            Map<String, Object> result,
            Map<String, Object> usage,
            Long latencyMs,
            String modelId
    ) {
    }
}
