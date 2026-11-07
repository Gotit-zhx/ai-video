package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.domain.VideoScriptShot;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

@Service
public class VideoScriptAnalysisProcessor {

    private static final Logger log = LoggerFactory.getLogger(VideoScriptAnalysisProcessor.class);
    private static final int REQUIRED_SHOT_COUNT = 9;
    private static final BigDecimal MAX_SCRIPT_DURATION_SECONDS = new BigDecimal("15.0");
    private static final long[] ANALYSIS_RETRY_DELAYS_MILLIS = {1_000L, 3_000L};
    private static final Duration ANALYSIS_READ_TIMEOUT = Duration.ofSeconds(960);

    private final VideoScriptMapper scriptMapper;
    private final VideoScriptShotMapper shotMapper;
    private final ModelConfigService modelConfigService;
    private final VideoFrameExtractor frameExtractor;
    private final VideoCameraMotionAnalyzer cameraMotionAnalyzer;
    private final DayanAudioTranscriptionService audioTranscriptionService;
    private final ObjectMapper objectMapper;
    private final RestClient piClient;
    private final RestClient piProgressClient;
    private final TransactionTemplate transactionTemplate;

    public VideoScriptAnalysisProcessor(
            VideoScriptMapper scriptMapper,
            VideoScriptShotMapper shotMapper,
            ModelConfigService modelConfigService,
            VideoFrameExtractor frameExtractor,
            VideoCameraMotionAnalyzer cameraMotionAnalyzer,
            DayanAudioTranscriptionService audioTranscriptionService,
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
        this.audioTranscriptionService = audioTranscriptionService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        JdkClientHttpRequestFactory analysisRequestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        );
        analysisRequestFactory.setReadTimeout(ANALYSIS_READ_TIMEOUT);
        this.piClient = RestClient.builder()
                .baseUrl(piRuntimeUrl)
                .defaultHeader("X-Internal-Token", piRuntimeToken)
                .requestFactory(analysisRequestFactory)
                .build();
        JdkClientHttpRequestFactory progressRequestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        );
        progressRequestFactory.setReadTimeout(Duration.ofSeconds(2));
        this.piProgressClient = RestClient.builder()
                .baseUrl(piRuntimeUrl)
                .defaultHeader("X-Internal-Token", piRuntimeToken)
                .requestFactory(progressRequestFactory)
                .build();
    }

    @Async("videoScriptAnalysisExecutor")
    public void analyze(String scriptId) {
        VideoScript script = scriptMapper.selectById(scriptId);
        if (script == null || !"QUEUED".equals(script.getStatus())) return;
        Instant queuedAt = script.getUpdatedAt();
        script.markExtracting();
        int claimed = scriptMapper.update(null, new LambdaUpdateWrapper<VideoScript>()
                .eq(VideoScript::getId, scriptId)
                .eq(VideoScript::getStatus, "QUEUED")
                .eq(VideoScript::getUpdatedAt, queuedAt)
                .set(VideoScript::getStatus, script.getStatus())
                .set(VideoScript::getProgress, script.getProgress())
                .set(VideoScript::getAnalysisStage, script.getAnalysisStage())
                .set(VideoScript::getErrorMessage, null)
                .set(VideoScript::getUpdatedAt, script.getUpdatedAt()));
        if (claimed != 1) return;
        try {
            Path source = Path.of(script.getSourceFilePath()).toAbsolutePath().normalize();
            // Watch owns all visual sampling and interpretation. Java only
            // probes timing and passes the shared-volume source to the skill.
            double sourceDurationSeconds = frameExtractor.probeDuration(source);
            DayanAudioTranscriptionService.Transcript transcript = DayanAudioTranscriptionService.Transcript.empty();
            String audioExtractionStatus = script.isExtractAudio() ? "REQUESTED" : "NOT_REQUESTED";
            if (script.isExtractAudio()) {
                try {
                    transcript = audioTranscriptionService.transcribe(script);
                    audioExtractionStatus = transcript.available() ? "TRANSCRIBED" : "NO_SPEECH";
                } catch (DayanAudioTranscriptionService.NoAudioTrackException exception) {
                    audioExtractionStatus = "NO_AUDIO_TRACK";
                    log.info("Video script {} has no usable audio track; continue with visual analysis", scriptId);
                }
            }

            ModelConfigService.RuntimeModelConnection model = modelConfigService
                    .driverConnection(script.getOrganizationId())
                    .orElseThrow(() -> new IllegalStateException("请先在模型接入中配置并启用驱动模型"));
            script.markAnalyzing(model.id(), model.modelId());
            scriptMapper.updateById(script);

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("sessionId", "video-script:" + script.getId());
            request.put("sourceVideoPath", source.toString());
            String progressId = script.getAnalysisRunId();
            request.put("progressId", progressId);
            request.put("model", model.driverRequestModel());
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("requestedName", script.getName());
            context.put("sourceFileName", script.getSourceFileName());
            context.put("targetMarket", script.getTargetMarket() == null ? "" : script.getTargetMarket());
            context.put("requirements", script.getRequirements() == null ? "" : script.getRequirements());
            context.put("presentationMode", script.getPresentationMode());
            double targetDurationSeconds = Math.min(sourceDurationSeconds, MAX_SCRIPT_DURATION_SECONDS.doubleValue());
            context.put("sourceDurationSeconds", sourceDurationSeconds);
            context.put("durationSeconds", targetDurationSeconds);
            context.put("targetDurationSeconds", targetDurationSeconds);
            context.put("normalizeLongVideoToTargetDuration", sourceDurationSeconds > targetDurationSeconds);
            context.put("analysisMode", "PI_WATCH_SKILL");
            context.put("desiredShotCount", REQUIRED_SHOT_COUNT);
            context.put("contentExtractionPolicy", Map.of(
                    "extractOnlyReusableMotionCameraSceneTiming", true,
                    "excludeOriginalPersonIdentityHairStylingOutfit", true,
                    "excludeOriginalVideoProductDetails", true,
                    "excludeOnScreenSubtitlesTextWatermarks", true,
                    "preserveObjectOrientationAndStateTransitions", true,
                    "preserveVisibleFunctionalActions", true,
                    "productionProductSource", "USER_UPLOADED_TASK_PRODUCT_IMAGE_ONLY"
            ));
            context.put("hasReliableAudioTranscript", transcript.available());
            context.put("audioExtractionStatus", audioExtractionStatus);
            context.put("audioTranscript", Map.of(
                    "fullText", transcript.fullText(),
                    "segments", transcript.segments().stream().map(segment -> Map.of(
                            "startSecond", segment.startSecond(),
                            "endSecond", segment.endSecond(),
                            "speaker", segment.speaker() == null ? "" : segment.speaker(),
                            "text", segment.text()
                    )).toList()
            ));
            request.put("context", context);
            PiAnalysisResponse response = requestAnalysisWithProgress(scriptId, progressId, request);
            if (response == null || response.result() == null) {
                throw new IllegalStateException("Pi Agent 没有返回视频脚本");
            }
            persistAnalysisSnapshot(scriptId, progressId, new PiProgressResponse(
                    94, "RESULT_READY", response.analysisOutputCharacters(), true,
                    response.analysisOutput(), response.analysisOutputUpdatedAt()));
            VideoScriptAnalysisResult result = objectMapper.convertValue(
                    response.result(),
                    VideoScriptAnalysisResult.class
            );
            validateResult(result);
            persistAnalysisProgress(scriptId, 95);
            result = normalizeLongVideoTimeline(result, sourceDurationSeconds);
            prepareShotPreviews(source, result, sourceDurationSeconds);
            // Polling writes output snapshots on separately loaded entities.
            // Complete the latest entity so the final write retains that text.
            VideoScript latest = scriptMapper.selectById(scriptId);
            persistResult(latest == null ? script : latest, result);
        } catch (Exception exception) {
            String message = readableMessage(exception);
            if (script.getModelId() != null) {
                PiRuntimeFailure.Details failure = PiRuntimeFailure.from(exception);
                message = "模型「" + script.getModelId() + "」拆解失败 [" + failure.failureCode() + "]："
                        + (failure.message() == null ? message : failure.message());
            }
            log.error("Video script analysis failed for {}: {}", scriptId, message, exception);
            VideoScript failed = scriptMapper.selectById(scriptId);
            if (failed != null) {
                failed.fail(message);
                scriptMapper.updateById(failed);
            }
        }
    }

    private PiAnalysisResponse requestAnalysisWithProgress(
            String scriptId, String progressId, Map<String, Object> request) {
        FutureTask<PiAnalysisResponse> analysis = new FutureTask<>(() -> requestAnalysisWithRetry(scriptId, request));
        var tracked=com.yingdao.aicommerce.ops.MaintenanceGate.accepted(analysis);
        Thread worker = Thread.ofVirtual().name("video-script-pi-" + scriptId).unstarted(tracked);
        tracked.start(worker);
        try {
            while (!analysis.isDone()) {
                pollAnalysisProgress(scriptId, progressId);
                Thread.sleep(1_000L);
            }
            return analysis.get();
        } catch (InterruptedException interrupted) {
            analysis.cancel(true);
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("视频拆解进度跟踪被中断", interrupted);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("视频拆解服务调用失败", cause);
        } finally {
            pollAnalysisProgress(scriptId, progressId);
            if (!analysis.isDone()) {
                analysis.cancel(true);
                worker.interrupt();
            }
        }
    }

    private void pollAnalysisProgress(String scriptId, String progressId) {
        try {
            PiProgressResponse progress = piProgressClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/video-script/progress")
                            .queryParam("progressId", progressId)
                            .build())
                    .retrieve()
                    .body(PiProgressResponse.class);
            if (progress != null) persistAnalysisSnapshot(scriptId, progressId, progress);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                log.debug("Unable to read Pi analysis progress for {}: {}", scriptId, exception.getMessage());
            }
        } catch (ResourceAccessException exception) {
            log.debug("Pi analysis progress is temporarily unavailable for {}: {}", scriptId, exception.getMessage());
        } catch (RestClientException exception) {
            log.debug("Pi analysis progress response is temporarily unreadable for {}: {}", scriptId, exception.getMessage());
        }
    }

    private void persistAnalysisSnapshot(String scriptId, String runId, PiProgressResponse snapshot) {
        VideoScript current = scriptMapper.selectById(scriptId);
        if (current == null || !java.util.Objects.equals(current.getAnalysisRunId(), runId)) return;
        boolean changed = snapshot.progress() != null && current.advanceAnalysisProgress(snapshot.progress());
        changed |= current.receiveAnalysisOutput(runId, snapshot.stage(), snapshot.output(),
                snapshot.outputCharacters(), snapshot.outputUpdatedAt() == null
                        ? null : Instant.ofEpochMilli(snapshot.outputUpdatedAt()));
        if (changed) scriptMapper.updateById(current);
    }

    private void persistAnalysisProgress(String scriptId, int progress) {
        VideoScript current = scriptMapper.selectById(scriptId);
        if (current != null && current.advanceAnalysisProgress(progress)) {
            scriptMapper.updateById(current);
        }
    }

    private PiAnalysisResponse requestAnalysisWithRetry(String scriptId, Map<String, Object> request) {
        for (int attempt = 0; ; attempt++) {
            try {
                return piClient.post()
                        .uri("/v1/video-script/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(PiAnalysisResponse.class);
            } catch (RestClientResponseException exception) {
                if (!retryablePiFailure(exception.getStatusCode().value(), exception.getResponseBodyAsString())
                        || attempt >= ANALYSIS_RETRY_DELAYS_MILLIS.length) {
                    throw exception;
                }
                log.warn("Video script analysis {} received transient Pi response {}. Retrying attempt {}/{}",
                        scriptId, exception.getStatusCode().value(), attempt + 2,
                        ANALYSIS_RETRY_DELAYS_MILLIS.length + 1);
                waitBeforeAnalysisRetry(attempt);
            } catch (ResourceAccessException exception) {
                if (attempt >= ANALYSIS_RETRY_DELAYS_MILLIS.length) throw exception;
                log.warn("Video script analysis {} lost the Pi connection. Retrying attempt {}/{}",
                        scriptId, attempt + 2, ANALYSIS_RETRY_DELAYS_MILLIS.length + 1);
                waitBeforeAnalysisRetry(attempt);
            }
        }
    }

    static boolean retryablePiFailure(int statusCode, String responseBody) {
        if (PiRuntimeFailure.parse(responseBody).explicitlyNonRetryable()) return false;
        if (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500) return true;
        String message = responseBody == null ? "" : responseBody.toLowerCase();
        return message.contains("stream ended without finish_reason")
                || message.contains("timeout")
                || message.contains("timed out")
                || message.contains("connection reset");
    }

    private void waitBeforeAnalysisRetry(int attempt) {
        try {
            Thread.sleep(ANALYSIS_RETRY_DELAYS_MILLIS[Math.min(attempt, ANALYSIS_RETRY_DELAYS_MILLIS.length - 1)]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("视频拆解自动重试被中断", interrupted);
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
            VideoCameraMotionAnalyzer.MotionAnalysis cameraMotion,
            double sourceDurationSeconds) {
        if (result == null || result.shots() == null) return result;
        double resultDurationSeconds = result.durationSeconds() == null
                ? sourceDurationSeconds
                : result.durationSeconds().doubleValue();
        double sourceTimelineScale = sourceDurationSeconds > resultDurationSeconds && resultDurationSeconds > 0
                ? sourceDurationSeconds / resultDurationSeconds
                : 1.0;
        List<VideoScriptAnalysisResult.Shot> adjusted = result.shots().stream().map(shot ->
                new VideoScriptAnalysisResult.Shot(
                        shot.sequence(), shot.stage(), shot.title(), shot.startSecond(), shot.endSecond(),
                        shot.durationSeconds(), shot.sceneDescription(), shot.motionDescription(),
                        shot.motionIntensity(), shot.narration(), shot.shotSize(),
                        cameraMotion.windows().isEmpty()
                                ? shot.cameraMovement()
                                : cameraMotionAnalyzer.cameraMovementFor(
                                        cameraMotion,
                                        scaleTimelineSecond(shot.startSecond(), sourceTimelineScale),
                                        scaleTimelineSecond(shot.endSecond(), sourceTimelineScale),
                                        shot.cameraMovement()
                                ),
                        shot.transitionDescription(), shot.emotion(), shot.imagePrompt()
                )
        ).toList();
        return new VideoScriptAnalysisResult(
                result.title(), result.summary(), result.globalVisualTone(), result.durationSeconds(), adjusted
        );
    }

    static VideoScriptAnalysisResult applyAudioTranscript(
            VideoScriptAnalysisResult result,
            DayanAudioTranscriptionService.Transcript transcript,
            double sourceDurationSeconds) {
        if (result == null || result.shots() == null || transcript == null || !transcript.available()) return result;
        boolean hasCondensedNarration = result.shots().stream()
                .anyMatch(shot -> shot.narration() != null && !shot.narration().isBlank());
        if (hasCondensedNarration) {
            return result;
        }

        double resultDuration = result.durationSeconds() == null
                ? sourceDurationSeconds : result.durationSeconds().doubleValue();
        double scale = sourceDurationSeconds > resultDuration && sourceDurationSeconds > 0
                ? resultDuration / sourceDurationSeconds : 1.0;
        List<VideoScriptAnalysisResult.Shot> shots = result.shots().stream().map(shot -> {
            String narration = transcript.segments().stream()
                    .filter(segment -> overlaps(shot.startSecond(), shot.endSecond(),
                            segment.startSecond().multiply(BigDecimal.valueOf(scale)),
                            segment.endSecond().multiply(BigDecimal.valueOf(scale))))
                    .map(DayanAudioTranscriptionService.Segment::text)
                    .filter(text -> text != null && !text.isBlank())
                    .reduce((left, right) -> left + right).orElse("");
            if (narration.isBlank()) narration = shot.narration();
            else narration = "口播：" + narration;
            return new VideoScriptAnalysisResult.Shot(
                    shot.sequence(), shot.stage(), shot.title(), shot.startSecond(), shot.endSecond(),
                    shot.durationSeconds(), shot.sceneDescription(), shot.motionDescription(),
                    shot.motionIntensity(), narration, shot.shotSize(), shot.cameraMovement(),
                    shot.transitionDescription(), shot.emotion(), shot.imagePrompt());
        }).toList();
        return new VideoScriptAnalysisResult(
                result.title(), result.summary(), result.globalVisualTone(), result.durationSeconds(), shots);
    }

    private static boolean overlaps(BigDecimal shotStart, BigDecimal shotEnd,
                                    BigDecimal segmentStart, BigDecimal segmentEnd) {
        if (shotStart == null || shotEnd == null) return false;
        return segmentEnd.compareTo(shotStart) > 0 && segmentStart.compareTo(shotEnd) < 0;
    }

    private BigDecimal scaleTimelineSecond(BigDecimal second, double scale) {
        if (second == null || scale == 1.0) return second;
        return second.multiply(BigDecimal.valueOf(scale));
    }

    static VideoScriptAnalysisResult normalizeLongVideoTimeline(
            VideoScriptAnalysisResult result, double sourceDurationSeconds) {
        if (result == null || result.shots() == null
                || sourceDurationSeconds <= MAX_SCRIPT_DURATION_SECONDS.doubleValue()) {
            return result;
        }
        int shotCount = result.shots().size();
        if (shotCount == 0) return result;

        List<VideoScriptAnalysisResult.Shot> normalized = new ArrayList<>(shotCount);
        for (int index = 0; index < shotCount; index++) {
            VideoScriptAnalysisResult.Shot shot = result.shots().get(index);
            BigDecimal start = timelineBoundary(index, shotCount);
            BigDecimal end = timelineBoundary(index + 1, shotCount);
            normalized.add(new VideoScriptAnalysisResult.Shot(
                    shot.sequence(), shot.stage(), shot.title(), start, end, end.subtract(start),
                    shot.sceneDescription(), shot.motionDescription(), shot.motionIntensity(), shot.narration(),
                    shot.shotSize(), shot.cameraMovement(), shot.transitionDescription(), shot.emotion(),
                    shot.imagePrompt()
            ));
        }
        return new VideoScriptAnalysisResult(
                result.title(), result.summary(), result.globalVisualTone(), MAX_SCRIPT_DURATION_SECONDS, normalized
        );
    }

    private static BigDecimal timelineBoundary(int index, int shotCount) {
        if (index == shotCount) return MAX_SCRIPT_DURATION_SECONDS;
        return MAX_SCRIPT_DURATION_SECONDS.multiply(BigDecimal.valueOf(index))
                .divide(BigDecimal.valueOf(shotCount), 1, RoundingMode.HALF_UP);
    }

    private void prepareShotPreviews(Path source, VideoScriptAnalysisResult result, double sourceDurationSeconds) {
        try {
            frameExtractor.extractShotPreviews(source, source.getParent().resolve("shot-previews"),
                    shotPreviewTimestamps(result, sourceDurationSeconds));
        } catch (Exception exception) {
            // Preview JPEGs are display assets, never inputs to analysis. Do not
            // lose a completed model result because a thumbnail cannot be made.
            log.warn("Unable to prepare script preview images for {}: {}", source, readableMessage(exception));
        }
    }

    static Map<Integer, Double> shotPreviewTimestamps(VideoScriptAnalysisResult result, double sourceDurationSeconds) {
        Map<Integer, Double> timestamps = new LinkedHashMap<>();
        double targetDuration = result.durationSeconds() == null
                ? sourceDurationSeconds : result.durationSeconds().doubleValue();
        if (!Double.isFinite(targetDuration) || targetDuration <= 0) targetDuration = sourceDurationSeconds;
        int fallbackSequence = 1;
        for (int index = 0; index < result.shots().size(); index++) {
            VideoScriptAnalysisResult.Shot shot = result.shots().get(index);
            int sequence = shot.sequence() == null ? fallbackSequence : shot.sequence();
            fallbackSequence = sequence + 1;
            double timestamp = Double.NaN;
            Object evidence = shot.imagePrompt() == null ? null : shot.imagePrompt().get("sourceTimestampSeconds");
            if (evidence != null) {
                try { timestamp = Double.parseDouble(evidence.toString()); }
                catch (NumberFormatException ignored) { /* Fall back to the shot's source-time midpoint. */ }
            }
            if (!Double.isFinite(timestamp) || timestamp < 0 || timestamp >= sourceDurationSeconds) {
                timestamp = shot.startSecond() != null && shot.endSecond() != null
                        ? (shot.startSecond().doubleValue() + shot.endSecond().doubleValue()) / 2
                            * sourceDurationSeconds / targetDuration
                        : (index + 0.5) * sourceDurationSeconds / result.shots().size();
            }
            timestamps.put(sequence, Math.max(0, Math.min(sourceDurationSeconds - 0.001, timestamp)));
        }
        return timestamps;
    }

    void persistResult(VideoScript script, VideoScriptAnalysisResult result) {
        // Deleting an empty per-script range at MySQL REPEATABLE READ takes gap
        // locks shared with unrelated new scripts. Their inserts then deadlock.
        // Keep this short, atomic result write at READ COMMITTED, without changing
        // the shared template or rerunning any paid analysis on a lock conflict.
        TransactionTemplate resultTransaction = new TransactionTemplate(
                java.util.Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        resultTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        resultTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (int attempt = 1; ; attempt++) {
            try {
                persistResultOnce(script, result, resultTransaction);
                return;
            } catch (org.springframework.dao.PessimisticLockingFailureException conflict) {
                if (attempt >= 3) throw conflict;
                log.warn("Retrying completed script result persistence after lock conflict: script={}, attempt={}", script.getId(), attempt);
            }
        }
    }

    private void persistResultOnce(VideoScript script, VideoScriptAnalysisResult result, TransactionTemplate resultTransaction) {
        resultTransaction.executeWithoutResult(status -> {
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
                        safeNullable(shot.transitionDescription(), 500),
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
            String modelId,
            String analysisOutput,
            Long analysisOutputCharacters,
            Long analysisOutputUpdatedAt
    ) {
    }

    private record PiProgressResponse(
            Integer progress,
            String stage,
            Long outputCharacters,
            Boolean done,
            String output,
            Long outputUpdatedAt
    ) {
    }
}
