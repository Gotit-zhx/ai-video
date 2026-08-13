package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class AiVideoTaskService {
    private static final int MAX_ITEMS = 30;
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_AUDIO_BYTES = 15L * 1024 * 1024;

    private final AiVideoTaskMapper taskMapper;
    private final AiVideoTaskItemMapper itemMapper;
    private final AiVideoTaskAssetMapper assetMapper;
    private final VideoScriptMapper scriptMapper;
    private final VideoScriptShotMapper shotMapper;
    private final AiVideoImageProcessor processor;
    private final AiVideoGenerationService videoGenerationService;
    private final ObjectMapper objectMapper;
    private final Path storageRoot;

    public AiVideoTaskService(AiVideoTaskMapper taskMapper, AiVideoTaskItemMapper itemMapper,
                              AiVideoTaskAssetMapper assetMapper, VideoScriptMapper scriptMapper,
                              VideoScriptShotMapper shotMapper, AiVideoImageProcessor processor, ObjectMapper objectMapper,
                              AiVideoGenerationService videoGenerationService,
                              @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storageRoot) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.scriptMapper = scriptMapper;
        this.shotMapper = shotMapper;
        this.processor = processor;
        this.videoGenerationService = videoGenerationService;
        this.objectMapper = objectMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public AiVideoTask create(CreateCommand command, PlatformPrincipal principal) {
        VideoScript script = scriptMapper.selectOne(new LambdaQueryWrapper<VideoScript>()
                .eq(VideoScript::getId, command.scriptId())
                .eq(VideoScript::getOrganizationId, principal.organizationId()));
        if (script == null || !"READY".equals(script.getStatus()) || script.getScriptJson() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "请选择已经完成拆解的视频脚本");
        }
        long shotCount = shotMapper.selectCount(new LambdaQueryWrapper<com.yingdao.aicommerce.video.domain.VideoScriptShot>()
                .eq(com.yingdao.aicommerce.video.domain.VideoScriptShot::getScriptId, script.getId()));
        if (shotCount != 9) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前脚本不是完整 9 个分镜，请先补足分镜");
        }
        String name = normalize(command.name(), script.getName() + " · 视频任务", 160);
        String generationMode = normalizeGenerationMode(command.generationMode());
        String backgroundMode = normalizeBackgroundMode(command.backgroundMode());
        String productCategory = normalize(command.productCategory(), "MODEL_WEAR".equals(generationMode) ? "服装" : "通用商品", 80);
        // These legacy columns remain for API/database compatibility. New tasks never use a task-level
        // scene, interaction or hand switch because each storyboard shot is authoritative.
        String sceneType = "按分镜逐镜执行";
        String interactionType = "按分镜逐镜执行";
        boolean allowHand = false;
        String aspectRatio = normalizeAspectRatio(command.aspectRatio());
        String resolution = normalizeResolution(command.resolution());
        boolean useScriptReferenceVideo = command.useScriptReferenceVideo();
        if (useScriptReferenceVideo && !"PRODUCT_ONLY".equals(generationMode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "脚本视频复刻仅支持商品展示模式");
        }
        if (useScriptReferenceVideo && (!"VIDEO_ANALYSIS".equals(script.getSourceType())
                || script.getSourceFilePath() == null || script.getSourceContentType() == null
                || !script.getSourceContentType().startsWith("video/"))) {
            throw new BusinessException(HttpStatus.CONFLICT, "所选脚本没有可提交的原始参考视频");
        }
        String generationRules = json(Map.of("mode", generationMode, "backgroundMode", backgroundMode, "productCategory", productCategory,
                "scenePolicy", "PER_SHOT", "interactionPolicy", "PER_SHOT", "handPolicy", "PER_SHOT",
                "aspectRatio", aspectRatio, "resolution", resolution, "useScriptReferenceVideo", useScriptReferenceVideo));
        String qualityRules = json(Map.of("singleProduct", true, "preserveLogoAndText", true,
                "preserveColorAndShape", true, "preventExtraPeople", !"MODEL_WEAR".equals(generationMode),
                "preventExtraHands", true, "preventDeformation", true,
                "preventFloatingAndCollision", true));
        AiVideoTask task = new AiVideoTask(principal.organizationId(), name, script.getId(), script.getVersion(),
                script.getName(), script.getScriptJson(), normalizeNullable(command.targetMarket(), 80), generationMode,
                backgroundMode, productCategory, sceneType, interactionType, allowHand, aspectRatio, resolution, useScriptReferenceVideo,
                generationRules, qualityRules,
                principal.userId());
        taskMapper.insert(task);
        return task;
    }

    public List<AiVideoTask> list(PlatformPrincipal principal) {
        return taskMapper.selectList(new LambdaQueryWrapper<AiVideoTask>()
                .eq(AiVideoTask::getOrganizationId, principal.organizationId())
                .eq(AiVideoTask::getCreatedBy, principal.userId())
                .orderByDesc(AiVideoTask::getUpdatedAt));
    }

    public TaskDetail detail(String id, PlatformPrincipal principal) {
        AiVideoTask task = requireOwned(id, principal);
        List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getTaskId, id).orderByAsc(AiVideoTaskItem::getCreatedAt));
        for (AiVideoTaskItem item : items) {
            if (item.getVideoPromptText() == null || item.getVideoPromptText().isBlank()
                    || isLegacyGeneratedPrompt(task, item)) {
                item.updateVideoPrompt(defaultVideoPrompt(task, item.getName()));
                itemMapper.updateById(item);
            }
            if ("VIDEO_FAILED".equals(item.getStatus())
                    && (item.getErrorMessage() == null || item.getErrorMessage().isBlank()
                        || item.getErrorMessage().contains("所有已启用的视频线路均未生成成功"))) {
                String providerFailure = videoGenerationService.latestFailureMessage(item.getId());
                if (!providerFailure.equals(item.getErrorMessage())) {
                    item.updateVideoFailureMessage(providerFailure);
                    itemMapper.updateById(item);
                }
            }
        }
        List<AiVideoTaskAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getTaskId, id)
                .orderByAsc(AiVideoTaskAsset::getItemId)
                .orderByAsc(AiVideoTaskAsset::getAssetType)
                .orderByAsc(AiVideoTaskAsset::getShotSequence));
        boolean firstFrameAdded = false;
        if (!"MODEL_WEAR".equals(task.getGenerationMode())) {
            for (AiVideoTaskAsset asset : List.copyOf(assets)) {
                if (!"PRODUCT_SCENE_IMAGE".equals(asset.getAssetType())) continue;
                boolean exists = assets.stream().anyMatch(candidate -> candidate.getItemId().equals(asset.getItemId())
                        && "STORYBOARD_FIRST_FRAME".equals(candidate.getAssetType()));
                if (exists) continue;
                try {
                    firstFrameAdded |= processor.ensureProductFirstFrame(task, asset) != null;
                } catch (IllegalStateException ignored) {
                    // Old or incomplete artifacts remain visible; a later regeneration can repair them.
                }
            }
        }
        for (AiVideoTaskAsset asset : List.copyOf(assets)) {
            if (!"MODEL_WEAR".equals(task.getGenerationMode())) break;
            if (!"STORYBOARD_GRID".equals(asset.getAssetType())) continue;
            boolean exists = assets.stream().anyMatch(candidate -> candidate.getItemId().equals(asset.getItemId())
                    && "STORYBOARD_FIRST_FRAME".equals(candidate.getAssetType()));
            if (exists) continue;
            try {
                firstFrameAdded |= processor.ensureStoryboardFirstFrame(task, asset) != null;
            } catch (IllegalStateException ignored) {
                // Old or incomplete artifacts remain visible; a later regeneration can repair them.
            }
        }
        if (firstFrameAdded) {
            assets = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                    .eq(AiVideoTaskAsset::getTaskId, id)
                    .orderByAsc(AiVideoTaskAsset::getItemId)
                    .orderByAsc(AiVideoTaskAsset::getAssetType)
                    .orderByAsc(AiVideoTaskAsset::getShotSequence));
        }
        return new TaskDetail(task, items, assets, videoGenerationService.currentRunSubmittedAt(id),
                videoGenerationService.currentRunProviderTaskIds(id));
    }

    public List<AiVideoTaskItem> upload(String id, List<MultipartFile> files, PlatformPrincipal principal) {
        AiVideoTask task = requireOwned(id, principal);
        if (!List.of("DRAFT", "READY").contains(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前任务状态不能继续上传衣服素材");
        }
        if (files == null || files.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "请选择商品图片");
        long existing = itemMapper.selectCount(new LambdaQueryWrapper<AiVideoTaskItem>().eq(AiVideoTaskItem::getTaskId, id));
        if (existing + files.size() > MAX_ITEMS) throw new BusinessException(HttpStatus.BAD_REQUEST, "单个任务最多上传 30 款衣服");
        List<AiVideoTaskItem> created = new ArrayList<>();
        int sequence = (int) existing + 1;
        for (MultipartFile file : files) {
            validateImage(file);
            String originalName = safeFileName(file.getOriginalFilename());
            String displayName = stripExtension(originalName);
            String sku = "SKU-" + String.format("%03d", sequence++);
            AiVideoTaskItem item = new AiVideoTaskItem(task.getOrganizationId(), task.getId(), displayName, sku,
                    originalName, "pending", safeContentType(file), file.getSize());
            item.updateVideoPrompt(defaultVideoPrompt(task, displayName));
            Path directory = itemDirectory(task, item);
            Path source = directory.resolve("source" + extension(originalName));
            try {
                Files.createDirectories(directory);
                file.transferTo(source);
            } catch (IOException exception) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "衣服图片保存失败");
            }
            item.attachSource(source.toString());
            itemMapper.insert(item);
            created.add(item);
        }
        int total = Math.toIntExact(itemMapper.selectCount(new LambdaQueryWrapper<AiVideoTaskItem>().eq(AiVideoTaskItem::getTaskId, id)));
        task.updateItemCount(total);
        taskMapper.updateById(task);
        return created;
    }

    public AiVideoTask uploadMusic(String id, MultipartFile file, PlatformPrincipal principal) {
        AiVideoTask task = requireOwned(id, principal);
        if (!List.of("DRAFT", "READY", "IMAGE_READY", "FAILED").contains(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前任务正在执行，暂时不能更换配乐");
        }
        validateAudio(file);
        String originalName = safeFileName(file.getOriginalFilename());
        String extension = audioExtension(originalName);
        Path directory = taskDirectory(task).resolve("music");
        Path output = directory.resolve("background" + extension).normalize();
        if (!output.startsWith(storageRoot)) throw new BusinessException(HttpStatus.BAD_REQUEST, "配乐目录不安全");
        String previous = task.getMusicFilePath();
        try {
            Files.createDirectories(directory);
            try (var input = file.getInputStream()) {
                Files.copy(input, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (previous != null && !previous.equals(output.toString())) {
                Path old = Path.of(previous).toAbsolutePath().normalize();
                if (old.startsWith(storageRoot)) Files.deleteIfExists(old);
            }
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "配乐保存失败");
        }
        task.attachMusic(originalName, output.toString(), audioContentType(extension), file.getSize());
        taskMapper.updateById(task);
        return task;
    }

    public AiVideoTaskItem updateVideoPrompt(String taskId, String itemId, String value, PlatformPrincipal principal) {
        requireOwned(taskId, principal);
        AiVideoTaskItem item = itemMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getId, itemId)
                .eq(AiVideoTaskItem::getTaskId, taskId)
                .eq(AiVideoTaskItem::getOrganizationId, principal.organizationId()));
        if (item == null) throw new BusinessException(HttpStatus.NOT_FOUND, "衣服素材不存在");
        String prompt = value == null ? "" : value.trim();
        if (prompt.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "视频提示词不能为空");
        if (prompt.length() > 20000) throw new BusinessException(HttpStatus.BAD_REQUEST, "视频提示词不能超过 20000 个字符");
        item.updateVideoPrompt(prompt);
        itemMapper.updateById(item);
        return item;
    }

    public AiVideoTask startImages(String id, PlatformPrincipal principal) {
        AiVideoTask task = requireOwned(id, principal);
        if (!List.of("READY", "FAILED", "IMAGE_READY").contains(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前任务还不能启动生图");
        }
        long count = itemMapper.selectCount(new LambdaQueryWrapper<AiVideoTaskItem>().eq(AiVideoTaskItem::getTaskId, id));
        if (count == 0) throw new BusinessException(HttpStatus.CONFLICT, "请先上传商品素材");
        try {
            processor.prepare(task);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "你已有一条 AI 视频生图任务正在执行");
        } catch (IllegalStateException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, exception.getMessage());
        }
        processor.generate(task.getId());
        return task;
    }

    public AiVideoTask startVideos(String id, PlatformPrincipal principal) {
        AiVideoTask task = requireOwned(id, principal);
        if (!List.of("IMAGE_READY", "FAILED", "VIDEO_READY").contains(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前任务还不能启动视频生成");
        }
        try {
            return videoGenerationService.start(task);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "你已有一条视频任务正在执行");
        } catch (IllegalStateException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    public AiVideoTask startGeneration(String id, PlatformPrincipal principal) {
        AiVideoTask task = requireOwned(id, principal);
        if (List.of("IMAGE_GENERATING", "VIDEO_GENERATING").contains(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前视频生成流程已经在执行");
        }
        if (!List.of("READY", "FAILED", "IMAGE_READY", "VIDEO_READY").contains(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前任务还不能启动视频生成");
        }
        List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getTaskId, id));
        if (items.isEmpty()) throw new BusinessException(HttpStatus.CONFLICT, "请先上传商品素材");
        String primaryAssetType = "MODEL_WEAR".equals(task.getGenerationMode())
                ? "MODEL_IMAGE" : "PRODUCT_SCENE_IMAGE";
        boolean imagesReady = items.stream().allMatch(item -> hasAsset(item.getId(), primaryAssetType)
                && hasAsset(item.getId(), "STORYBOARD_GRID")
                && hasAsset(item.getId(), "STORYBOARD_FIRST_FRAME"));
        if (imagesReady) return startVideos(id, principal);
        task.requestAutoStartVideo();
        taskMapper.updateById(task);
        return startImages(id, principal);
    }

    private boolean hasAsset(String itemId, String assetType) {
        return assetMapper.selectCount(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, itemId)
                .eq(AiVideoTaskAsset::getAssetType, assetType)) > 0;
    }

    public AiVideoTask regenerateStoryboard(String taskId, String itemId, PlatformPrincipal principal) {
        AiVideoTask task = requireOwned(taskId, principal);
        if ("IMAGE_GENERATING".equals(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前有图片正在生成，请完成后再重建九宫格");
        }
        AiVideoTaskItem item = itemMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getId, itemId)
                .eq(AiVideoTaskItem::getTaskId, taskId)
                .eq(AiVideoTaskItem::getOrganizationId, principal.organizationId()));
        if (item == null) throw new BusinessException(HttpStatus.NOT_FOUND, "衣服素材不存在");
        String primaryAssetType = "MODEL_WEAR".equals(task.getGenerationMode()) ? "MODEL_IMAGE" : "PRODUCT_SCENE_IMAGE";
        long modelCount = assetMapper.selectCount(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, itemId)
                .eq(AiVideoTaskAsset::getAssetType, primaryAssetType));
        if (modelCount == 0) throw new BusinessException(HttpStatus.CONFLICT, "请先生成主展示图");
        List<AiVideoTaskAsset> storyboards = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, itemId)
                .in(AiVideoTaskAsset::getAssetType, List.of("STORYBOARD_GRID", "STORYBOARD_GUIDE", "STORYBOARD_FIRST_FRAME")));
        for (AiVideoTaskAsset asset : storyboards) {
            assetMapper.deleteById(asset.getId());
            try {
                Path path = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
                if (path.startsWith(storageRoot)) Files.deleteIfExists(path);
            } catch (IOException ignored) { }
        }
        return startImages(taskId, principal);
    }

    public AiVideoTask regenerateFirstFrame(String taskId, String itemId, PlatformPrincipal principal) {
        AiVideoTask task = requireOwned(taskId, principal);
        if ("IMAGE_GENERATING".equals(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前有图片正在生成，请完成后再重做首帧");
        }
        AiVideoTaskItem item = itemMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getId, itemId)
                .eq(AiVideoTaskItem::getTaskId, taskId)
                .eq(AiVideoTaskItem::getOrganizationId, principal.organizationId()));
        if (item == null) throw new BusinessException(HttpStatus.NOT_FOUND, "商品素材不存在");
        String primaryAssetType = "MODEL_WEAR".equals(task.getGenerationMode()) ? "MODEL_IMAGE" : "PRODUCT_SCENE_IMAGE";
        List<AiVideoTaskAsset> generatedAssets = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, itemId)
                .in(AiVideoTaskAsset::getAssetType, List.of(primaryAssetType, "STORYBOARD_GRID", "STORYBOARD_GUIDE", "STORYBOARD_FIRST_FRAME")));
        for (AiVideoTaskAsset asset : generatedAssets) {
            assetMapper.deleteById(asset.getId());
            try {
                Path path = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
                if (path.startsWith(storageRoot)) Files.deleteIfExists(path);
            } catch (IOException ignored) { }
        }
        return startImages(taskId, principal);
    }

    public void delete(String id, PlatformPrincipal principal) {
        AiVideoTask task = requireOwned(id, principal);
        if (List.of("IMAGE_GENERATING", "VIDEO_GENERATING").contains(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "任务生成中，暂时不能删除");
        }
        taskMapper.deleteById(task.getId());
        Path directory = storageRoot.resolve(task.getOrganizationId()).resolve(task.getId()).normalize();
        if (!directory.startsWith(storageRoot) || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public AssetFile asset(String taskId, String assetId, PlatformPrincipal principal) {
        requireOwned(taskId, principal);
        AiVideoTaskAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getId, assetId)
                .eq(AiVideoTaskAsset::getTaskId, taskId)
                .eq(AiVideoTaskAsset::getOrganizationId, principal.organizationId()));
        if (asset == null) throw new BusinessException(HttpStatus.NOT_FOUND, "生成图片不存在");
        Path path = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "生成图片不存在");
        }
        return new AssetFile(path, asset.getContentType(), asset.getSizeBytes());
    }

    public SourceFile source(String taskId, String itemId, PlatformPrincipal principal) {
        requireOwned(taskId, principal);
        AiVideoTaskItem item = itemMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getId, itemId).eq(AiVideoTaskItem::getTaskId, taskId));
        if (item == null) throw new BusinessException(HttpStatus.NOT_FOUND, "衣服素材不存在");
        Path path = Path.of(item.getSourceFilePath()).toAbsolutePath().normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) throw new BusinessException(HttpStatus.NOT_FOUND, "衣服素材不存在");
        return new SourceFile(path, item.getSourceContentType(), item.getSourceSizeBytes());
    }

    private AiVideoTask requireOwned(String id, PlatformPrincipal principal) {
        AiVideoTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiVideoTask>()
                .eq(AiVideoTask::getId, id)
                .eq(AiVideoTask::getOrganizationId, principal.organizationId())
                .eq(AiVideoTask::getCreatedBy, principal.userId()));
        if (task == null) throw new BusinessException(HttpStatus.NOT_FOUND, "AI 视频任务不存在");
        return task;
    }

    private Path itemDirectory(AiVideoTask task, AiVideoTaskItem item) {
        Path path = storageRoot.resolve(task.getOrganizationId()).resolve(task.getId()).resolve(item.getId()).normalize();
        if (!path.startsWith(storageRoot)) throw new BusinessException(HttpStatus.BAD_REQUEST, "任务目录不安全");
        return path;
    }

    private Path taskDirectory(AiVideoTask task) {
        Path path = storageRoot.resolve(task.getOrganizationId()).resolve(task.getId()).normalize();
        if (!path.startsWith(storageRoot)) throw new BusinessException(HttpStatus.BAD_REQUEST, "任务目录不安全");
        return path;
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "衣服图片不能为空");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "单张衣服图片不能超过 20MB");
        if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "只支持上传图片文件");
        }
    }

    private void validateAudio(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "请选择配乐文件");
        if (file.getSize() > MAX_AUDIO_BYTES) throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "配乐文件不能超过 15MB");
        audioExtension(safeFileName(file.getOriginalFilename()));
    }

    private String audioExtension(String value) {
        String lower = value == null ? "" : value.toLowerCase();
        if (lower.endsWith(".mp3")) return ".mp3";
        if (lower.endsWith(".wav")) return ".wav";
        throw new BusinessException(HttpStatus.BAD_REQUEST, "配乐仅支持 MP3 或 WAV，时长请控制在 2–15 秒");
    }

    private String audioContentType(String extension) {
        return ".wav".equals(extension) ? "audio/wav" : "audio/mpeg";
    }

    private String safeContentType(MultipartFile file) { return file.getContentType() == null ? "image/jpeg" : file.getContentType(); }
    private String safeFileName(String value) {
        String name = value == null ? "garment.jpg" : Path.of(value).getFileName().toString().replaceAll("[\\r\\n]", "");
        return name.substring(0, Math.min(name.length(), 200));
    }
    private String stripExtension(String value) { int dot = value.lastIndexOf('.'); return normalize(dot > 0 ? value.substring(0, dot) : value, "未命名衣服", 160); }
    private String extension(String value) { int dot = value.lastIndexOf('.'); return dot >= 0 && dot >= value.length() - 6 ? value.substring(dot).toLowerCase() : ".jpg"; }
    private String normalize(String value, String fallback, int max) { String v = value == null || value.isBlank() ? fallback : value.trim(); return v.substring(0, Math.min(v.length(), max)); }
    private String normalizeNullable(String value, int max) { return value == null || value.isBlank() ? null : value.trim().substring(0, Math.min(value.trim().length(), max)); }

    private String normalizeGenerationMode(String value) {
        String mode = value == null ? "MODEL_WEAR" : value.trim().toUpperCase();
        if (!List.of("MODEL_WEAR", "PRODUCT_ONLY", "PARTIAL_INTERACT").contains(mode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的生成模式");
        }
        return mode;
    }

    private String normalizeBackgroundMode(String value) {
        String mode = value == null || value.isBlank() ? "SCRIPT_ORIGINAL" : value.trim().toUpperCase();
        if (!List.of("SCRIPT_ORIGINAL", "AI_DESIGNED").contains(mode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的背景方式");
        }
        return mode;
    }

    private String normalizeAspectRatio(String value) {
        String ratio = value == null || value.isBlank() ? "9:16" : value.trim();
        if (!List.of("9:16", "1:1", "16:9").contains(ratio)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的画幅比例");
        }
        return ratio;
    }

    private String normalizeResolution(String value) {
        String resolution = value == null || value.isBlank() ? "720p" : value.trim();
        if (!List.of("720p", "1080p", "2K", "4K").contains(resolution)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的视频分辨率");
        }
        return resolution;
    }

    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "任务规则保存失败"); }
    }

    private String defaultVideoPrompt(AiVideoTask task, String garmentName) {
        if (!"MODEL_WEAR".equals(task.getGenerationMode())) return buildProductVideoPrompt(task, garmentName);
        return buildStoryboardAnimationPrompt(task, garmentName);
    }

    private String buildProductVideoPrompt(AiVideoTask task, String productName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【参考图片】\n");
        prompt.append("图1：从图2第 01 格裁出的商品首帧，是商品外观、位置、场景与初始状态的最高优先级锚点。\n");
        prompt.append("图2：4K 商品分镜九宫格，按从左到右、从上到下读取 01→09，仅表示同一件商品的连续展示过程。\n");
        prompt.append("图3：原始商品素材，用于校验颜色、结构、比例、Logo、包装文字、印花与材质。\n\n");
        prompt.append("如请求中另附参考视频：它是原分镜脚本的动作母版，只用于复刻时间节奏、主体动作、运镜轨迹和镜头边界转场；不得继承其中的原商品外观、文字、品牌、人物身份或背景细节。\n\n");
        prompt.append("【生成任务】\n");
        prompt.append("生成一条").append(task.getAspectRatio()).append(" 商品展示视频。全程只保留同一件商品主体，按 01→09 自然连续变化，不得复制或增加第二件同款。\n");
        prompt.append("商品的颜色、形状、比例、Logo、标签、包装文字、图案和材质必须与图3一致，不得重新设计、漂移、融化或变形。\n");
        prompt.append("场景、展示方式、机位、构图和手部状态逐镜读取图2与脚本时间轴，不得用某一格的设置覆盖其他格。镜头变化只采用脚本明确的推近、拉远、平移、环绕或细节特写。\n");
        prompt.append("运镜不是静态构图备注：每个时间段必须真实执行脚本 cameraMovement 指定的方向、速度、起止景别和持续过程；只有脚本明确写固定机位时才允许完全固定。\n");
        prompt.append("九宫格是 9 个时间轴关键帧，不代表 9 个物理镜头。只有 transitionDescription 明确标记真实切点时才执行转场；标记“同镜头连续，无转场”的相邻格必须保持同一镜头内连续运动。原片拆解出的闪光、光漏、遮挡、甩镜、变焦模糊、叠化或硬切要按类型、方向、颜色和时长复现，不得在九宫格内部凭空增加切镜。\n");
        prompt.append("分镜明确写出手持、拿取、摆放、打开或演示时，只在该分镜出现完成动作所需的一双自然手；分镜未要求手部互动时，不得新增人物、手臂、手部或持物动作。\n");
        prompt.append("商品不得悬浮，必须与手、桌面、地面、支架或包装建立符合该分镜的真实接触关系。\n");
        prompt.append("禁止穿模、异常反射、破损结构、额外配件、背景文字、水印、分屏、九宫格边框和画中画。\n\n");
        appendMusicPrompt(prompt, task);
        appendImageToVideoTimeline(prompt, task);
        String value = prompt.toString().trim();
        return value.substring(0, Math.min(value.length(), 20000));
    }

    private String buildStoryboardAnimationPrompt(AiVideoTask task, String garmentName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("提示词版本：V11.1 · V11 恢复版与局部帧保持\n\n");
        prompt.append("【参考图片顺序】\n");
        prompt.append("图1：从图2第 01 格等比例裁出的单人首帧。它只锚定第 01 镜头的人物身份、脸、发型、身材、完整穿搭与初始姿势；不得用图1背景覆盖后续分镜。\n");
        prompt.append("图2：最终 4K 九宫格分镜表。按从左到右、从上到下读取 01→09；每一格都分别决定对应镜头的动作、场景、构图、景别和机位，不得只读取第 01 格。\n");
        prompt.append("图3：商品素材，只用于校验上衣颜色、版型、领口、袖长以及胸前印花的内容、位置和比例。\n\n");
        prompt.append("【生成任务】\n");
        prompt.append("生成一条严格按图2的 01→09 顺序播放的多镜头电商短片。必须逐格读取并落实每格场景、动作、构图和景别；禁止把九格全部改写成第 01 格的背景与站姿。\n");
        prompt.append("相邻格背景、人物位置和景别一致时，使用同一身体的自然连续动作；相邻格出现明显换景、站立与坐姿切换、人物镜头与商品特写切换或景别大幅变化时，在分镜边界使用干净硬切，不得用人体变形、背景融化或强行走位完成转场。\n");
        prompt.append("【视角变化判定】若时间轴明确写出转身、回身、侧身、低头、抬头或回眸，则在约 0.8–1.5 秒内只完成这一项平滑运动，头、颈、肩和躯干按真实骨骼关系共同转动；转动期间不叠加新的微笑、挑眉、口型、眨眼或快速视线动作。\n");
        prompt.append("若时间轴没有连续动作指令，而相邻格出现正面/侧面/背面互换、身体朝向变化超过约 30°、头部俯仰变化超过约 20°、人物尺度明显改变或全身与局部景别互换，则在边界直接硬切。硬切前后各稳定约 4–6 帧，禁止中间脸、中间身体、交叉溶解、残影、变形插值和背景融化。\n");
        prompt.append("每格都必须在对应时间段真实出现，不得跳过中间格或长期停留第 01 格。镜头内每次只推进一个主要动作，不自行增加舞蹈、挥手、转圈或夸张表演。\n");
        prompt.append("所有有人分镜始终使用图1中的同一个人物和同一套穿搭，任一镜头最多 1 人；纯商品特写允许人物不出镜。禁止第二人物、复制、分身、镜像人物、换脸、换发型、换装、四肢增生、关节反折和穿模。\n");
        prompt.append("【面部身份与运动分离】图1是整条视频唯一的人脸几何母版。脸型、眉形、眼裂与眼距、虹膜、鼻梁、鼻翼与鼻孔、静止唇形、嘴角、下颌线和脸颈肤色持续锁定；头部转动只产生正常透视变化，不得逐帧重新生成五官。\n");
        prompt.append("脚本没有明确面部动作时保持放松自然表情、稳定闭口和稳定视线；禁止眼睛大小或虹膜漂移、左右眼不同步、嘴角抽动、嘴唇无意义开合、下颌抖动、鼻翼或鼻孔收缩扩张、眉毛跳动和表情来回切换。全片最多一次自然眨眼，且只能发生在人物正面稳定时。\n");
        prompt.append("【局部帧保持】同一镜头中，当人物姿势或表情没有变化时，眼睛、鼻子、嘴巴直接保持上一帧的局部形状与纹理，只做必要的位置跟随，不重新采样、锐化、补全或美化。转身、低头和抬头期间以最近稳定关键帧为身份锚点，只做连续几何变换；清晰度不足时宁可保持柔和，也不得重新画一套五官。\n");
        prompt.append("允许时间轴明确要求的单次微笑、眨眼或视线转移，但每次只执行一项，从稳定表情缓慢进入，到位后保持至少 0.4 秒；不得同时触发眨眼、转头、张嘴、挑眉和视线跳转。身体动作不自动触发表情。\n");
        appendMusicPrompt(prompt, task);
        prompt.append("【商品纹理时序锁定】胸前文字、数字、图案、轮廓线、颜色块和相互间距是绑定在同一块布料坐标上的固定纹理，只能随整件衣服连续做透视、缩放和褶皱形变；不得独立移动、逐帧改字、换色、增删、闪烁、爬动或重新排列。\n");
        prompt.append("【印花帧保持】胸前印花在首个清晰正面帧完成一次校验后，后续直接复用同一纹理贴图，不执行 OCR、不重新拼写、不逐帧修复字母。每个字母的笔画、端点和开口方向必须保持不变，W 始终为 W，禁止变成 V、M、N；侧身时允许自然变窄、降低可读性或被遮挡，不得为保持可读而重画。\n");
        prompt.append("衣服褶皱遵循连续布料物理，同一条褶皱随身体动作平滑移动，不得凭空出现、消失或像液体沸腾。皮肤的肤色、毛孔、细纹和关节折痕不得游走、闪烁或逐帧强化；分辨率不足时保持自然柔和的中频细节。\n");
        prompt.append("【曝光与色彩锁定】每个镜头分别以图2对应格的亮度、肤色、白平衡、色温、黑位、对比度和饱和度为基准；同一镜头内保持稳定，不得出现曝光呼吸、肤色闪烁、自动压暗、灰雾、暗角或电影滤镜。不同场景在硬切后直接采用下一格自身的曝光，不使用淡入淡出或黑场。\n");
        prompt.append("每个镜头的背景、焦段、光线与人物尺度以图2对应格为准；同一镜头内部保持稳定。不得显示图2格线、拼贴、文字或编号，不生成字幕、额外 Logo 或水印。\n\n");
        appendImageToVideoTimeline(prompt, task);
        String value = prompt.toString().trim();
        return value.substring(0, Math.min(value.length(), 20000));
    }

    private void appendMusicPrompt(StringBuilder prompt, AiVideoTask task) {
        if ("UPLOADED".equals(task.getMusicMode())) {
            prompt.append("【配乐】使用已上传的音乐作为唯一 BGM，保留其旋律与节奏；不再生成替代音乐、人声、念白、环境噪声或随机音效。\n");
        } else {
            prompt.append("【配乐】同步生成轻快、明亮、有清晰旋律的纯器乐 BGM，节奏平稳且适合日常电商短视频；音量稳定，无杂音、无乱音、无突兀打击、无失真、无人声或念白。\n");
        }
        prompt.append("【音画解耦】BGM 只提供背景氛围，不代表人物说话，不得驱动口型、嘴唇、下颌、鼻翼、眉毛、眼球、眨眼、手势或身体节拍。\n");
    }

    @SuppressWarnings("unchecked")
    private void appendImageToVideoTimeline(StringBuilder prompt, AiVideoTask task) {
        try {
            Map<String, Object> root = objectMapper.readValue(task.getScriptJson(), Map.class);
            Object rawShots = root.get("shots");
            if (!(rawShots instanceof List<?> shots) || shots.isEmpty()) return;
            prompt.append("【逐镜头时间轴】\n");
            boolean productOnly = VideoTimelineSemantics.usesPhysicalShotTransitions(task.getGenerationMode());
            prompt.append(productOnly
                    ? "每一项对应图2中同编号关键帧，必须在指定时段落实该格的场景、构图、景别、动作和运镜；严格读取“进入转场”，其中“同镜头连续，无转场”表示平滑延续，只有明确的真实切点才允许切镜。\n"
                    : "每一项对应图2中同编号格，必须在指定时段落实该格的场景、构图、景别和动作；同场景内部平滑运动，场景明显变化时在边界干净硬切。\n");
            prompt.append("时间轴文字中的人物外貌、发型、眼镜、配饰、上装和下装描述全部失效，人物与穿搭只认图1和图3；场景、动作、构图与景别以图2对应格的可见画面为最高标准。\n");
            for (Object rawShot : shots) {
                if (!(rawShot instanceof Map<?, ?> shot)) continue;
                int sequence = numberValue(shot.get("sequence"), 1);
                String start = textValue(shot.get("startSecond"));
                String end = textValue(shot.get("endSecond"));
                String action = textValue(shot.get("motionDescription"));
                if (action.isBlank()) action = textValue(shot.get("sceneDescription"));
                if (action.isBlank()) action = textValue(shot.get("title"));
                String intensity = textValue(shot.get("motionIntensity"));
                String shotSize = textValue(shot.get("shotSize"));
                String movement = textValue(shot.get("cameraMovement"));
                String transition = textValue(shot.get("transitionDescription"));
                String scene = textValue(shot.get("sceneDescription"));
                prompt.append(String.format("%02d", sequence));
                if (!start.isBlank() && !end.isBlank()) prompt.append("｜").append(start).append('–').append(end).append(" 秒");
                prompt.append('\n');
                appendLine(prompt, "动作轨迹", action);
                appendLine(prompt, "画面与场景", scene);
                appendLine(prompt, "动作幅度", intensity);
                appendLine(prompt, "手部规则", shotHandDirective(task, action, scene));
                if (productOnly) {
                    appendLine(prompt, "进入转场", VideoTimelineSemantics.enteringTransition(sequence, transition));
                }
                if (!shotSize.isBlank() || !movement.isBlank()) {
                    prompt.append("镜头：");
                    if (!shotSize.isBlank()) prompt.append(shotSize);
                    if (!shotSize.isBlank() && !movement.isBlank()) prompt.append(" / ");
                    prompt.append(movement).append('\n');
                }
            }
        } catch (Exception ignored) { }
    }

    private String buildThreeReferenceGridPromptV3(AiVideoTask task, String garmentName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("商品：").append(garmentName).append('\n');
        if (task.getTargetMarket() != null && !task.getTargetMarket().isBlank()) {
            prompt.append("目标市场：").append(task.getTargetMarket()).append('\n');
        }
        prompt.append("画幅：9:16 竖屏短视频（调用视频模型时必须明确设置 9:16，不允许回退为 16:9）\n");
        prompt.append("提示词版本：V3 · 3 张参考图 · 4K 九宫格\n\n");
        prompt.append("【只允许使用以下 3 张参考图】\n");
        prompt.append("参考图 A（").append(garmentName).append("-AI 模特图）：唯一人物身份和完整穿搭参考。锁定脸部、发型、肤色、身材、人物比例、下装搭配及衣服的实际穿着效果；忽略图 A 的背景和动作。\n");
        prompt.append("参考图 B（").append(garmentName).append("-4K 分镜九宫格）：已用图 A 同一人物和穿搭完成替换的动作分镜，只提供动作、站位、景别、机位和环境。它是一张 3×3 索引图，必须按从左到右、从上到下识别为 01、02、03 / 04、05、06 / 07、08、09；人物身份仍以图 A 为最高标准。\n");
        prompt.append("参考图 C（").append(garmentName).append("原始衣服图）：唯一商品细节校验参考。锁定颜色、版型、领口、袖长、纹理、印花内容、印花位置和比例。\n\n");
        prompt.append("【九宫格识别协议｜最高优先级】\n");
        prompt.append("1. 图 B 只是内部动作索引，不是视频首帧或成片布局；先在内部识别 9 个格子的编号与动作，再只生成当前分段对应的单人连续画面。\n");
        prompt.append("2. 成片任何时刻都不得出现九宫格、格线、拼贴、分屏、画中画、多人、分身、镜像人物或残影。\n");
        prompt.append("3. 全程只能出现图 A 中的同一个人；图 B 人物必须被图 A 的完整人物与穿搭替换，商品细节最终以图 C 校正。\n");
        prompt.append("4. 人物身份、发型、身体比例、服装图案、场景结构和光线必须连续，不得换人、换衣、变形或跳变。\n");
        prompt.append("5. 不由视频模型生成字幕、促销文字、Logo 重绘或水印；脚本文案仅供后期剪辑使用。\n\n");
        prompt.append("【三段生成策略｜每段始终只上传 A + B + C】\n");
        prompt.append("第 1 段：只读取图 B 第 01–03 格，忽略 04–09。\n");
        prompt.append("第 2 段：只读取图 B 第 04–06 格，忽略 01–03 和 07–09。\n");
        prompt.append("第 3 段：只读取图 B 第 07–09 格，忽略 01–06。\n");
        prompt.append("三段都复用完全相同的 A、B、C 三张参考图，不上传第 4 张图。最后按 01 至 09 的时间顺序拼接。\n\n");
        appendFrozenScript(prompt, task);
        String value = prompt.toString().trim();
        return value.substring(0, Math.min(value.length(), 20000));
    }

    private String buildContinuousThreeReferenceGridPrompt(AiVideoTask task, String garmentName) {
        String previous = buildThreeReferenceGridPromptV3(task, garmentName);
        String continuity = "【参考图权限｜不得混用】\n"
                + "1. 图 A 是唯一人物身份与完整穿搭母版，拥有最高优先级：必须复制同一张脸、五官比例、肤色、发型、发长、身材比例、上衣、下装、鞋袜和配饰。\n"
                + "2. 图 B 只提供 01→09 的动作姿态、构图和背景引导。即使图 B 已做人物替换，也必须持续用图 A 校验脸、发型、身材和完整穿搭，不得把任何残留差异带入成片。\n"
                + "3. 图 C 只用于校验商品上衣细节；其颜色、领口、袖长、版型、文字、图案、纹理和位置必须准确覆盖到图 A 人物身上，不得重新设计。\n"
                + "4. 每一帧只能出现图 A 的同一个人物并穿图 A 的同一整套搭配。若 A、B、C 冲突：身份和下装以 A 为准，上衣商品细节以 C 为准，动作与环境以 B 为准。\n"
                + "5. 禁止换脸、脸型漂移、年龄变化、发型变化、身材变化、换衣、图案变形、文字乱码、颜色漂移或中途出现第二个人。\n\n"
                + "【动作连续性协议｜必须逐帧执行】\n"
                + "1. 图 B 的 01→02→03→04→05→06→07→08→09 是一条连续时间线上的 9 个关键帧，不是 9 张独立摆拍。\n"
                + "2. 必须先识别当前分段的3个起始/过渡/结束姿态，再生成完整动作轨迹；不得直接从前一格跳到后一格。\n"
                + "3. 相邻关键帧之间必须自动补齐至少 6–12 个过渡姿态；头部、肩部、手臂、手指、髋部、膝盖和脚部必须沿连续、最短且符合关节极限的路径移动。\n"
                + "4. 每次只完成一个主动作；手还没到位时不启动转身，转身还没完成时不重置回正面。\n"
                + "5. 上一个动作的结束帧必须与下一个动作的起始帧一致；禁止瞬移、跳帧、姿势重置、脚底滑动、手臂闪现、身体突然转向或镜头突然变焦。\n"
                + "6. 固定机位、焦段、场景、光线和人物所在地面位置；除脚本明确要求转身外，两脚的支撑点不得无原因变化。\n"
                + "7. 节奏按分镜时间严格分配：每个关键帧只停留约 15%–25% 时间，其余时间用于平滑过渡，不允许长时间静止后突然赶动作。\n"
                + "8. 严格遵守真实人体骨骼、关节角度、重心和惯性：肘、腕、膝、踝不得反向弯折或无因扭转，动作速度和加速度必须平滑。\n"
                + "9. 开启碰撞约束：手臂、手指、头发、双腿不得穿过躯干、衣服或彼此；身体不得穿进墙面、家具和地面；服装不得粘进皮肤或突然破裂。\n"
                + "10. 双手始终各有五根清晰手指，四肢数量固定，脸部与身体不得融化、拉伸、重影、复制或局部消失。\n"
                + "11. 生成完成前逐帧自检身份、服装、人体结构、碰撞和动作连续性；发现穿模或诡异姿态时必须回退到最近正确帧并沿更小幅度路径重算，不得强行衔接。\n\n";
        String value = previous
                .replace("提示词版本：V3 · 3 张参考图 · 4K 九宫格",
                        "提示词版本：V5 · 3 张参考图 · 双阶段身份对齐九宫格")
                .replace("【三段生成策略｜每段始终只上传 A + B + C】",
                        continuity + "【三段生成策略｜每段始终只上传 A + B + C】")
                .trim();
        return value.substring(0, Math.min(value.length(), 20000));
    }

    private String buildDetailedVideoPrompt(AiVideoTask task, String garmentName, boolean independentShots) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("商品：").append(garmentName).append('\n');
        if (task.getTargetMarket() != null && !task.getTargetMarket().isBlank()) {
            prompt.append("目标市场：").append(task.getTargetMarket()).append('\n');
        }
        prompt.append("画幅：9:16 竖屏短视频（调用视频模型时必须在参数中明确设置 9:16，不允许回退为 16:9）\n\n");
        if (independentShots) {
            prompt.append("提示词版本：V2 · 9 张独立分镜\n\n");
            prompt.append("【参考图职责｜不得混用】\n");
            prompt.append("参考图 A（").append(garmentName).append("-AI 模特图）：唯一完整人物与穿搭效果参考。使用她的脸部、发型、肤色、身材、人物比例、下装搭配，以及商品穿在身上的肩线、袖口、衣长、松紧、垂坠和整体合身效果；忽略图 A 的背景和动作。\n");
            prompt.append("参考图 B01–B09（").append(garmentName).append("-分镜-01.png 至分镜-09.png）：9 张已切好的独立 9:16 分镜，各自只提供对应编号的动作、站位、景别、机位和环境。不需要解析、裁切或推测九宫格顺序；忽略 B 系列图中的人物身份、眼镜、发型、服装和文字。\n");
            prompt.append("参考图 C（").append(garmentName).append("原始衣服图）：唯一商品细节校验参考。全程严格保持颜色、版型、领口、袖长、纹理、印花内容、印花位置和比例；图 A 决定穿着效果，图 C 校正商品细节。\n\n");
        } else {
            prompt.append("【参考图职责｜不得混用】\n");
            prompt.append("参考图 A（").append(garmentName).append("-AI 模特图）：唯一完整人物与穿搭效果参考。使用她的脸部、发型、肤色、身材、人物比例、下装搭配，以及商品穿在身上的肩线、袖口、衣长、松紧、垂坠和整体合身效果；忽略图 A 的背景和动作。最终效果相当于把图 A 的完整人物与穿搭自然放进图 B 的动作和环境中。\n");
            prompt.append("参考图 B（").append(garmentName).append("-分镜九宫格）：仅作为 01 至 09 的动作、站位、镜头和环境引导。按从左到右、从上到下读取；忽略图 B 中所有人物身份、眼镜、服装、文字、边框和拼贴布局。严禁把整张九宫格直接变成视频画面。\n");
            prompt.append("参考图 C（").append(garmentName).append("原始衣服图）：唯一商品细节校验参考。全程严格保持衣服的颜色、版型、领口、袖长、纹理、印花内容、印花位置和比例；图 A 决定实际穿着效果，图 C 负责纠正商品细节，二者共同优先于图 B 中的服装。\n\n");
        }
        prompt.append("【最高优先级硬约束】\n");
        prompt.append("1. 全程始终只能出现图 A 中的同一个人物，任何时刻画面内都只能有 1 人。\n");
        prompt.append("2. 禁止第二人物、人物复制、分身、镜像人物、残影人物、多人同框、分屏、拼贴、九宫格边框和画中画。\n");
        if (independentShots) {
            prompt.append("3. B 系列分镜只提供动作与环境，不得继承其人物的脸、眼镜、发型、身体特征、服装或穿搭；始终以图 A 的完整人物与穿搭重建对应动作。\n");
        } else {
            prompt.append("3. 图 B 只提供动作与环境，不得继承图 B 人物的脸、眼镜、发型、身体特征、服装或穿搭。每格都要先移除图 B 原人物，再以图 A 的完整人物与穿搭按该格姿势重新构建。\n");
        }
        prompt.append("4. 每个分镜必须保持人物身份、发型、服装图案、身体比例、场景结构和光线连续，不得换人、换衣或跳变。\n");
        prompt.append("5. 不由视频模型生成字幕、促销文字、Logo 重绘或水印；脚本中的屏幕文案仅作为后期剪辑文字。\n");
        prompt.append("6. 如果分镜文案语言与目标市场冲突，以目标市场为准，并交由后期添加。\n\n");
        prompt.append("【生成策略】\n");
        if (independentShots) {
            prompt.append("第 1 段只上传 A + B01 + B02 + B03 + C；第 2 段只上传 A + B04 + B05 + B06 + C；第 3 段只上传 A + B07 + B08 + B09 + C。每段正好 5 张参考图，不上传原始九宫格总图。\n");
            prompt.append("后一段使用前一段尾帧作为续写首帧，通过视频模型的首帧/续写参数传入，不占用 5 张素材参考图席位；最后按 01 至 09 的时间顺序无缝拼接。\n\n");
        } else {
            prompt.append("先把图 B 内部解析为 9 张独立 9:16 分镜关键帧；每张都以图 A 的完整人物与穿搭替换图 B 原人物，再用图 C 校验商品细节。不得直接将整张九宫格作为视频首帧或参考画面。\n");
            prompt.append("按 01-03、04-06、07-09 分成三段生成；后一段使用前一段尾帧作为身份与画面连续性参考，最后按时间顺序无缝拼接。\n\n");
        }
        appendFrozenScript(prompt, task);
        String value = prompt.toString().trim();
        return value.substring(0, Math.min(value.length(), 20000));
    }

    private boolean isLegacyGeneratedPrompt(AiVideoTask task, AiVideoTaskItem item) {
        String current = item.getVideoPromptText();
        return current != null && (current.trim().equals(legacyDefaultVideoPrompt(task, item.getName()))
                || current.trim().equals(buildDetailedVideoPrompt(task, item.getName(), false))
                || current.trim().equals(buildDetailedVideoPrompt(task, item.getName(), true))
                || current.trim().equals(buildThreeReferenceGridPromptV3(task, item.getName()))
                || current.trim().equals(buildContinuousThreeReferenceGridPrompt(task, item.getName()))
                || current.contains("提示词版本：V4 · 3 张参考图 · 连续动作九宫格")
                || current.contains("提示词版本：V5.1 · 已完成分镜动画化")
                || current.contains("提示词版本：V5.2 · 已完成分镜动画化")
                || current.contains("以图1为画面和动作依据，让同一个人物按照 01→09")
                || current.contains("参考视频1：原始参考视频")
                || current.contains("图2是已经制作完成的最终分镜。直接让图2中的同一个人物")
                || current.contains("图1：人物形象参考。")
                || current.contains("图1：单人首帧。它是视频第 1 帧")
                || current.contains("生成固定机位、不中断、不切镜的单人连续镜头")
                || current.contains("提示词版本：V7 · 九宫格逐镜头场景调度")
                || current.contains("提示词版本：V8 · 九宫格逐镜头场景调度与面部稳定")
                || current.contains("提示词版本：V9 · 九宫格逐镜头场景调度与时序纹理稳定")
                || current.contains("提示词版本：V10 · 九宫格逐镜头调度与曝光时序稳定")
                || current.contains("提示词版本：V11 · 九宫格视角断点与曝光时序稳定")
                || current.contains("提示词版本：V12 · 五官拓扑与商品印花静态锚定")
                || current.contains("提示词版本：V12.1 · 核心约束精简版")
                || (current.contains("必须逐格读取并落实每格场景")
                    && !current.contains("提示词版本：V7")
                    && !current.contains("提示词版本：V8")
                    && !current.contains("提示词版本：V9")
                    && !current.contains("提示词版本：V10")
                    && !current.contains("提示词版本：V11")
                    && !current.contains("提示词版本：V12.1"))
                || current.contains("图1：最终 4K 九宫格动作轨迹图。")
                || (current.contains("图1：最终九宫格分镜") && !current.contains("【分镜时间轴】"))
                || current.contains("提示词版本：V6 · 单格参考 · 三段生成 · 禁止九宫格输入"));
    }

    private String legacyDefaultVideoPrompt(AiVideoTask task, String garmentName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("商品：").append(garmentName).append('\n');
        if (task.getTargetMarket() != null && !task.getTargetMarket().isBlank()) {
            prompt.append("目标市场：").append(task.getTargetMarket()).append('\n');
        }
        prompt.append("画幅：9:16 竖屏短视频\n");
        appendFrozenScript(prompt, task);
        String value = prompt.toString().trim();
        return value.substring(0, Math.min(value.length(), 20000));
    }

    @SuppressWarnings("unchecked")
    private void appendFrozenScript(StringBuilder prompt, AiVideoTask task) {
        try {
            Map<String, Object> root = objectMapper.readValue(task.getScriptJson(), Map.class);
            appendLine(prompt, "整体风格", root.get("globalVisualTone"));
            Object rawShots = root.get("shots");
            if (rawShots instanceof List<?> shots) {
                for (Object rawShot : shots) {
                    if (!(rawShot instanceof Map<?, ?> shot)) continue;
                    int sequence = numberValue(shot.get("sequence"), 1);
                    String title = textValue(shot.get("title"));
                    String start = textValue(shot.get("startSecond"));
                    String end = textValue(shot.get("endSecond"));
                    prompt.append("\n分镜 ").append(String.format("%02d", sequence));
                    if (!title.isBlank()) prompt.append("｜").append(title);
                    if (!start.isBlank() && !end.isBlank()) prompt.append("｜").append(start).append('-').append(end).append(" 秒");
                    prompt.append('\n');
                    appendLine(prompt, "画面", shot.get("sceneDescription"));
                    String shotSize = textValue(shot.get("shotSize"));
                    String movement = textValue(shot.get("cameraMovement"));
                    if (!shotSize.isBlank() || !movement.isBlank()) {
                        prompt.append("镜头：").append(shotSize);
                        if (!shotSize.isBlank() && !movement.isBlank()) prompt.append(" / ");
                        prompt.append(movement).append('\n');
                    }
                    appendLine(prompt, "台词", shot.get("narration"));
                }
            }
        } catch (Exception ignored) {
            prompt.append("\n").append(task.getScriptJson());
        }
    }

    private void appendLine(StringBuilder target, String label, Object value) {
        String text = textValue(value);
        if (!text.isBlank()) target.append(label).append('：').append(text).append('\n');
    }

    private String shotHandDirective(AiVideoTask task, String action, String scene) {
        String context = (textValue(action) + " " + textValue(scene)).toLowerCase();
        boolean explicitlyNoHand = containsAny(context, "无人手", "无手部", "不出现手", "没有手", "纯静物", "平铺", "仅商品", "no hand", "hands-free");
        boolean requiresHand = !explicitlyNoHand && containsAny(context,
                "手持", "拿取", "拿起", "拿着", "握住", "抓住", "托住", "捏住", "摆放", "放下", "拆封", "开箱", "双手", "单手", "手掌", "手指",
                "hold", "holding", "pick up", "grip", "handheld", "unbox");
        if ("MODEL_WEAR".equals(task.getGenerationMode())) {
            return requiresHand
                    ? "本镜头按脚本执行指定手势或触碰；只做该动作，不新增拿取、指向、挥手或其他手势"
                    : "人物双手自然沿用该分镜姿势；不新增手持、拿取、指向、拉扯、点赞或挥手动作";
        }
        return requiresHand
                ? "本镜头按脚本出现完成动作所需的一双自然手；手的数量、方向、接触位置与动作必须一致，不能延续到其他镜头"
                : "本镜头不出现人物、手臂、手掌或手指，只保留商品及脚本要求的承托关系";
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private String textValue(Object value) {
        return value == null || "null".equals(String.valueOf(value)) ? "" : String.valueOf(value).trim();
    }

    private int numberValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(textValue(value)); } catch (Exception ignored) { return fallback; }
    }

    public record CreateCommand(String scriptId, String name, String targetMarket, String generationMode,
                                String backgroundMode, String productCategory, String sceneType, String interactionType,
                                boolean allowHand, String aspectRatio, String resolution, boolean useScriptReferenceVideo) {}
    public record TaskDetail(AiVideoTask task, List<AiVideoTaskItem> items, List<AiVideoTaskAsset> assets,
                             java.time.Instant videoSubmittedAt, Map<String, String> videoProviderTaskIds) {}
    public record AssetFile(Path path, String contentType, long size) {}
    public record SourceFile(Path path, String contentType, long size) {}
}
