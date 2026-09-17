package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.domain.VideoScriptShot;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Service
public class VideoScriptService {

    private static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;
    static final BigDecimal ANALYSIS_CLIP_SECONDS = new BigDecimal("15");
    static final BigDecimal MIN_ANALYSIS_CLIP_SECONDS = new BigDecimal("0.1");
    private static final BigDecimal CLIP_BOUNDARY_TOLERANCE_SECONDS = new BigDecimal("0.05");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "m4v", "webm", "avi", "mkv");

    private final Path storageRoot;
    private final VideoScriptMapper scriptMapper;
    private final VideoScriptShotMapper shotMapper;
    private final VideoScriptAnalysisProcessor processor;
    private final VideoFrameExtractor videoFrameExtractor;
    private final ObjectMapper objectMapper;

    public VideoScriptService(
            @Value("${app.video-script-storage:/data/video-scripts}") String storageRoot,
            VideoScriptMapper scriptMapper,
            VideoScriptShotMapper shotMapper,
            VideoScriptAnalysisProcessor processor,
            VideoFrameExtractor videoFrameExtractor,
            ObjectMapper objectMapper
    ) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.scriptMapper = scriptMapper;
        this.shotMapper = shotMapper;
        this.processor = processor;
        this.videoFrameExtractor = videoFrameExtractor;
        this.objectMapper = objectMapper;
    }

    public VideoScript createAnalysis(
            MultipartFile video,
            String name,
            String targetMarket,
            String requirements,
            String presentationMode,
            boolean extractAudio,
            PlatformPrincipal principal
    ) {
        return createAnalysis(video, name, targetMarket, requirements, presentationMode,
                extractAudio, BigDecimal.ZERO, null, principal);
    }

    public VideoScript createAnalysis(
            MultipartFile video,
            String name,
            String targetMarket,
            String requirements,
            String presentationMode,
            boolean extractAudio,
            BigDecimal clipStartSecond,
            PlatformPrincipal principal
    ) {
        return createAnalysis(video, name, targetMarket, requirements, presentationMode,
                extractAudio, clipStartSecond, null, principal);
    }

    public VideoScript createAnalysis(
            MultipartFile video,
            String name,
            String targetMarket,
            String requirements,
            String presentationMode,
            boolean extractAudio,
            BigDecimal clipStartSecond,
            BigDecimal clipEndSecond,
            PlatformPrincipal principal
    ) {
        validateVideo(video);
        String id = UUID.randomUUID().toString();
        String originalName = safeFileName(video.getOriginalFilename());
        String extension = extension(originalName);
        Path directory = storageRoot
                .resolve(principal.organizationId())
                .resolve(id)
                .normalize();
        if (!directory.startsWith(storageRoot)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "视频存储路径无效");
        }
        Path originalSource = directory.resolve("original." + extension).normalize();
        if (!originalSource.startsWith(directory)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "视频存储路径无效");
        }
        Path analysisSource = originalSource;
        BigDecimal sourceDuration = null;
        BigDecimal clipStart = null;
        BigDecimal clipEnd = null;
        boolean inserted = false;
        try {
            Files.createDirectories(directory);
            video.transferTo(originalSource);
            double probedDuration = videoFrameExtractor.probeDuration(originalSource);
            sourceDuration = seconds(probedDuration);
            ClipWindow window = clipWindow(
                    BigDecimal.valueOf(probedDuration), clipStartSecond, clipEndSecond);
            clipStart = window.startSecond();
            clipEnd = window.trimmed() ? window.endSecond() : sourceDuration;
            if (window.trimmed()) {
                analysisSource = directory.resolve("source.mp4").normalize();
                if (!analysisSource.startsWith(directory)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "视频存储路径无效");
                }
                videoFrameExtractor.trim(
                        originalSource,
                        analysisSource,
                        clipStart.doubleValue(),
                        window.durationSeconds().doubleValue()
                );
            }
        } catch (IOException exception) {
            deleteQuietly(directory);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "视频上传保存失败");
        } catch (RuntimeException exception) {
            deleteQuietly(directory);
            throw exception;
        }

        String normalizedName = name == null || name.isBlank()
                ? originalName.replaceFirst("\\.[^.]+$", "")
                : name.trim();
        VideoScript script = new VideoScript(
                id,
                principal.organizationId(),
                normalizedName.substring(0, Math.min(normalizedName.length(), 160)),
                "VIDEO_ANALYSIS",
                normalizePresentationMode(presentationMode),
                originalName,
                analysisSource.toString(),
                analysisSource.equals(originalSource) ? video.getContentType() : "video/mp4",
                fileSize(analysisSource),
                trimTo(targetMarket, 80),
                trimTo(requirements, 4000),
                principal.userId()
        );
        script.requestAudioExtraction(extractAudio);
        script.configureClip(sourceDuration, clipStart, clipEnd);
        try {
            scriptMapper.insert(script);
        } catch (RuntimeException exception) {
            deleteQuietly(directory);
            throw exception;
        }
        submitAnalysis(script);
        return script;
    }

    @Transactional
    public VideoScript createManual(
            String name,
            String targetMarket,
            String visualTone,
            String presentationMode,
            List<ManualShot> requestedShots,
            PlatformPrincipal principal
    ) {
        String normalizedName = required(name, "脚本名称", 160);
        String normalizedTone = required(visualTone, "全局视觉基调", 4000);
        if (requestedShots == null || requestedShots.size() != 9) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "手动脚本必须完整填写 9 个分镜");
        }

        String id = UUID.randomUUID().toString();
        List<VideoScriptShot> shots = new ArrayList<>(9);
        BigDecimal previousEnd = null;
        for (int index = 0; index < requestedShots.size(); index++) {
            ManualShot requested = requestedShots.get(index);
            if (requested == null || requested.startSecond() == null || requested.endSecond() == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "第 " + (index + 1) + " 个分镜必须填写开始和结束秒数");
            }
            BigDecimal start = requested.startSecond().stripTrailingZeros();
            BigDecimal end = requested.endSecond().stripTrailingZeros();
            if (start.signum() < 0 || end.compareTo(start) <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "第 " + (index + 1) + " 个分镜时间范围无效");
            }
            if (previousEnd != null && start.compareTo(previousEnd) != 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "第 " + (index + 1) + " 个分镜必须从上一分镜结束时间开始");
            }
            String scene = required(requested.sceneDescription(), "第 " + (index + 1) + " 个分镜画面与动作", 8000);
            String motion = trimTo(requested.motionDescription(), 8000);
            if (motion == null) motion = scene;
            String imagePrompt = toJson(Map.of("prompt", scene));
            shots.add(new VideoScriptShot(
                    principal.organizationId(), id, index + 1, normalizeStage(requested.stage()),
                    required(requested.title(), "第 " + (index + 1) + " 个分镜标题", 160),
                    start, end, end.subtract(start), scene, motion, null,
                    trimTo(requested.narration(), 8000), trimTo(requested.shotSize(), 80),
                    trimTo(requested.cameraMovement(), 120), null, trimTo(requested.emotion(), 120), imagePrompt
            ));
            previousEnd = end;
        }

        VideoScript script = new VideoScript(
                id, principal.organizationId(), normalizedName, "MANUAL",
                normalizePresentationMode(presentationMode), null, null, null, null,
                trimTo(targetMarket, 80), null, principal.userId()
        );
        String summary = "手动创建的 9 分镜生产脚本";
        script.complete(summary, normalizedTone, rebuildScriptJson(null, normalizedName, summary, normalizedTone, shots));
        scriptMapper.insert(script);
        shots.forEach(shotMapper::insert);
        return script;
    }

    public VideoScript currentBlocking(PlatformPrincipal principal) {
        return scriptMapper.selectOne(
                new LambdaQueryWrapper<VideoScript>()
                        .eq(VideoScript::getOrganizationId, principal.organizationId())
                        .eq(VideoScript::getCreatedBy, principal.userId())
                        .in(VideoScript::getStatus, "QUEUED", "EXTRACTING_FRAMES", "AI_ANALYZING")
                        .orderByDesc(VideoScript::getUpdatedAt)
                        .last("LIMIT 1")
        );
    }

    public VideoScript retry(String id, PlatformPrincipal principal) {
        VideoScript script = requireOwnedScript(id, principal);
        if (!"FAILED".equals(script.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "只有拆解失败的任务可以重试");
        }
        queueExistingAnalysis(script, false);
        return script;
    }

    public VideoScript reanalyze(String id, PlatformPrincipal principal) {
        VideoScript script = requireOwnedScript(id, principal);
        if (!"READY".equals(script.getStatus()) || !"VIDEO_ANALYSIS".equals(script.getSourceType())) {
            throw new BusinessException(HttpStatus.CONFLICT, "只有已完成的视频拆解脚本可以重新精拆");
        }
        queueExistingAnalysis(script, true);
        return script;
    }

    private void queueExistingAnalysis(VideoScript script, boolean reanalysis) {
        String previousStatus = script.getStatus();
        Instant previousUpdatedAt = script.getUpdatedAt();
        if (reanalysis) script.prepareReanalysis();
        else script.prepareRetry();
        int changed = scriptMapper.update(null, new LambdaUpdateWrapper<VideoScript>()
                .eq(VideoScript::getId, script.getId())
                .eq(VideoScript::getOrganizationId, script.getOrganizationId())
                .eq(VideoScript::getCreatedBy, script.getCreatedBy())
                .eq(VideoScript::getStatus, previousStatus)
                .eq(VideoScript::getUpdatedAt, previousUpdatedAt)
                .set(VideoScript::getStatus, script.getStatus())
                .set(VideoScript::getProgress, script.getProgress())
                .set(VideoScript::getAnalysisRunId, script.getAnalysisRunId())
                .set(VideoScript::getAnalysisStage, script.getAnalysisStage())
                .set(VideoScript::getAnalysisOutput, script.getAnalysisOutput())
                .set(VideoScript::getAnalysisOutputCharacters, 0)
                .set(VideoScript::getAnalysisOutputUpdatedAt, null)
                .set(VideoScript::getErrorMessage, null)
                .set(VideoScript::getCompletedAt, null)
                .set(VideoScript::getUpdatedAt, script.getUpdatedAt())
                .set(VideoScript::getVersion, script.getVersion()));
        if (changed != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "这条视频脚本状态已更新，请刷新后重试");
        }
        submitAnalysis(script);
    }

    private void submitAnalysis(VideoScript script) {
        try {
            processor.analyze(script.getId());
        } catch (RejectedExecutionException exception) {
            script.fail("视频拆解队列已满，任务和原视频已保存，请稍后重试当前任务");
            // Rejection happens before an async worker starts; never leave an orphaned QUEUED row.
            scriptMapper.update(null, new LambdaUpdateWrapper<VideoScript>()
                    .eq(VideoScript::getId, script.getId())
                    .eq(VideoScript::getStatus, "QUEUED")
                    .set(VideoScript::getStatus, script.getStatus())
                    .set(VideoScript::getProgress, script.getProgress())
                    .set(VideoScript::getErrorMessage, script.getErrorMessage())
                    .set(VideoScript::getCompletedAt, script.getCompletedAt())
                    .set(VideoScript::getUpdatedAt, script.getUpdatedAt()));
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, script.getErrorMessage());
        }
    }

    @Transactional
    public VideoScript copy(String id, PlatformPrincipal principal) {
        VideoScript original = requireAccessibleScript(id, principal);
        if (!Set.of("READY", "DRAFT").contains(original.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "脚本处理完成后才能复制版本");
        }

        String copyId = UUID.randomUUID().toString();
        String copyName = trimTo(original.getName() + " - 副本", 160);
        Path copiedSource = copyScriptFiles(original, copyId);
        try {
            VideoScript copy = new VideoScript(
                    copyId,
                    original.getOrganizationId(),
                    copyName,
                    original.getSourceType(),
                    original.getPresentationMode(),
                    original.getSourceFileName(),
                    copiedSource == null ? null : copiedSource.toString(),
                    original.getSourceContentType(),
                    original.getSourceSizeBytes(),
                    original.getTargetMarket(),
                    original.getRequirements(),
                    principal.userId()
            );
            copy.requestAudioExtraction(original.isExtractAudio());
            copy.configureClip(
                    original.getSourceDurationSeconds(),
                    original.getClipStartSecond(),
                    original.getClipEndSecond()
            );
            List<VideoScriptShot> originalShots = shotMapper.selectList(
                    new LambdaQueryWrapper<VideoScriptShot>()
                            .eq(VideoScriptShot::getOrganizationId, principal.organizationId())
                            .eq(VideoScriptShot::getScriptId, original.getId())
                            .orderByAsc(VideoScriptShot::getSequenceNo)
            );
            List<VideoScriptShot> copiedShots = originalShots.stream()
                    .map(shot -> new VideoScriptShot(
                            original.getOrganizationId(), copyId, shot.getSequenceNo(), shot.getStage(), shot.getTitle(),
                            shot.getStartSecond(), shot.getEndSecond(), shot.getDurationSeconds(),
                            shot.getSceneDescription(), shot.getMotionDescription(), shot.getMotionIntensity(),
                            shot.getNarration(), shot.getShotSize(), shot.getCameraMovement(),
                            shot.getTransitionDescription(), shot.getEmotion(),
                            shot.getImagePromptJson()
                    ))
                    .toList();
            String copiedJson = rebuildScriptJson(
                    original.getScriptJson(), copyName, original.getSummary(), original.getVisualTone(), copiedShots
            );
            if ("DRAFT".equals(original.getStatus())) {
                copy.saveManual(original.getSummary(), original.getVisualTone(), copiedJson);
            } else {
                copy.complete(original.getSummary(), original.getVisualTone(), copiedJson);
            }
            copy.preserveAnalysisModel(original.getModelConfigId(), original.getModelId());
            scriptMapper.insert(copy);
            copiedShots.forEach(shotMapper::insert);
            return copy;
        } catch (RuntimeException exception) {
            if (copiedSource != null) deleteQuietly(copiedSource.getParent());
            throw exception;
        }
    }

    @Transactional
    public VideoScript updateVisualTone(String id, String visualTone, PlatformPrincipal principal) {
        VideoScript script = requireAccessibleScript(id, principal);
        if (Set.of("QUEUED", "EXTRACTING_FRAMES", "AI_ANALYZING").contains(script.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "视频正在拆解，暂时不能编辑视觉基调");
        }
        String normalizedTone = required(visualTone, "全局视觉基调", 4000);
        List<VideoScriptShot> shots = shotMapper.selectList(
                new LambdaQueryWrapper<VideoScriptShot>()
                        .eq(VideoScriptShot::getOrganizationId, principal.organizationId())
                        .eq(VideoScriptShot::getScriptId, id)
                        .orderByAsc(VideoScriptShot::getSequenceNo)
        );
        String scriptJson = rebuildScriptJson(
                script.getScriptJson(), script.getName(), script.getSummary(), normalizedTone, shots
        );
        script.saveEditedVisualTone(normalizedTone, scriptJson);
        scriptMapper.updateById(script);
        return script;
    }

    public void delete(String id, PlatformPrincipal principal) {
        VideoScript script = requireOwnedScript(id, principal);
        if (Set.of("QUEUED", "EXTRACTING_FRAMES", "AI_ANALYZING").contains(script.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "视频正在拆解，完成或失败后才能删除");
        }
        scriptMapper.deleteById(script.getId());
        if (script.getSourceFilePath() != null && !script.getSourceFilePath().isBlank()) {
            Path source = Path.of(script.getSourceFilePath()).toAbsolutePath().normalize();
            Path directory = source.getParent();
            if (directory != null && directory.startsWith(storageRoot)) {
                deleteQuietly(directory);
            }
        }
    }

    public List<VideoScript> list(PlatformPrincipal principal) {
        return scriptMapper.selectList(
                new LambdaQueryWrapper<VideoScript>()
                        .eq(VideoScript::getOrganizationId, principal.organizationId())
                        .orderByDesc(VideoScript::getUpdatedAt)
                        .last("LIMIT 100")
        );
    }

    public VideoScriptDetail detail(String id, PlatformPrincipal principal) {
        VideoScript script = requireAccessibleScript(id, principal);
        List<VideoScriptShot> shots = shotMapper.selectList(
                new LambdaQueryWrapper<VideoScriptShot>()
                        .eq(VideoScriptShot::getOrganizationId, principal.organizationId())
                        .eq(VideoScriptShot::getScriptId, id)
                        .orderByAsc(VideoScriptShot::getSequenceNo)
        );
        return new VideoScriptDetail(script, shots);
    }

    public ShotPreview shotPreview(String scriptId, String shotId, PlatformPrincipal principal) {
        VideoScript script = requireAccessibleScript(scriptId, principal);
        VideoScriptShot shot = shotMapper.selectOne(
                new LambdaQueryWrapper<VideoScriptShot>()
                        .eq(VideoScriptShot::getOrganizationId, principal.organizationId())
                        .eq(VideoScriptShot::getScriptId, scriptId)
                        .eq(VideoScriptShot::getId, shotId)
                        .last("LIMIT 1")
        );
        if (shot == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "分镜不存在");
        }
        if (script.getSourceFilePath() == null || script.getSourceFilePath().isBlank()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "该脚本没有视频抽帧预览");
        }
        Path source = Path.of(script.getSourceFilePath()).toAbsolutePath().normalize();
        Path exactPreview = source.getParent() == null ? null : source.getParent().resolve("shot-previews")
                .resolve(String.format(Locale.ROOT, "shot-%03d.jpg", (int) sourceNumber(shot, "sourcePreviewSequence", shot.getSequenceNo()))).normalize();
        if (exactPreview != null && exactPreview.startsWith(storageRoot) && Files.isRegularFile(exactPreview)) {
            try {
                return new ShotPreview(exactPreview, Files.getLastModifiedTime(exactPreview).toInstant());
            } catch (IOException exception) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取分镜预览失败");
            }
        }
        if (exactPreview != null && exactPreview.startsWith(storageRoot) && Files.isDirectory(exactPreview.getParent())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "本次分镜预览尚未生成");
        }
        // Older scripts have uniformly sampled analysis frames instead of
        // the dedicated, timestamp-matched previews generated after watch.
        Path frameDirectory = source.getParent() == null ? null : source.getParent().resolve("frames").normalize();
        if (frameDirectory == null || !frameDirectory.startsWith(storageRoot) || !Files.isDirectory(frameDirectory)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "视频抽帧预览不存在");
        }
        List<Path> frames;
        try (var files = Files.list(frameDirectory)) {
            frames = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpg"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取分镜预览失败");
        }
        if (frames.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "视频抽帧预览不存在");
        }

        List<VideoScriptShot> allShots = shotMapper.selectList(
                new LambdaQueryWrapper<VideoScriptShot>()
                        .eq(VideoScriptShot::getOrganizationId, principal.organizationId())
                        .eq(VideoScriptShot::getScriptId, scriptId)
                        .orderByAsc(VideoScriptShot::getSequenceNo)
        );
        double timelineEnd = allShots.stream()
                .map(VideoScriptShot::getEndSecond)
                .filter(value -> value != null)
                .mapToDouble(BigDecimal::doubleValue)
                .max()
                .orElse(0.0);
        timelineEnd = sourceNumber(shot, "sourceDurationSeconds", timelineEnd);
        double midpoint = sourceNumber(shot, "sourceTimestampSeconds", midpoint(shot));
        int frameIndex;
        if (timelineEnd > 0.0 && midpoint >= 0.0) {
            frameIndex = (int) Math.round((midpoint / timelineEnd) * (frames.size() - 1));
        } else {
            int position = 0;
            for (int index = 0; index < allShots.size(); index++) {
                if (allShots.get(index).getSequenceNo() == shot.getSequenceNo()) {
                    position = index;
                    break;
                }
            }
            frameIndex = allShots.size() <= 1
                    ? 0
                    : (int) Math.round(position * (frames.size() - 1.0) / (allShots.size() - 1.0));
        }
        frameIndex = Math.max(0, Math.min(frames.size() - 1, frameIndex));
        Path preview = frames.get(frameIndex);
        try {
            return new ShotPreview(preview, Files.getLastModifiedTime(preview).toInstant());
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取分镜预览失败");
        }
    }

    @Transactional
    public VideoScriptDetail deleteShot(String scriptId, String shotId, PlatformPrincipal principal) {
        VideoScript script = scriptMapper.selectOne(new LambdaQueryWrapper<VideoScript>()
                .eq(VideoScript::getId, scriptId).eq(VideoScript::getOrganizationId, principal.organizationId())
                .last("FOR UPDATE"));
        if (script == null) throw new BusinessException(HttpStatus.NOT_FOUND, "视频脚本不存在");
        if (!Set.of("READY", "DRAFT").contains(script.getStatus()))
            throw new BusinessException(HttpStatus.CONFLICT, "当前脚本尚未完成，暂时不能删除分镜");
        List<VideoScriptShot> shots = shotMapper.selectList(new LambdaQueryWrapper<VideoScriptShot>()
                .eq(VideoScriptShot::getScriptId, scriptId)
                .eq(VideoScriptShot::getOrganizationId, principal.organizationId())
                .orderByAsc(VideoScriptShot::getSequenceNo));
        VideoScriptShot removed = shots.stream().filter(shot -> shotId.equals(shot.getId())).findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "分镜不存在，可能已被删除"));
        if (shots.size() <= 1) throw new BusinessException(HttpStatus.CONFLICT, "至少保留一个分镜");
        if (shots.stream().anyMatch(shot -> shot.getEndSecond() == null || shot.getDurationSeconds() == null
                || shot.getDurationSeconds().signum() <= 0))
            throw new BusinessException(HttpStatus.CONFLICT, "分镜时长无效，请先修正脚本");
        BigDecimal total = shots.stream().map(VideoScriptShot::getEndSecond).max(BigDecimal::compareTo).orElseThrow();
        List<VideoScriptShot> remaining = shots.stream().filter(shot -> !shotId.equals(shot.getId())).toList();
        BigDecimal weight = remaining.stream().map(VideoScriptShot::getDurationSeconds)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weight.signum() <= 0 || total.signum() <= 0)
            throw new BusinessException(HttpStatus.CONFLICT, "分镜时长无效，请先修正脚本");
        shotMapper.deleteById(removed.getId());
        BigDecimal elapsed = BigDecimal.ZERO, cumulative = BigDecimal.ZERO;
        // Ascending compaction is safe for the unique (script_id, sequence_no) index after deletion.
        for (int index = 0; index < remaining.size(); index++) {
            VideoScriptShot shot = remaining.get(index);
            Map<String, Object> prompt = new LinkedHashMap<>();
            if (shot.getImagePromptJson() != null) {
                try { prompt.putAll(objectMapper.readValue(shot.getImagePromptJson(), Map.class)); }
                catch (Exception ignored) { prompt.put("prompt", shot.getSceneDescription()); }
            }
            prompt.putIfAbsent("sourcePreviewSequence", shot.getSequenceNo());
            prompt.putIfAbsent("sourceTimestampSeconds", midpoint(shot));
            prompt.putIfAbsent("sourceDurationSeconds", total);
            cumulative = cumulative.add(shot.getDurationSeconds());
            BigDecimal end = index == remaining.size() - 1 ? total
                    : total.multiply(cumulative).divide(weight, 3, RoundingMode.HALF_UP);
            if (end.compareTo(elapsed) <= 0)
                throw new BusinessException(HttpStatus.CONFLICT, "脚本时长过短，无法重新分配");
            try { shot.retime(index + 1, elapsed, end, objectMapper.writeValueAsString(prompt)); }
            catch (BusinessException exception) { throw exception; }
            catch (Exception exception) { throw new IllegalStateException("分镜时长保存失败", exception); }
            shotMapper.updateById(shot);
            elapsed = end;
        }
        script.saveEditedJson(rebuildScriptJson(script, remaining));
        scriptMapper.updateById(script);
        return new VideoScriptDetail(script, remaining);
    }

    private double sourceNumber(VideoScriptShot shot, String key, double fallback) {
        try {
            Object value = objectMapper.readValue(shot.getImagePromptJson(), Map.class).get(key);
            return value instanceof Number number ? number.doubleValue() : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    @Transactional
    public VideoScriptShot updateShot(
            String scriptId,
            String shotId,
            ShotUpdate update,
            PlatformPrincipal principal
    ) {
        VideoScript script = requireAccessibleScript(scriptId, principal);
        if (Set.of("QUEUED", "EXTRACTING_FRAMES", "AI_ANALYZING").contains(script.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "视频正在拆解，暂时不能编辑分镜");
        }
        VideoScriptShot shot = shotMapper.selectOne(
                new LambdaQueryWrapper<VideoScriptShot>()
                        .eq(VideoScriptShot::getOrganizationId, principal.organizationId())
                        .eq(VideoScriptShot::getScriptId, scriptId)
                        .eq(VideoScriptShot::getId, shotId)
                        .last("LIMIT 1")
        );
        if (shot == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "分镜不存在");
        }
        String stage = normalizeStage(update.stage());
        shot.updateContent(
                stage,
                required(update.title(), "分镜标题", 160),
                required(update.sceneDescription(), "画面描述", 8000),
                update.motionDescription() == null
                        ? shot.getMotionDescription()
                        : trimTo(update.motionDescription(), 8000),
                update.motionIntensity() == null
                        ? shot.getMotionIntensity()
                        : normalizeMotionIntensity(update.motionIntensity()),
                trimTo(update.narration(), 8000),
                trimTo(update.shotSize(), 80),
                trimTo(update.cameraMovement(), 120),
                trimTo(update.transitionDescription(), 500),
                trimTo(update.emotion(), 120)
        );
        shotMapper.updateById(shot);
        List<VideoScriptShot> shots = shotMapper.selectList(
                new LambdaQueryWrapper<VideoScriptShot>()
                        .eq(VideoScriptShot::getOrganizationId, principal.organizationId())
                        .eq(VideoScriptShot::getScriptId, scriptId)
                        .orderByAsc(VideoScriptShot::getSequenceNo)
        );
        script.saveEditedJson(rebuildScriptJson(script, shots));
        scriptMapper.updateById(script);
        return shot;
    }

    public VideoScript status(String id, PlatformPrincipal principal) {
        return requireAccessibleScript(id, principal);
    }

    private VideoScript requireAccessibleScript(String id, PlatformPrincipal principal) {
        return requireScript(id, principal.organizationId());
    }

    private VideoScript requireScript(String id, String organizationId) {
        VideoScript script = scriptMapper.selectById(id);
        if (script == null || !organizationId.equals(script.getOrganizationId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "视频脚本不存在");
        }
        return script;
    }

    private VideoScript requireOwnedScript(String id, PlatformPrincipal principal) {
        VideoScript script = requireScript(id, principal.organizationId());
        if (!principal.userId().equals(script.getCreatedBy())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权操作该视频拆解任务");
        }
        return script;
    }

    private void deleteQuietly(Path directory) {
        try {
            if (!Files.exists(directory)) return;
            try (var paths = Files.walk(directory)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best-effort cleanup after a rejected duplicate request.
                    }
                });
            }
        } catch (IOException ignored) {
            // The scheduled file-retention cleanup will remove leftovers.
        }
    }

    private Path copyScriptFiles(VideoScript original, String copyId) {
        if (original.getSourceFilePath() == null || original.getSourceFilePath().isBlank()) return null;
        Path sourceFile = Path.of(original.getSourceFilePath()).toAbsolutePath().normalize();
        Path sourceDirectory = sourceFile.getParent();
        Path targetDirectory = storageRoot.resolve(original.getOrganizationId()).resolve(copyId).normalize();
        if (sourceDirectory == null || !sourceDirectory.startsWith(storageRoot)
                || !sourceFile.startsWith(sourceDirectory) || !Files.isRegularFile(sourceFile)
                || !targetDirectory.startsWith(storageRoot)) {
            throw new BusinessException(HttpStatus.CONFLICT, "原视频文件不存在，暂时无法复制版本");
        }
        try {
            List<Path> sourcePaths;
            try (var paths = Files.walk(sourceDirectory)) {
                sourcePaths = paths.sorted().toList();
            }
            for (Path sourcePath : sourcePaths) {
                Path targetPath = targetDirectory.resolve(sourceDirectory.relativize(sourcePath)).normalize();
                if (!targetPath.startsWith(targetDirectory)) {
                    throw new IOException("复制目标路径越界");
                }
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                    continue;
                }
                Files.createDirectories(targetPath.getParent());
                try {
                    Files.createLink(targetPath, sourcePath);
                } catch (IOException | UnsupportedOperationException | SecurityException linkError) {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return targetDirectory.resolve(sourceDirectory.relativize(sourceFile)).normalize();
        } catch (IOException exception) {
            deleteQuietly(targetDirectory);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "复制脚本素材失败");
        }
    }

    private void validateVideo(MultipartFile video) {
        if (video == null || video.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请选择需要拆解的视频");
        }
        if (video.getSize() > MAX_VIDEO_BYTES) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "视频不能超过 100MB");
        }
        String name = safeFileName(video.getOriginalFilename());
        String extension = extension(name);
        String contentType = video.getContentType() == null ? "" : video.getContentType().toLowerCase(Locale.ROOT);
        if (!VIDEO_EXTENSIONS.contains(extension) || (!contentType.isBlank() && !contentType.startsWith("video/"))) {
            throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持 MP4、MOV、M4V、WebM、AVI 或 MKV 视频");
        }
    }

    static ClipWindow clipWindow(BigDecimal sourceDuration, BigDecimal requestedStart) {
        return clipWindow(sourceDuration, requestedStart, null);
    }

    static ClipWindow clipWindow(
            BigDecimal sourceDuration,
            BigDecimal requestedStart,
            BigDecimal requestedEnd
    ) {
        if (sourceDuration == null || sourceDuration.signum() <= 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "视频时长无效");
        }
        BigDecimal normalizedStart = normalizeClipSecond(requestedStart, BigDecimal.ZERO);
        if (normalizedStart.signum() < 0) {
            if (normalizedStart.abs().compareTo(CLIP_BOUNDARY_TOLERANCE_SECONDS) <= 0) {
                normalizedStart = BigDecimal.ZERO;
            } else {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "截取开始位置不能小于 0 秒");
            }
        }
        if (requestedEnd != null) {
            return explicitClipWindow(sourceDuration, normalizedStart, requestedEnd);
        }

        if (sourceDuration.compareTo(ANALYSIS_CLIP_SECONDS) <= 0) {
            if (normalizedStart.signum() != 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "不足 15 秒的视频将使用完整视频，开始位置必须为 0");
            }
            return new ClipWindow(BigDecimal.ZERO, sourceDuration, false);
        }
        BigDecimal maximumStart = sourceDuration.subtract(ANALYSIS_CLIP_SECONDS);
        if (normalizedStart.compareTo(maximumStart) > 0) {
            if (normalizedStart.subtract(maximumStart).compareTo(CLIP_BOUNDARY_TOLERANCE_SECONDS) <= 0) {
                normalizedStart = maximumStart;
            } else {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "截取开始位置最晚为 " + displaySeconds(maximumStart) + " 秒，截取片段固定为 15 秒"
                );
            }
        }
        return new ClipWindow(normalizedStart, normalizedStart.add(ANALYSIS_CLIP_SECONDS), true);
    }

    private static ClipWindow explicitClipWindow(
            BigDecimal sourceDuration,
            BigDecimal normalizedStart,
            BigDecimal requestedEnd
    ) {
        BigDecimal normalizedEnd = normalizeClipSecond(requestedEnd, null);
        BigDecimal sourceDelta = normalizedEnd.subtract(sourceDuration).abs();
        if (sourceDelta.compareTo(CLIP_BOUNDARY_TOLERANCE_SECONDS) <= 0) {
            normalizedEnd = sourceDuration;
        } else if (normalizedEnd.compareTo(sourceDuration) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "截取结束位置不能超过源视频时长");
        }
        if (normalizedStart.compareTo(sourceDuration) >= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "截取开始位置必须小于源视频时长");
        }
        if (normalizedEnd.signum() <= 0 || normalizedEnd.compareTo(normalizedStart) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "截取结束位置必须大于开始位置");
        }

        BigDecimal duration = normalizedEnd.subtract(normalizedStart);
        if (duration.compareTo(MIN_ANALYSIS_CLIP_SECONDS) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "视频截取片段不能短于 0.1 秒");
        }
        if (duration.compareTo(ANALYSIS_CLIP_SECONDS) > 0) {
            BigDecimal overflow = duration.subtract(ANALYSIS_CLIP_SECONDS);
            if (overflow.compareTo(CLIP_BOUNDARY_TOLERANCE_SECONDS) <= 0) {
                normalizedEnd = normalizedStart.add(ANALYSIS_CLIP_SECONDS);
            } else {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "视频截取片段不能超过 15 秒");
            }
        }

        boolean fullSource = normalizedStart.signum() == 0 && normalizedEnd.compareTo(sourceDuration) == 0;
        return new ClipWindow(normalizedStart, normalizedEnd, !fullSource);
    }

    private static BigDecimal normalizeClipSecond(BigDecimal value, BigDecimal fallback) {
        if (value == null) {
            if (fallback != null) return fallback;
            throw new BusinessException(HttpStatus.BAD_REQUEST, "截取结束位置不能为空");
        }
        return value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal seconds(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "视频时长无效");
        }
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static String displaySeconds(BigDecimal value) {
        return value.setScale(Math.min(Math.max(value.scale(), 0), 3), RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "视频文件读取失败");
        }
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeFileName(String value) {
        String name = value == null ? "video.mp4" : Path.of(value).getFileName().toString().trim();
        name = name.replaceAll("[\\r\\n\\u0000]", "_");
        return name.isBlank() ? "video.mp4" : name.substring(0, Math.min(name.length(), 240));
    }

    private String trimTo(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.substring(0, Math.min(normalized.length(), max));
    }

    private String required(String value, String label, int max) {
        String normalized = trimTo(value, max);
        if (normalized == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + "不能为空");
        }
        return normalized;
    }

    private String normalizeStage(String value) {
        String normalized = value == null ? "CONTENT" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("HOOK", "SOLUTION", "TRUST", "CTA", "CONTENT").contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "分镜阶段无效");
        }
        return normalized;
    }

    static String normalizePresentationMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MODEL_WEAR", "PRODUCT_ONLY", "CAST_PRODUCT").contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请选择人物穿搭、纯商品或人物＋商品脚本");
        }
        return normalized;
    }

    private String normalizeMotionIntensity(String value) {
        String normalized = trimTo(value, 20);
        if (normalized == null) return null;
        if (!Set.of("静止", "微幅", "小幅", "中幅", "大幅").contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "动作幅度无效");
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private String rebuildScriptJson(VideoScript script, List<VideoScriptShot> shots) {
        return rebuildScriptJson(
                script.getScriptJson(), script.getName(), script.getSummary(), script.getVisualTone(), shots
        );
    }

    @SuppressWarnings("unchecked")
    private String rebuildScriptJson(
            String existingJson,
            String name,
            String summary,
            String visualTone,
            List<VideoScriptShot> shots
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                root.putAll(objectMapper.readValue(existingJson, Map.class));
            } catch (Exception ignored) {
                // Rebuild a clean JSON document from persisted shot data.
            }
        }
        root.put("title", name);
        root.put("summary", summary);
        root.put("globalVisualTone", visualTone);
        List<Map<String, Object>> shotValues = new ArrayList<>();
        for (VideoScriptShot shot : shots) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sequence", shot.getSequenceNo());
            value.put("sourceSequence", (int) sourceNumber(shot, "sourcePreviewSequence", shot.getSequenceNo()));
            value.put("stage", shot.getStage());
            value.put("title", shot.getTitle());
            value.put("startSecond", shot.getStartSecond());
            value.put("endSecond", shot.getEndSecond());
            value.put("durationSeconds", shot.getDurationSeconds());
            value.put("sceneDescription", shot.getSceneDescription());
            value.put("motionDescription", shot.getMotionDescription());
            value.put("motionIntensity", shot.getMotionIntensity());
            value.put("narration", shot.getNarration());
            value.put("shotSize", shot.getShotSize());
            value.put("cameraMovement", shot.getCameraMovement());
            value.put("transitionDescription", shot.getTransitionDescription());
            value.put("emotion", shot.getEmotion());
            value.put("imagePrompt", parseJsonValue(shot.getImagePromptJson()));
            shotValues.add(value);
        }
        root.put("shots", shotValues);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "分镜 JSON 保存失败");
        }
    }

    private Object parseJsonValue(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "手动分镜 JSON 保存失败");
        }
    }

    private double midpoint(VideoScriptShot shot) {
        double start = shot.getStartSecond() == null ? -1.0 : shot.getStartSecond().doubleValue();
        double end = shot.getEndSecond() == null ? -1.0 : shot.getEndSecond().doubleValue();
        if (start >= 0.0 && end >= start) return (start + end) / 2.0;
        return start;
    }

    public record VideoScriptDetail(VideoScript script, List<VideoScriptShot> shots) {
    }

    record ClipWindow(BigDecimal startSecond, BigDecimal endSecond, boolean trimmed) {
        BigDecimal durationSeconds() {
            return endSecond.subtract(startSecond);
        }
    }

    public record ShotPreview(Path path, Instant lastModified) {
    }

    public record ShotUpdate(
            String stage,
            String title,
            String sceneDescription,
            String motionDescription,
            String motionIntensity,
            String narration,
            String shotSize,
            String cameraMovement,
            String transitionDescription,
            String emotion
    ) {
    }

    public record ManualShot(
            String stage,
            String title,
            BigDecimal startSecond,
            BigDecimal endSecond,
            String sceneDescription,
            String motionDescription,
            String narration,
            String shotSize,
            String cameraMovement,
            String emotion
    ) {
    }
}
