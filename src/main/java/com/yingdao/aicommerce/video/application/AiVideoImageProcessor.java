package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.domain.VideoScriptShot;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Service
public class AiVideoImageProcessor {
    private static final String PORTRAIT_4K_SIZE = "2160x3840";
    private static final String PORTRAIT_4K_REQUIREMENT = "最终必须输出实际像素为 2160×3840 的 9:16 竖屏 4K 图片，"
            + "不是仅在画面风格上表现为高清；主体边缘、服装纹理、印花和背景细节必须清晰可辨。";
    private static final String REAL_PHOTOGRAPHY_REQUIREMENT = "画面像普通人用手机主摄在真实空间中随手拍摄，不是 CG、插画、"
            + "影楼样片或素材库合成图。使用手机主摄的正常视角，拍摄距离比传统全身照缩短约 50%，相机自然向人物推进但不使用数码裁切或超广角，胸腹高度持机；关闭人像模式、"
            + "美颜、瘦脸和强 HDR，不制造抠图式背景虚化。允许轻微手持取景偏差、自然透视、不过度校正的垂直线和真实中间调，"
            + "但画面不能倾斜、模糊或低清。光线、曝光、白平衡、阴影方向与明暗关系直接沿用脚本第 01 分镜或参考背景的原始状态，"
            + "不额外设计或添加窗光、光束、发丝光、轮廓光、补光、影棚光、逆光、电影光效或新的高光与阴影。"
            + "若参考背景没有明确方向光，就保持普通室内现场光与手机自动曝光，不主动改变场景亮度。禁止 HDR 光晕、假景深和滤镜。";
    private static final String PORTRAIT_SKIN_QUALITY_REQUIREMENT = "皮肤像未经重度美颜的真实手机照片：整体白净健康但保留轻微肤色起伏，"
            + "允许少量浅痘印、淡雀斑、眼下细纹和不均匀但健康的局部血色；脸颊、鼻翼、眼下、唇周和关节处有克制的自然色差。"
            + "T 区保持自然哑光且不发油光，保留细小毛孔、真实唇纹，以及额头、面颊和唇周只有近看才可见的细小人类绒毛。"
            + "光线在脸、耳、颈和手臂之间连续过渡，不做瓷面磨皮、塑料高光、单独脸部美白或过度锐化。";
    private static final String NATURAL_EYE_REQUIREMENT = "眼神像真实人物刚注意到拍摄者：双眼共同对焦在镜头附近的同一目标，"
            + "眼睛保持普通东亚成年人的自然眼裂宽高、虹膜大小和眼白比例，不放大双眼、虹膜、瞳孔或卧蚕，"
            + "不做幼态圆眼、漫画大眼或刻意睁眼。眼睑开合自然，左右眼保留人类轻微差异；"
            + "瞳孔和柔和眼神光只响应现场主光，不做玻璃珠眼或僵直凝视。";
    private static final String NATURAL_HAIR_REQUIREMENT = "头发保持日常梳理后的整洁状态：发型轮廓、发缝、刘海和扎发结构清楚，"
            + "发束顺着重力和动作自然排列，不出现炸毛、鸟窝状乱发、粘成塑料块或遮住眼睛和嘴唇。"
            + "只在发际线、耳侧和逆光边缘保留极少量细软碎发来体现真实感；碎发不能密集飞散，也不能逐格改变发型。";
    private static final String FRIENDLY_MODEL_FACE_BASELINE = "【真人选角基调】年轻成年东亚女性，清秀耐看、五官协调、神态亲和，"
            + "但必须像现实生活中的具体个人而不是统一网红模板。眉眼、鼻头、唇形、脸颊与下颌保留克制的个人特征和轻微左右差异；"
            + "妆容为轻薄伪素颜：薄而透的底妆不遮掉毛孔与细小瑕疵，眉毛只轻微整理，眼妆极淡，使用接近原生的自然唇色。"
            + "不使用浓眼线、夸张睫毛、假睫毛、明显卧蚕、高饱和眼影、强腮红、高光修容或玻璃唇；"
            + "眼睛大小与脸型比例自然，不追求网红大眼。嘴唇放松，眼神有明确落点，像朋友用手机临时拍下的自然一瞬。";
    private static final String ANTI_MANNEQUIN_REQUIREMENT = "人物处在真实呼吸与承重状态：一侧肩髋略低，重心落在一条腿，双手姿态不完全相同，"
            + "颈肩、锁骨和衣料褶皱呈现同一受力关系。人物边缘、发丝、皮肤和背景都服从同一现场光线；"
            + "保留手机照片自然的局部锐度差和暗部细微传感器纹理，4K 只增加真实信息，不把人脸处理成均匀、对称、无表情的假人。";
    private static final String FACE_COLOR_CONTINUITY_REQUIREMENT = "【肤色连续】脸、耳、颈、锁骨、手臂和双腿属于同一暖中性自然肤色，"
            + "明暗与冷暖只随现场光线渐变；脸颊可有极淡且不对称的血色，鼻翼和眼下保留细微真实色差。"
            + "不得出现灰脸、黄绿脸、单独脸部滤镜、粉底面具边界或脸颈色温断层。";
    private static final String DIGNIFIED_STYLING_REQUIREMENT = "本规则只约束鞋履，不约束也不得改写上衣、下装、配色、配饰或整体穿搭。"
            + "只有画面本来能看见脚部时，才必须穿完整且合脚的普通日常鞋，优先简洁干净的休闲鞋或低帮鞋；不得为了展示鞋子把人物缩小或把相机拉远。"
            + "禁止赤脚、只穿袜子、拖鞋、酒店拖鞋、鞋子残缺或鞋子凭空消失。九宫格中鞋款与颜色保持一致。";
    private static final String CLOSE_PORTRAIT_COMPOSITION_REQUIREMENT = "【近距离清晰构图｜最高优先级】相机相对原全身构图向前推进约二分之一距离，"
            + "这不是完整全身照。按示例红框比例构图：画面上沿在头顶上方保留 5%–8% 空间，画面下沿必须明确落在大腿中下段至膝盖上方，膝盖、小腿、双脚和鞋子必须位于画面之外且不可见。"
            + "头顶、完整五官、胸前商品、双肩、双臂和执行动作的双手必须清楚入镜；不能为了保留双脚或展示整套穿搭而缩小人物。"
            + "人脸从发际线到下巴的有效高度不得低于整张 2160×3840 图片高度的 15%，约 575 像素，"
            + "双眼虹膜边缘、上下眼睑、鼻翼、唇线、毛孔和细小绒毛必须具有真实可辨的像素信息。必须通过真实相机靠近和重新构图获得清晰度，禁止先生成远景再数码放大、裁切或锐化。";
    private static final String GRID_FACE_PIXEL_REQUIREMENT = "【九宫格人脸像素硬约束】每个 720×1280 子画面默认采用头顶至大腿中下段、接近膝盖上方的中近景，"
            + "默认格子的下边界必须落在大腿中下段至膝盖上方，膝盖、小腿、双脚和鞋子不可见；每张脸从发际线到下巴不得低于单格高度的 15%，约 190 像素。头顶、五官、胸前商品、双臂和执行动作的双手必须完整清楚，"
            + "不得为了保留全身和鞋底把人物缩小。脚本明确要求纯商品特写、坐姿或动作必须看到脚部时，才允许该格采用对应景别并显示鞋子，"
            + "但同一连续段的人脸尺度不得忽大忽小。";
    private static final String FABRIC_AND_PRODUCT_REALISM_REQUIREMENT = "材质必须按真实物理方式受光：棉料保留细微织纹、厚薄、"
            + "缝线和受力褶皱，印花贴合布面起伏而非悬浮；纸、塑料、玻璃、金属和木材分别呈现正确的漫反射、粗糙度、"
            + "透光或反射特征。避免所有表面同样光滑、边缘融化、无来源高光和不符合重力的悬浮。";
    private final AiVideoTaskMapper taskMapper;
    private final AiVideoTaskItemMapper itemMapper;
    private final AiVideoTaskAssetMapper assetMapper;
    private final VideoScriptMapper scriptMapper;
    private final VideoScriptShotMapper shotMapper;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;
    private final RestClient piClient;
    private final Executor imageExecutor;
    private final Path storageRoot;
    private final Path videoScriptStorageRoot;

    public AiVideoImageProcessor(AiVideoTaskMapper taskMapper, AiVideoTaskItemMapper itemMapper,
                                 AiVideoTaskAssetMapper assetMapper, VideoScriptMapper scriptMapper,
                                 VideoScriptShotMapper shotMapper, ModelConfigService modelConfigService,
                                 ObjectMapper objectMapper,
                                 @Qualifier("aiVideoImageExecutor") Executor imageExecutor,
                                 @Value("${app.pi-runtime-url}") String piRuntimeUrl,
                                 @Value("${app.pi-runtime-token}") String piRuntimeToken,
                                 @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storageRoot,
                                 @Value("${app.video-script-storage:/data/video-scripts}") String videoScriptStorageRoot) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.scriptMapper = scriptMapper;
        this.shotMapper = shotMapper;
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
        this.imageExecutor = imageExecutor;
        this.piClient = RestClient.builder().baseUrl(piRuntimeUrl).defaultHeader("X-Internal-Token", piRuntimeToken).build();
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.videoScriptStorageRoot = Path.of(videoScriptStorageRoot).toAbsolutePath().normalize();
    }

    public void prepare(AiVideoTask task) {
        ModelConfigService.RuntimeModelConnection model = modelConfigService.imageConnection(task.getOrganizationId())
                .orElseThrow(() -> new IllegalStateException("请先在模型接入中配置并启用图片模型"));
        task.start(model.id(), model.modelId());
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<AiVideoTask>()
                .eq(AiVideoTask::getId, task.getId())
                .in(AiVideoTask::getStatus, List.of("READY", "FAILED", "IMAGE_READY"))
                .set(AiVideoTask::getStatus, task.getStatus())
                .set(AiVideoTask::getProgress, task.getProgress())
                .set(AiVideoTask::getCompletedItems, task.getCompletedItems())
                .set(AiVideoTask::getModelConfigId, task.getModelConfigId())
                .set(AiVideoTask::getModelId, task.getModelId())
                .set(AiVideoTask::getErrorMessage, null)
                .set(AiVideoTask::getCompletedAt, null)
                .set(AiVideoTask::getUpdatedAt, task.getUpdatedAt()));
        if (updated != 1) {
            throw new IllegalStateException("图片生成请求已经提交，请勿重复操作");
        }
    }

    @Async("automationDiagnosisExecutor")
    public void generate(String taskId) {
        AiVideoTask task = taskMapper.selectById(taskId);
        if (task == null || !"IMAGE_GENERATING".equals(task.getStatus())) return;
        List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getTaskId, taskId).orderByAsc(AiVideoTaskItem::getCreatedAt));
        try {
            ModelConfigService.RuntimeModelConnection model = modelConfigService.imageConnection(task.getOrganizationId())
                    .orElseThrow(() -> new IllegalStateException("图片模型连接已失效"));
            List<ShotPrompt> shots = readShots(task.getScriptJson());
            if (shots.size() != 9) throw new IllegalStateException("冻结脚本不是完整 9 个分镜");
            int completed = 0;
            List<String> failures = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                AiVideoTaskItem item = items.get(index);
                try {
                    generateItem(task, item, shots, model);
                    completed++;
                } catch (Exception exception) {
                    String message = readable(exception);
                    item.fail(message);
                    itemMapper.updateById(item);
                    failures.add(item.getName() + "：" + message);
                }
                task.reportProgress(Math.min(99, Math.max(2, ((index + 1) * 100) / Math.max(1, items.size()))), completed);
                taskMapper.updateById(task);
            }
            if (completed == 0) {
                task.fail(failures.isEmpty() ? "图片模型没有生成有效结果" : String.join("；", failures));
            } else {
                task.complete(completed, failures.isEmpty() ? null : String.join("；", failures));
            }
            taskMapper.updateById(task);
        } catch (Exception exception) {
            task.fail(readable(exception));
            taskMapper.updateById(task);
        }
    }

    private void generateItem(AiVideoTask task, AiVideoTaskItem item, List<ShotPrompt> shots,
                              ModelConfigService.RuntimeModelConnection model) throws Exception {
        List<AiVideoTaskAsset> existing = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, item.getId()));
        boolean modelWear = "MODEL_WEAR".equals(task.getGenerationMode());
        boolean preserveScriptBackground = preserveScriptBackground(task);
        String primaryAssetType = modelWear ? "MODEL_IMAGE" : "PRODUCT_SCENE_IMAGE";
        AiVideoTaskAsset modelAsset = usableAsset(existing, primaryAssetType);
        AiVideoTaskAsset storyboardAsset = usableAsset(existing, "STORYBOARD_GRID");
        AiVideoTaskAsset firstFrameAsset = usableAsset(existing, "STORYBOARD_FIRST_FRAME");
        AiVideoTaskAsset storyboardGuideAsset = usableAsset(existing, "STORYBOARD_GUIDE");
        boolean hasModel = modelAsset != null;
        boolean hasStoryboard = storyboardAsset != null;
        item.start();
        if (hasModel && !hasStoryboard) item.modelReady();
        itemMapper.updateById(item);
        if (hasModel && hasStoryboard) {
            if (firstFrameAsset == null) {
                if (modelWear) ensureModelFirstFrame(task, modelAsset);
                else ensureProductFirstFrame(task, modelAsset);
            }
            item.complete();
            itemMapper.updateById(item);
            return;
        }
        ReferenceImage garment = reference(Path.of(item.getSourceFilePath()), "参考图1 商品素材", item.getSourceContentType());
        ReferenceImage scriptFirstFrame = preserveScriptBackground ? firstScriptFrameReference(task) : null;
        String modelPrompt = modelWear
                ? modelWearPrompt(task, item, shots.getFirst(), scriptFirstFrame != null)
                : productScenePrompt(task, shots.getFirst(), scriptFirstFrame != null);
        if (!hasModel) {
            List<ReferenceImage> modelReferences = scriptFirstFrame == null
                    ? List.of(garment)
                    : List.of(garment, scriptFirstFrame);
            modelAsset = CompletableFuture.supplyAsync(
                    () -> generateAsset(task, item, model, primaryAssetType, modelPrompt, modelReferences), imageExecutor).join();
            item.modelReady();
            itemMapper.updateById(item);
        }
        if (!hasStoryboard) {
            ReferenceImage generatedModel = reference(Path.of(modelAsset.getFilePath()),
                    modelWear ? "AI模特图" : "AI商品场景图", modelAsset.getContentType());
            if (modelWear) {
                boolean realFrameGuide = false;
                if (storyboardGuideAsset == null && preserveScriptBackground) {
                    storyboardGuideAsset = createRealFrameStoryboardGuide(task, item);
                    realFrameGuide = storyboardGuideAsset != null;
                }
                if (storyboardGuideAsset == null) {
                    String guidePrompt = storyboardGridPrompt(task, shots);
                    storyboardGuideAsset = CompletableFuture.supplyAsync(
                            () -> generateAsset(task, item, model, "STORYBOARD_GUIDE", guidePrompt,
                                    List.of(generatedModel)), imageExecutor).join();
                } else {
                    realFrameGuide = storyboardGuideAsset.getPromptText() != null
                            && storyboardGuideAsset.getPromptText().startsWith("REAL_VIDEO_FRAMES_V1");
                }
                ReferenceImage storyboardGuide = reference(Path.of(storyboardGuideAsset.getFilePath()),
                        "动作场景九宫格底图", storyboardGuideAsset.getContentType());
                String identityPrompt = storyboardIdentityTransferPrompt(task, shots, realFrameGuide);
                storyboardAsset = CompletableFuture.supplyAsync(
                        () -> generateAsset(task, item, model, "STORYBOARD_GRID", identityPrompt,
                                List.of(storyboardGuide, generatedModel)), imageExecutor).join();
            } else {
                boolean realFrameGuide = false;
                if (storyboardGuideAsset == null && preserveScriptBackground) {
                    storyboardGuideAsset = createRealFrameStoryboardGuide(task, item);
                    realFrameGuide = storyboardGuideAsset != null;
                } else if (storyboardGuideAsset != null) {
                    realFrameGuide = storyboardGuideAsset.getPromptText() != null
                            && storyboardGuideAsset.getPromptText().startsWith("REAL_VIDEO_FRAMES_V1");
                }
                String productStoryboardPrompt = productStoryboardGridPrompt(task, shots, realFrameGuide);
                List<ReferenceImage> productReferences;
                if (realFrameGuide) {
                    ReferenceImage storyboardGuide = reference(Path.of(storyboardGuideAsset.getFilePath()),
                            "参考图1 原视频真实九帧动作与构图母版", storyboardGuideAsset.getContentType());
                    ReferenceImage productMaster = reference(Path.of(item.getSourceFilePath()),
                            "参考图2 用户上传商品外观唯一母版", item.getSourceContentType());
                    productReferences = List.of(storyboardGuide, productMaster);
                } else {
                    productReferences = List.of(generatedModel, garment);
                }
                storyboardAsset = CompletableFuture.supplyAsync(
                        () -> generateAsset(task, item, model, "STORYBOARD_GRID", productStoryboardPrompt,
                                productReferences), imageExecutor).join();
            }
            if (modelWear) ensureModelFirstFrame(task, modelAsset);
            else ensureProductFirstFrame(task, modelAsset);
        }
        item.complete();
        itemMapper.updateById(item);
    }

    private boolean hasUsableAsset(List<AiVideoTaskAsset> assets, String type) {
        return usableAsset(assets, type) != null;
    }

    private AiVideoTaskAsset usableAsset(List<AiVideoTaskAsset> assets, String type) {
        for (AiVideoTaskAsset asset : assets) {
            if (!type.equals(asset.getAssetType())) continue;
            try {
                Path path = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
                if (path.startsWith(storageRoot) && Files.isRegularFile(path) && Files.size(path) > 0) return asset;
            } catch (Exception ignored) {
                // A database row without a readable file is not a completed output.
            }
            assetMapper.deleteById(asset.getId());
        }
        return null;
    }

    private AiVideoTaskAsset generateAsset(AiVideoTask task, AiVideoTaskItem item,
                                           ModelConfigService.RuntimeModelConnection model,
                                           String assetType, String prompt, List<ReferenceImage> references) {
        try {
            PiArtifact artifact = call(model, prompt, references);
            return store(task, item, assetType, null, artifact, prompt);
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    private ReferenceImage firstScriptFrameReference(AiVideoTask task) {
        VideoScript script = scriptMapper.selectById(task.getScriptId());
        if (script == null || !task.getOrganizationId().equals(script.getOrganizationId())
                || !"VIDEO_ANALYSIS".equals(script.getSourceType())
                || script.getSourceFilePath() == null || script.getSourceFilePath().isBlank()) return null;
        try {
            Path source = Path.of(script.getSourceFilePath()).toAbsolutePath().normalize();
            Path frameDirectory = source.getParent() == null ? null : source.getParent().resolve("frames").normalize();
            if (frameDirectory == null || !frameDirectory.startsWith(videoScriptStorageRoot)
                    || !Files.isDirectory(frameDirectory)) return null;
            List<Path> frames;
            try (var stream = Files.list(frameDirectory)) {
                frames = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jpg"))
                        .sorted().toList();
            }
            if (frames.isEmpty()) return null;
            List<VideoScriptShot> scriptShots = shotMapper.selectList(new LambdaQueryWrapper<VideoScriptShot>()
                    .eq(VideoScriptShot::getOrganizationId, task.getOrganizationId())
                    .eq(VideoScriptShot::getScriptId, task.getScriptId())
                    .orderByAsc(VideoScriptShot::getSequenceNo));
            if (scriptShots.isEmpty()) return null;
            VideoScriptShot firstShot = scriptShots.getFirst();
            double timelineEnd = scriptShots.stream().map(VideoScriptShot::getEndSecond)
                    .filter(value -> value != null).mapToDouble(value -> value.doubleValue()).max().orElse(0.0);
            double start = firstShot.getStartSecond() == null ? 0.0 : firstShot.getStartSecond().doubleValue();
            double end = firstShot.getEndSecond() == null ? start : firstShot.getEndSecond().doubleValue();
            double midpoint = Math.max(0.0, (start + Math.max(start, end)) / 2.0);
            int frameIndex = timelineEnd > 0.0
                    ? (int) Math.round((midpoint / timelineEnd) * (frames.size() - 1))
                    : 0;
            Path selected = frames.get(Math.max(0, Math.min(frames.size() - 1, frameIndex)));
            return reference(selected, "参考图2 脚本第01分镜真实画面", "image/jpeg");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Video-analysis scripts already own real sampled frames. Reusing those frames as the scene guide
     * prevents the image model from inventing a polished showroom for every storyboard cell.
     */
    private AiVideoTaskAsset createRealFrameStoryboardGuide(AiVideoTask task, AiVideoTaskItem item) {
        VideoScript script = scriptMapper.selectById(task.getScriptId());
        if (script == null || !task.getOrganizationId().equals(script.getOrganizationId())
                || !"VIDEO_ANALYSIS".equals(script.getSourceType())
                || script.getSourceFilePath() == null || script.getSourceFilePath().isBlank()) return null;
        try {
            Path source = Path.of(script.getSourceFilePath()).toAbsolutePath().normalize();
            Path frameDirectory = source.getParent() == null ? null : source.getParent().resolve("frames").normalize();
            if (frameDirectory == null || !frameDirectory.startsWith(videoScriptStorageRoot)
                    || !Files.isDirectory(frameDirectory)) return null;
            List<Path> frames;
            try (var stream = Files.list(frameDirectory)) {
                frames = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jpg"))
                        .sorted().toList();
            }
            if (frames.isEmpty()) return null;
            List<VideoScriptShot> scriptShots = shotMapper.selectList(new LambdaQueryWrapper<VideoScriptShot>()
                    .eq(VideoScriptShot::getOrganizationId, task.getOrganizationId())
                    .eq(VideoScriptShot::getScriptId, task.getScriptId())
                    .orderByAsc(VideoScriptShot::getSequenceNo));
            if (scriptShots.size() != 9) return null;
            double timelineEnd = scriptShots.stream().map(VideoScriptShot::getEndSecond)
                    .filter(value -> value != null).mapToDouble(value -> value.doubleValue()).max().orElse(0.0);
            List<Path> selected = new ArrayList<>(9);
            for (int index = 0; index < scriptShots.size(); index++) {
                VideoScriptShot shot = scriptShots.get(index);
                double start = shot.getStartSecond() == null ? -1.0 : shot.getStartSecond().doubleValue();
                double end = shot.getEndSecond() == null ? -1.0 : shot.getEndSecond().doubleValue();
                double midpoint = start >= 0.0 && end >= start ? (start + end) / 2.0 : -1.0;
                int frameIndex = timelineEnd > 0.0 && midpoint >= 0.0
                        ? (int) Math.round((midpoint / timelineEnd) * (frames.size() - 1))
                        : (int) Math.round(index * (frames.size() - 1.0) / 8.0);
                selected.add(frames.get(Math.max(0, Math.min(frames.size() - 1, frameIndex))));
            }
            BufferedImage guide = composePortraitStoryboard(selected);
            Path directory = storageRoot.resolve(task.getOrganizationId()).resolve(task.getId())
                    .resolve(item.getId()).resolve("outputs").normalize();
            if (!directory.startsWith(storageRoot)) throw new IllegalStateException("分镜底图输出目录不安全");
            Files.createDirectories(directory);
            Path output = directory.resolve("storyboard-guide.png").normalize();
            if (!ImageIO.write(guide, "png", output.toFile())) throw new IllegalStateException("真实分镜底图编码失败");
            String marker = "REAL_VIDEO_FRAMES_V1｜原视频九个分镜时间中点抽帧，背景与构图应像素级保留";
            AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(), item.getId(),
                    "STORYBOARD_GUIDE", null, output.toString(), "image/png", Files.size(output), marker, "ffmpeg");
            assetMapper.insert(asset);
            return asset;
        } catch (Exception ignored) {
            // Old/manual scripts or missing source frames safely fall back to the generated guide path.
            return null;
        }
    }

    private BufferedImage composePortraitStoryboard(List<Path> frames) throws IOException {
        int cellWidth = 720;
        int cellHeight = 1280;
        BufferedImage output = new BufferedImage(cellWidth * 3, cellHeight * 3, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        for (int index = 0; index < 9; index++) {
            BufferedImage source = ImageIO.read(frames.get(index).toFile());
            if (source == null) throw new IOException("无法读取真实分镜帧");
            double targetRatio = cellWidth / (double) cellHeight;
            int cropWidth = source.getWidth();
            int cropHeight = source.getHeight();
            if (source.getWidth() / (double) source.getHeight() > targetRatio) {
                cropWidth = Math.max(1, (int) Math.round(source.getHeight() * targetRatio));
            } else {
                cropHeight = Math.max(1, (int) Math.round(source.getWidth() / targetRatio));
            }
            int sourceX = Math.max(0, (source.getWidth() - cropWidth) / 2);
            int sourceY = Math.max(0, (source.getHeight() - cropHeight) / 2);
            int targetX = (index % 3) * cellWidth;
            int targetY = (index / 3) * cellHeight;
            graphics.drawImage(source, targetX, targetY, targetX + cellWidth, targetY + cellHeight,
                    sourceX, sourceY, sourceX + cropWidth, sourceY + cropHeight, null);
        }
        graphics.dispose();
        return output;
    }

    private AiVideoTaskAsset ensureModelFirstFrame(AiVideoTask task, AiVideoTaskAsset modelAsset) {
        if (modelAsset == null || !"MODEL_IMAGE".equals(modelAsset.getAssetType())) return null;
        AiVideoTaskAsset existing = assetMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, modelAsset.getItemId())
                .eq(AiVideoTaskAsset::getAssetType, "STORYBOARD_FIRST_FRAME")
                .last("LIMIT 1"));
        if (existing != null) {
            try {
                Path path = Path.of(existing.getFilePath()).toAbsolutePath().normalize();
                if (path.startsWith(storageRoot) && Files.isRegularFile(path) && Files.size(path) > 0) return existing;
            } catch (Exception ignored) { }
            assetMapper.deleteById(existing.getId());
        }
        try {
            Path source = Path.of(modelAsset.getFilePath()).toAbsolutePath().normalize();
            if (!source.startsWith(storageRoot) || !Files.isRegularFile(source)) {
                throw new IllegalStateException("AI 模特图不存在，无法复用为首帧");
            }
            String ext = extension(modelAsset.getContentType());
            Path output = source.getParent().resolve("storyboard-first-frame." + ext).normalize();
            if (!output.startsWith(storageRoot)) throw new IllegalStateException("首帧输出目录不安全");
            Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING);
            AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(),
                    modelAsset.getItemId(), "STORYBOARD_FIRST_FRAME", 1, output.toString(),
                    modelAsset.getContentType(), Files.size(output),
                    "复用 4K AI 模特图作为视频第 01 分镜，不增加模型调用", modelAsset.getModelId());
            try {
                assetMapper.insert(asset);
                return asset;
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                return assetMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskAsset>()
                        .eq(AiVideoTaskAsset::getItemId, modelAsset.getItemId())
                        .eq(AiVideoTaskAsset::getAssetType, "STORYBOARD_FIRST_FRAME")
                        .last("LIMIT 1"));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("复用 4K AI 模特图作为首帧失败", exception);
        }
    }

    /**
     * Product-only tasks already have a dedicated, script-aligned first-scene image. Reusing it avoids
     * inheriting an incorrect hand or composition from a model-generated storyboard's top-left cell.
     */
    public AiVideoTaskAsset ensureProductFirstFrame(AiVideoTask task, AiVideoTaskAsset productSceneAsset) {
        if (productSceneAsset == null || !"PRODUCT_SCENE_IMAGE".equals(productSceneAsset.getAssetType())) return null;
        AiVideoTaskAsset existing = assetMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, productSceneAsset.getItemId())
                .eq(AiVideoTaskAsset::getAssetType, "STORYBOARD_FIRST_FRAME")
                .last("LIMIT 1"));
        if (existing != null) {
            try {
                Path path = Path.of(existing.getFilePath()).toAbsolutePath().normalize();
                if (path.startsWith(storageRoot) && Files.isRegularFile(path) && Files.size(path) > 0) return existing;
            } catch (Exception ignored) { }
            assetMapper.deleteById(existing.getId());
        }
        try {
            Path source = Path.of(productSceneAsset.getFilePath()).toAbsolutePath().normalize();
            if (!source.startsWith(storageRoot) || !Files.isRegularFile(source)) {
                throw new IllegalStateException("AI 商品场景图不存在，无法复用为首帧");
            }
            String ext = extension(productSceneAsset.getContentType());
            Path output = source.getParent().resolve("storyboard-first-frame." + ext).normalize();
            if (!output.startsWith(storageRoot)) throw new IllegalStateException("商品首帧输出目录不安全");
            Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING);
            AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(),
                    productSceneAsset.getItemId(), "STORYBOARD_FIRST_FRAME", 1, output.toString(),
                    productSceneAsset.getContentType(), Files.size(output),
                    "复用脚本对齐的 AI 商品场景图作为第 01 分镜，不从九宫格裁切", productSceneAsset.getModelId());
            try {
                assetMapper.insert(asset);
                return asset;
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                return assetMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskAsset>()
                        .eq(AiVideoTaskAsset::getItemId, productSceneAsset.getItemId())
                        .eq(AiVideoTaskAsset::getAssetType, "STORYBOARD_FIRST_FRAME")
                        .last("LIMIT 1"));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("复用 AI 商品场景图作为首帧失败", exception);
        }
    }

    public AiVideoTaskAsset ensureStoryboardFirstFrame(AiVideoTask task, AiVideoTaskAsset storyboardAsset) {
        if (storyboardAsset == null || !"STORYBOARD_GRID".equals(storyboardAsset.getAssetType())) return null;
        AiVideoTaskAsset existing = assetMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, storyboardAsset.getItemId())
                .eq(AiVideoTaskAsset::getAssetType, "STORYBOARD_FIRST_FRAME")
                .last("LIMIT 1"));
        if (existing != null) {
            try {
                Path path = Path.of(existing.getFilePath()).toAbsolutePath().normalize();
                if (path.startsWith(storageRoot) && Files.isRegularFile(path) && Files.size(path) > 0) return existing;
            } catch (Exception ignored) { }
            assetMapper.deleteById(existing.getId());
        }
        try {
            Path storyboardPath = Path.of(storyboardAsset.getFilePath()).toAbsolutePath().normalize();
            if (!storyboardPath.startsWith(storageRoot) || !Files.isRegularFile(storyboardPath)) {
                throw new IllegalStateException("最终九宫格文件不存在，无法裁切首帧");
            }
            BufferedImage source = ImageIO.read(storyboardPath.toFile());
            BufferedImage firstFrame = cropFirstStoryboardCell(source);
            Path directory = storyboardPath.getParent();
            Path output = directory.resolve("storyboard-first-frame.png").normalize();
            if (!output.startsWith(storageRoot)) throw new IllegalStateException("首帧输出目录不安全");
            if (!ImageIO.write(firstFrame, "png", output.toFile())) {
                throw new IllegalStateException("首帧图片编码失败");
            }
            AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(),
                    storyboardAsset.getItemId(), "STORYBOARD_FIRST_FRAME", 1, output.toString(), "image/png",
                    Files.size(output), "从最终 4K 九宫格第 01 格自动裁切", storyboardAsset.getModelId());
            try {
                assetMapper.insert(asset);
                return asset;
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                return assetMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskAsset>()
                        .eq(AiVideoTaskAsset::getItemId, storyboardAsset.getItemId())
                        .eq(AiVideoTaskAsset::getAssetType, "STORYBOARD_FIRST_FRAME")
                        .last("LIMIT 1"));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("九宫格首帧裁切失败：" + exception.getMessage(), exception);
        }
    }

    static BufferedImage cropFirstStoryboardCell(BufferedImage source) {
        if (source == null || source.getWidth() < 3 || source.getHeight() < 3) {
            throw new IllegalStateException("最终九宫格图片无法读取");
        }
        int width = source.getWidth() / 3;
        int height = source.getHeight() / 3;
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, width, height, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private PiArtifact call(ModelConfigService.RuntimeModelConnection model, String prompt,
                            List<ReferenceImage> references) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", Map.of("baseUrl", model.baseUrl(), "modelId", model.modelId(),
                "apiFormat", model.apiFormat(), "apiKey", model.apiKey()));
        body.put("prompt", prompt);
        body.put("size", PORTRAIT_4K_SIZE);
        body.put("inputFidelity", references.isEmpty() ? "" : "high");
        body.put("references", references);
        PiImageResponse response = piClient.post().uri("/v1/images/generate")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(PiImageResponse.class);
        if (response == null || response.artifact() == null || response.artifact().dataBase64() == null) {
            throw new IllegalStateException("Pi Agent 图片工具没有返回图片");
        }
        return response.artifact();
    }

    private AiVideoTaskAsset store(AiVideoTask task, AiVideoTaskItem item, String type, Integer sequence,
                                   PiArtifact artifact, String prompt) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(artifact.dataBase64());
        if (bytes.length == 0 || bytes.length > 64L * 1024 * 1024) throw new IllegalStateException("生成图片大小无效");
        String ext = extension(artifact.mimeType());
        String fileName = "MODEL_IMAGE".equals(type)
                ? "model." + ext
                : "PRODUCT_SCENE_IMAGE".equals(type)
                    ? "product-scene." + ext
                : "STORYBOARD_GRID".equals(type)
                    ? "storyboard-grid." + ext
                    : "STORYBOARD_GUIDE".equals(type)
                        ? "storyboard-guide." + ext
                    : String.format("shot-%02d.%s", sequence, ext);
        Path directory = storageRoot.resolve(task.getOrganizationId()).resolve(task.getId()).resolve(item.getId()).resolve("outputs").normalize();
        if (!directory.startsWith(storageRoot)) throw new IllegalStateException("生成目录不安全");
        Files.createDirectories(directory);
        Path path = directory.resolve(fileName);
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(), item.getId(), type,
                sequence, path.toString(), artifact.mimeType(), bytes.length, prompt, artifact.modelId());
        assetMapper.insert(asset);
        return asset;
    }

    private String productScenePrompt(AiVideoTask task, ShotPrompt firstShot, boolean hasScriptFirstFrame) {
        String backgroundRequirement = hasScriptFirstFrame
                ? "参考图2是脚本第 01 分镜的真实背景，必须沿用其空间、家具、材质、光源、曝光、白平衡和机位关系，只移除旧主体并放入当前商品，不得重新设计或美化背景。"
                : "由模型根据商品类型、目标市场和展示方式搭配一个真实、克制、日常可落地的场景；不得复制脚本原视频房间，不使用空洞影棚、塑料感样板间或与商品无关的奢华布景。";
        StringBuilder prompt = new StringBuilder("【主体】基于参考商品图生成一张高保真电商商品场景主图。商品类型：")
                .append(safe(task.getProductCategory(), "通用商品")).append("。")
                .append("必须完整复制商品的颜色、结构、尺寸比例、Logo、包装文字、标签、图案、材质和边缘形状，")
                .append("不得重新设计、增加第二件同款、改变文字、改变颜色、融化、变形、破损或悬浮。")
                .append("【背景方式】").append(backgroundRequirement)
                .append("【分镜 01】").append(shotExecutionDirection(task, firstShot)).append("主体层级清楚，")
                .append("构图像真实商业摄影而非素材拼贴，前景、主体和背景保持可信的尺寸与透视关系。")
                .append("【光线与材质】").append(REAL_PHOTOGRAPHY_REQUIREMENT)
                .append(FABRIC_AND_PRODUCT_REALISM_REQUIREMENT)
                .append("商品与承托面建立真实接触和阴影，背景干净，无水印和促销文字。")
                .append("【输出】").append(PORTRAIT_4K_REQUIREMENT);
        return prompt.toString();
    }

    private String modelWearPrompt(AiVideoTask task, AiVideoTaskItem item, ShotPrompt firstShot, boolean hasScriptFirstFrame) {
        String sceneRequirement = hasScriptFirstFrame
                ? "【脚本首帧只提供背景】参考图2仅用于锁定脚本第 01 分镜的房间、墙面、门窗、家具、物品、材质磨损、光源方向、阴影、曝光、白平衡、景深、机位方向与人物所处位置，不得重新设计、整理、美化或替换这些背景信息。参考图2的远近景别和人物尺寸不作为约束，必须在相同机位方向上把相机向人物推进约二分之一距离，并按近距离清晰构图重新取景。参考图2中的旧人物不是人物参考，必须完整移除；绝对不能继承或融合旧人物的五官、发型、发色、肤色、年龄、身材、妆容、表情、配饰、上装、下装或鞋履。新人物继续使用本提示词规定的独立模特五官与发型特色，只把参考图1商品准确穿在新人物身上；与原生成逻辑相比，背景空间与机位方向来自参考图2。"
                : "【AI 自动搭配背景】不要复刻脚本原视频房间。根据商品颜色、品类、目标市场与第 01 分镜动作，设计一个真实有人居住、适合该商品的普通日常空间；后续九宫格以此为视觉母版。画面可自然出现少量书、杯子、折叠衣物、充电线、使用过的家具或不完全对齐的靠垫，但不能脏乱或遮挡商品。保留轻微材质磨损、墙面色差和自然遮挡，不要使用空样板间、完全对称布置、统一米白影棚或奢华网红布景。";
        return "【REALISM V3｜手机日常随拍任务】参考图1是唯一商品素材。基于参考图1生成一张普通人用手机主摄在较近距离随手拍下的竖版服装展示照片。"
                + "日常生活感是最高风格优先级：成片应像同事、朋友或家人在普通居住空间临时记录今天穿搭，"
                + "而不是品牌广告、影棚 Lookbook、网红写真、模特卡或精修样片。目标市场："
                + safe(task.getTargetMarket(), "通用电商市场") + "。只出现一位成年人物，头顶、五官、胸前商品、双臂和双手完整清楚入镜。"
                + FRIENDLY_MODEL_FACE_BASELINE
                + "人物大致面向镜头但不要证件照式立正：身体相对画面中心轻微偏移，重心自然落在一条腿上，两侧肩髋不完全水平，"
                + "双臂以略有差异的方式自然下垂，一只手可轻微弯曲，头部有极小角度偏转，眼神像刚注意到拍摄者；"
                + "表情处在尚未刻意摆拍的自然瞬间，可以只是轻微嘴角变化，不能露出模板化甜美微笑。"
                + "姿态松弛但能够完整展示上衣，不摆夸张时尚姿势；身体比例、关节和五根手指正确。"
                + "【视频第 01 分镜】这张 AI 模特图同时就是后续视频直接使用的唯一高清首帧，不再另外生成首帧。"
                + "脚本第 01 分镜只提供动作、机位、场景、节奏和微表情；其中若残留原视频人物、发型、穿搭、商品、品牌、颜色、版型、印花、文字、Logo、字幕或卖点，全部忽略。"
                + "必须从一开始执行第 01 分镜：目标：" + safe(firstShot.title(), "自然开场展示")
                + "；动作：" + safe(firstShot.motion(), "人物自然站立并看向镜头附近")
                + "；景别固定：头顶至大腿中下段、接近膝盖上方的中近景，不读取原脚本中的全身或远景描述"
                + "；运镜起点：" + safe(firstShot.cameraMovement(), "固定机位")
                + "；情绪：" + safe(firstShot.emotion(), "自然、松弛、亲和")
                + "；场景：" + safe(firstShot.scene(), "按第 01 分镜建立真实场景")
                + "；手部规则：" + shotHandDirective(task, firstShot) + "。"
                + "【首帧清晰正脸约束】无论脚本原人物或第 01 分镜描述是否侧身、回眸或低头，视频身份首帧都先建立可识别的近正面人脸："
                + "脸部可保留约 2–10 度的自然轻微偏转或侧倾，双眼、眉毛、鼻梁、鼻翼、嘴唇和下颌均清楚可见；"
                + "两只眼睛共同看向镜头附近而非僵直盯住镜头，头发不遮挡主要五官。上半身接近正面，胸前商品完整无遮挡；"
                + "脚本中的侧身、转头、回眸和低头动作统一延后到第 02 格以后再开始。该正脸身份锚点优先级高于第 01 分镜动作。"
                + "画面必须是动作刚开始且可以自然进入第 02 分镜的稳定姿态，不能生成侧脸、背脸、低头闭眼或无法进行人脸捕捉的首帧。"
                + "【商品保真】必须保持参考服装的颜色、版型、领口、袖长、纹理、缝线、印花内容、位置和比例完全一致，"
                + "不得增加、删除、简化或重新设计服装细节。" + FABRIC_AND_PRODUCT_REALISM_REQUIREMENT
                + "【独立造型设计与商品最高优先级】参考图1中的展示商品是整套造型唯一不可改变的核心，先识别它属于上装、下装、裙装、套装或其他品类，"
                + "其颜色、图案、文字、版型、长度、面料和所有可见细节拥有最高优先级。参考图2旧人物的发型、下装、鞋子、配饰和整体搭配全部无效，不得照搬。"
                + "本款采用独立造型方向：" + stylingDirection(item) + "。如果参考商品是上装，按造型方向选择下装；如果参考商品本身是裤子或半身裙，"
                + "必须保留该商品并改为选择协调但不抢眼的上装；如果是连衣裙或完整套装，不得再叠加会遮挡或替换商品的上下装。"
                + "所有补充服饰与鞋履必须真实存在、尺寸合身、颜色协调，不能遮挡商品主体或改变商品版型；配饰保持少量克制。不同商品允许使用长裤、短裤、半身裙及不同颜色组合，"
                + "但同一款商品在首帧和九宫格中必须始终保持同一套搭配，不得逐格换装。"
                + sceneRequirement + CLOSE_PORTRAIT_COMPOSITION_REQUIREMENT
                + "构图轻微偏心但稳定，垂直线保持自然，不使用广角，不拉长四肢，不把脸或手贴边。拍摄瞬间允许一次极轻微呼吸、"
                + "不完全水平的肩线和非对称手指放松状态，不要求人物像证件照一样屏息定格。"
                + "【光线与皮肤】" + REAL_PHOTOGRAPHY_REQUIREMENT + PORTRAIT_SKIN_QUALITY_REQUIREMENT
                + FACE_COLOR_CONTINUITY_REQUIREMENT
                + "【消除假人感】" + ANTI_MANNEQUIN_REQUIREMENT
                + "【头发】" + NATURAL_HAIR_REQUIREMENT
                + "【眼神】" + NATURAL_EYE_REQUIREMENT
                + "【手机直出】保留手机主摄轻微的传感器纹理、不过分纯净的中间调、有限但自然的动态范围和局部微对比；"
                + "不能通过加明显噪点、胶片滤镜、模糊或低清来伪装真实，也不要模拟专业棚拍。"
                + "输出前检查：如果画面像 AI 网红写真、证件照、3D 渲染或样板间广告，则重新生成更普通、更松弛、更有现场感的版本。"
                + "【输出】" + PORTRAIT_4K_REQUIREMENT + "无字幕、无水印、无额外人物、无多余肢体。";
    }

    private String stylingDirection(AiVideoTaskItem item) {
        List<String> bottoms = List.of(
                "深靛蓝高腰直筒牛仔长裤，裤型自然利落",
                "炭灰色垂坠感阔腿长裤，腰线干净",
                "浅卡其色利落休闲短裤，长度得体",
                "复古蓝高腰牛仔短裤，剪裁简洁",
                "深咖色 A 字中长半身裙，裙摆自然",
                "雾灰蓝色直筒中长半身裙，轮廓克制",
                "低饱和卡其色 A 字短裙，长度得体且适合日常",
                "黑色简洁百褶中长裙，褶皱真实不过密");
        List<String> hairStyles = List.of(
                "自然披发并整理耳侧碎发",
                "松弛低马尾，发际线保留少量自然碎发",
                "简洁低丸子头，轮廓整齐不僵硬",
                "自然半扎发，发束顺着重力落下");
        List<String> accessories = List.of(
                "不添加明显配饰",
                "只佩戴一对小尺寸素色耳钉",
                "只佩戴一只简洁日常腕表",
                "只使用一条极细无吊坠项链");
        int seed = (item.getId() + "|" + item.getName()).hashCode();
        String bottom = bottoms.get(Math.floorMod(seed, bottoms.size()));
        String hair = hairStyles.get(Math.floorMod(seed / 17, hairStyles.size()));
        String accessory = accessories.get(Math.floorMod(seed / 31, accessories.size()));
        return hair + "；优先尝试" + bottom + "；" + accessory
                + "。这是用于拉开批量款式差异的造型方向，不是必须生搬硬套的单品清单；和谐度高于类别轮换。"
                + "首帧只展示到大腿中下段、接近膝盖上方，不描述也不展示鞋履。"
                + "如果该方向与参考商品的品类、长度、版型、颜色或场景冲突，必须由服装造型师视角改选更协调的裙装、短裤或长裤，"
                + "但不得改成参考图2旧人物的原搭配，也不得覆盖参考商品";
    }

    private String productStoryboardGridPrompt(AiVideoTask task, List<ShotPrompt> shots, boolean realFrameGuide) {
        String backgroundRequirement = preserveScriptBackground(task)
                ? (realFrameGuide
                    ? "背景逐格沿用参考图1对应位置的原视频真实场景；连续分镜锁定空间、材质、光线和机位关系，原视频明确换景时才切换。"
                    : "背景沿用已生成的脚本原场景主图；连续分镜锁定同一空间、材质、光线和机位关系，脚本明确换景时才切换。")
                : "背景以已生成的 AI 场景主图为视觉母版；根据各分镜动作合理延展空间，保持同一套真实摄影风格，脚本明确换景时才设计协调的新场景。";
        String referenceContract = realFrameGuide
                ? "参考图1是原视频按九个分镜时间中点抽取并在后端拼成的真实 3×3 画面母版；参考图2是用户上传商品外观唯一母版。"
                    + "两张参考图职责不可互换：参考图1逐格决定机位、景别、商品位置与尺度、手部有无、手部进入方向、接触点、动作阶段、环境和光影；"
                    + "参考图2以最高优先级锁定商品颜色、结构、文字、Logo、图案、标签和材质。"
                    + "参考图1中的旧商品外观必须全部替换为参考图2，但不得借替换之名改变参考图1的动作、构图、景别或手部时序。"
                : "参考图1是商品场景与构图参考，参考图2是用户上传商品外观唯一母版，参考图2的商品拥有最高优先级。";
        String productMasterName = "参考图2";
        StringBuilder prompt = new StringBuilder(referenceContract)
                .append("生成一张严格的 3×3 连续商品展示九宫格，按从左到右、从上到下对应 01→09。")
                .append(PORTRAIT_4K_REQUIREMENT)
                .append("先锁定").append(productMasterName).append("中商品的轮廓与表面内容，再生成九格；九格必须逐像素级保持同一件商品的颜色、结构、比例、Logo、包装文字、标签、插画、人物图案和材质一致。")
                .append("禁止采用分镜脚本文字或原视频中出现的任何其他商品、品牌、颜色、Logo、包装、印花或文案。")
                .append("不得把").append(productMasterName).append("的复杂插画或印花替换成纯文字、其他商品主题或模型自行设计的图案，也不得简化、概括或重新排版。")
                .append(backgroundRequirement)
                .append(realFrameGuide
                        ? "九格必须逐格对照参考图1的同一位置复刻，不能只模仿整体风格。参考图1某格无人手时该格绝对不能出现手、手臂或人体；"
                            + "某格有手时必须复刻同一只手的进入方向、掌心朝向、手指姿态、遮挡区域和接触位置。参考图1的光斑、轻微推移、商品占比和俯拍角度同样逐格保留。"
                        : "没有真实抽帧母版时，严格按照下面每格文字执行手部与构图状态。")
                .append("每格场景、展示方式、构图和手部状态只读取对应分镜，禁止用任一格覆盖九格。")
                .append("相邻格只推进一个小幅动作或镜头变化；商品不得复制、跳变、漂移、悬浮、融化、穿模或突然更换背景。")
                .append("每格都必须保持真实摄影的透视、接触阴影和光源方向，并让商品材质对光线产生正确响应。")
                .append(FABRIC_AND_PRODUCT_REALISM_REQUIREMENT)
                .append("连续分镜若都要求手部互动，必须保持同一双手、相同方向和连续接触点；只在脚本指定的分镜边界出现或退出手部。")
                .append("不得添加编号、文字、边框、水印、额外商品或无关配件。每格要求如下：\n");
        for (ShotPrompt shot : shots) {
            String value = productMotionDirection(task, shot);
            if (value.length() > 600) value = value.substring(0, 600);
            prompt.append("分镜 ").append(String.format("%02d", shot.sequence())).append("：").append(value).append('\n');
        }
        prompt.append(realFrameGuide
                        ? "输出前把生成结果与参考图1逐格核对：01→09 的手部有无、进入方向、接触点、商品位置、尺度、景别与光影阶段必须一一对应；"
                        : "输出前逐格核对手部状态、商品位置、尺度、景别与光影阶段；")
                .append("同时检查商品数量唯一、文字和 Logo 未改变、结构未变形、接触与阴影真实；任何一格不合格时只重算该格。")
                .append("最终只输出一张 2160×3840 的完整 4K 九宫格图片。");
        return prompt.toString();
    }

    private String productMotionDirection(AiVideoTask task, ShotPrompt shot) {
        StringBuilder value = new StringBuilder();
        if (!shot.title().isBlank()) value.append("目标：").append(shot.title()).append("；");
        if (!shot.scene().isBlank()) value.append("场景与构图：").append(shot.scene()).append("；");
        if (!shot.motion().isBlank()) value.append("动作：").append(shot.motion()).append("；");
        if (!shot.shotSize().isBlank()) value.append("景别：").append(shot.shotSize()).append("；");
        if (!shot.cameraMovement().isBlank()) value.append("运镜：").append(shot.cameraMovement()).append("；");
        if (value.isEmpty()) value.append("保持同一商品，进行轻微且连续的电商展示变化；");
        value.append("手部：").append(shotHandDirective(task, shot)).append("；");
        value.append("只执行动作与运镜，任何商品外观描述均无效，商品始终严格复制参考图2。");
        return value.toString();
    }

    private String storyboardGridPrompt(AiVideoTask task, List<ShotPrompt> shots) {
        String backgroundRequirement = preserveScriptBackground(task)
                ? "尽量按照脚本对场景、空间和换景关系的描述建立环境；没有真实抽帧可用时只做克制补全，不擅自改变场景类型。"
                : "不要复刻原视频背景；以参考图1的日常摄影风格为母版，根据商品、目标市场和每个分镜的场景与动作语义主动设计协调的真实环境。"
                    + "必须保留脚本明确要求的场景类型、场景切换、站位和构图，只重新设计环境的具体陈设；所有场景保持同一套真实程度、色彩和生活气质。";
        StringBuilder prompt = new StringBuilder("参考图1只作为人物尺度与真实手机摄影质感标尺，不复制其中的具体商品。"
                + "借用参考图1的真实光源、自然曝光、白平衡、动态范围、材质细节、空气透视和有人日常使用过的生活痕迹；"
                + backgroundRequirement
                + "九格须按各自分镜建立对应环境，不能无条件把九格固化成同一个背景。"
                + "生成一张严格的 3×3 九宫格连续动作控制图。本图只负责动作、站位、景别、机位和环境，"
                + "不得生成任何可识别的真实人物身份。每格使用同一个无脸中性人体模型或简洁写实的人体姿态轮廓："
                + "脸部保持空白，不画五官、发型、肤色、年龄特征，不穿带有颜色、图案、文字或款式特征的具体服装。"
                + "人体只用于准确表达关节、肢体朝向、重心和动作轨迹；后续视频会用独立模特图替换该人体。"
                + PORTRAIT_4K_REQUIREMENT
                + "整张图必须正好包含 9 个大小一致的 9:16 竖屏画面，每格在 4K 总图中约为 720×1280 像素，"
                + "按从左到右、从上到下的顺序对应分镜 1 到 9。01→09 是同一个镜头内按时间先后发生的连续动作关键帧，"
                + "不是 9 张互不相关的摆拍照片。相邻两格之间只能推进一个主要动作，姿势变化必须小而可达；"
                + "上一格的结束姿态必须自然衔接下一格的开始姿态，禁止人物瞬移、肢体跳变、突然回正、脚底换位、镜头变焦或场景重置。"
                + "01/04/07 是每段起始关键帧，02/05/08 是动作中间态，03/06/09 是动作结束态；"
                + "同一段三格必须清楚呈现起势→过程→到位，且第 03 格能平滑衔接第 04 格、第 06 格能平滑衔接第 07 格。"
                + "同一场景的连续分镜必须锁定机位、焦段、背景结构、光线、人物地面位置与身体尺度；"
                + "脚本明确换场景、坐立变化或商品特写时必须按对应分镜切换环境与构图，不能把九格都固化成第 01 格背景。"
                + GRID_FACE_PIXEL_REQUIREMENT
                + "除脚本明确要求外，连续动作中的双脚支撑点保持稳定。"
                + "九格中的无脸人体模型必须保持相同身体比例，但不携带任何人物身份与穿搭信息；分镜描述中的人物外貌、发型、衣服和配饰一律忽略。"
                + "分镜描述中的原视频商品类别、颜色、版型、材质、印花、文字、Logo、字幕和卖点同样一律忽略；动作区域只允许称为当前任务商品，最终商品以任务上传商品图和后续模特图为准。"
                + "所有姿态必须符合真实人体骨骼、关节角度、重心与惯性；手臂和手指不得穿过身体或衣服，双腿不得粘连，"
                + "人物不得穿入家具、墙面或地面，不得出现反关节、肢体增减、手指融合、身体扭曲、重影或衣服穿模。"
                + "不要添加文字、数字、边框、水印或额外格子。每格内容如下：\n");
        for (ShotPrompt shot : shots) {
            String value = shot.prompt().replaceAll("\\s+", " ").trim();
            if (value.length() > 800) value = value.substring(0, 800);
            prompt.append("分镜 ").append(shot.sequence()).append("：").append(value)
                    .append("；执行校验：").append(shotExecutionDirection(task, shot)).append("\n");
        }
        prompt.append("最终只输出一张 2160×3840 的完整 4K 九宫格图片，不得降级为 1024 像素级别。"
                + "构图清楚、格子边界规整，背景真实自然，无多余肢体。输出前逐格核对：九格不得出现真实人脸或具体服装，"
                + "且任意相邻两格都能由真实人体在短时间内连续完成；任何一格出现穿模、反关节或诡异肢体都必须重算该格。");
        return prompt.toString();
    }

    private String storyboardIdentityTransferPrompt(AiVideoTask task, List<ShotPrompt> shots, boolean realFrameGuide) {
        StringBuilder prompt = new StringBuilder("执行一次严格的九宫格人物替换编辑。参考图1是必须保留内容和构图的 3×3 动作场景底图；")
                .append("参考图2是唯一人物身份、完整穿搭和整体摄影风格母版。参考图2中的人物已经穿好最终商品，是已经完成的成衣结果，不需要也不允许再次试衣、换衣或贴图。")
                .append("保持参考图1每一格指定的场景类型、主要物体、空间关系、机位方向、站位、动作姿态和时间顺序；原图中的远近景别和人物尺寸不保留，所有人物格必须重新按本提示词的近距离比例构图。")
                .append(realFrameGuide
                        ? "参考图1来自原视频真实抽帧。第 01 格以参考图2的人物身份、穿搭、背景和动作起点为准；第 02–09 格保留对应真实背景的墙面、门窗、家具、物品、磨损、阴影、曝光、白平衡和景深特征，但允许为了相机向前推进约二分之一而自然放大和重新取景。禁止把九格改造成样板间、木柜棚景、统一房间或模型新设计的室内布景。"
                        : "当前任务选择 AI 自动搭配背景。参考图1决定每格动作、站位、景别、场景类型、场景切换和构图，参考图2提供人物、穿搭与真实摄影风格；不复刻脚本原视频房间，只重新设计每格环境的具体陈设。若背景存在塑料感、CG感、假材质、无来源高光或过度整洁，必须按参考图2的真实摄影标准重建材质与光线。")
                .append(realFrameGuide
                        ? "新人物必须适应参考图1各格原有的真实光源、色温、曝光、阴影与景深；不得反过来修改背景去迁就人物。"
                        : "但九格的色温、曝光、对比度、手机随拍质感、光线自然度、材质表现和日常生活氛围统一匹配参考图2。")
                .append(realFrameGuide
                        ? "参考图2只为第 01 格提供人物、成衣、背景和动作起点；第 02–09 格只继承其中的人物与成衣，不能把参考图2的房间复制到后八格。不同分镜原本存在的换景和商品特写必须保留；除纯商品特写和必须展示脚部的动作外，人物格全部统一为头顶至大腿中下段的近距离比例。"
                        : "只迁移参考图2的视觉风格，不能把九格背景全部替换成参考图2的同一个房间，也不能抹掉参考图1要求的换景和商品特写。")
                .append("把九格中的每一个无脸人体模型逐格替换为参考图2中的同一个真实人物。必须复制参考图2的同一张脸、五官比例、肤色、")
                .append("发型、发长、身材比例、上衣、下装、鞋袜和配饰；九格人物必须是同一个人、同一套穿搭，不得重新选角或重新设计服装。")
                .append("【人脸身份最高优先级】参考图2的脸是唯一合法人脸母版。参考图1即使残留原视频人物的脸、五官、年龄、肤色或表情，也全部视为需要被删除的旧主体，绝对不能继承、融合或折中。")
                .append("逐格保持参考图2的脸型轮廓、眉形、眼裂、眼距、虹膜、鼻梁、鼻翼、嘴唇、下颌线和年龄观感；只允许因转头、透视和轻微表情产生正常变化。")
                .append("如果动作姿态与人脸身份发生冲突，优先保住参考图2的人脸身份与自然五官，再对姿态做最小幅度调整；禁止重新抽取另一位模特。")
                .append("九格审美基调完全继承参考图2，不得为了所谓美化重新画脸，也不得把参考图2变老、变疲惫、变凶或改变亲和感。")
                .append("【第 01 格硬约束】参考图2本身就是最终视频第 01 分镜的人物与动作母版。九宫格左上角第 01 格必须复现参考图2的同一人物、五官、穿搭、姿态、背景关系和清晰度，但必须按头顶至大腿中下段的近距离比例重新取景；第 02–09 格从它开始连续推进。")
                .append(GRID_FACE_PIXEL_REQUIREMENT)
                .append("参考图2如果是远景，不沿用它的人物尺寸；只沿用同一人物、穿搭、背景与机位方向，在每格中把相机距离推进约二分之一。第 01 格必须按该近距离规则重新构图，但人物身份、姿态起点和背景关系保持不变。")
                .append("直接复制参考图2中上衣已经穿在人物身上的最终视觉结果，包括颜色、领型、袖长、版型、面料纹理、缝线、印花文字、数字、图形、大小、位置与排列顺序。")
                .append("不得重新理解商品、不得重新生成一件相似款、不得把上衣从人物身上移除后重新穿戴，也不得改字、漏字、增加装饰或改变版型。")
                .append("侧身、坐姿和特写只允许产生符合真实透视的轻微布料形变，商品身份和全部可见设计必须保持不变。")
                .append(PORTRAIT_SKIN_QUALITY_REQUIREMENT)
                .append(FACE_COLOR_CONTINUITY_REQUIREMENT)
                .append(NATURAL_HAIR_REQUIREMENT)
                .append(NATURAL_EYE_REQUIREMENT)
                .append(DIGNIFIED_STYLING_REQUIREMENT)
                .append(REAL_PHOTOGRAPHY_REQUIREMENT)
                .append(FABRIC_AND_PRODUCT_REALISM_REQUIREMENT)
                .append("九格必须锁定参考图2的同一套基础肤色、脸与脖子的色差关系、曝光、白平衡和锐度参数，不能逐格重新美颜、重新上妆或改变皮肤质感。")
                .append("微表情不需要每格都变化。脚本未明确写出笑、眨眼、视线转移、说话、挑眉或其他面部动作时，后一格必须沿用前一格的自然表情，")
                .append("不能为了让九格看起来不同而逐格重新生成眉毛、眼睑、眼球、鼻翼、嘴唇和嘴角。只有明确的面部动作才允许建立一次起势→过程→到位的连续变化，不能突然大笑、张大嘴、瞪眼或改变年龄和脸型。每格面部状态如下：\n");
        for (ShotPrompt shot : shots) {
            String expression = inferMicroExpression(shot);
            prompt.append("分镜 ").append(String.format("%02d", shot.sequence()))
                    .append("：场景与动作按该格底图执行；").append(shotHandDirective(task, shot)).append("；")
                    .append(expression).append("；视线与该格动作目标一致，双眼共同对焦。\n");
        }
        prompt.append("仅允许改变人物姿态和上述微表情以贴合参考图1，人物身份和服装纹理不得随姿态改变。")
                .append("【异常规避优先级】九宫格的可用性高于复杂动作的精确复刻。如果某个动作会导致脸部崩坏、手指异常、肢体穿模、重心失衡或服装图案扭曲，")
                .append("必须把该格动作幅度缩小为解剖正确的中间姿态，不能带着异常输出。每个有人格必须且只能出现一个头、一个躯干、两条手臂、两只手、两条腿和两只脚；")
                .append("可见手掌必须为五指且连接正确，头颈肩连接自然，脚底与地面接触，鞋履完整。侧身格也必须保持同一张脸、同一发型、同一发长和同一套穿搭。")
                .append("第 01 格必须严格复现参考图2的清晰正脸身份锚点；第 02–09 格才按脚本逐步进行转头、侧身、回眸或低头，禁止第 01 格再次被改成侧脸。")
                .append("人物必须正确落在地面并与环境建立合理遮挡，手臂、手指、头发和双腿不得穿过身体、衣服、家具或墙面；")
                .append("输出前逐格检查人物边界与脸部像素：头顶、完整五官、胸前商品、双臂和执行动作的手不得越界、贴边或被截断；默认在大腿中下段至膝盖上方结束构图。人脸低于约 190 像素、五官无法辨认或人物被缩成远景时，必须保持原动作把相机推进并重新构图，不得通过后期裁切放大补救。")
                .append("不得出现换脸、脸型漂移、年龄变化、发型变化、衣服变化、图案乱码、反关节、手指融合、肢体增减、身体扭曲或重影。")
                .append("最终只输出一张 2160×3840 的 9:16 竖屏 4K 九宫格成图，不输出解释、文字、编号、水印或额外画面。")
                .append("输出前逐格进行人脸、脸颈肤色、眼神、头发、穿搭和背景风格一致性核验；重点对比每格脸、耳朵、脖子和手臂，"
                        + "出现偏灰、偏黄、偏绿、局部泛红、面具边界或与参考图2肤色不一致时，该格视为不合格并只重算人物。"
                        + "任意一格不一致时不得改变其他格；即使需要重算，也保持该格原有场景内容与构图。");
        return prompt.toString();
    }

    private String inferMicroExpression(ShotPrompt shot) {
        String context = (shot.title() + " " + shot.motion()).toLowerCase();
        if (containsAny(context, "眨眼", "闭眼", "睁眼")) {
            return "按动作只完成一次双眼同步的完整眨眼，其他五官保持稳定，不连续眨眼或眼睑颤动";
        }
        if (containsAny(context, "微笑", "笑", "开心", "愉悦")) {
            return "嘴角从原状态缓慢形成轻微自然微笑并稳定保持，眉毛、鼻翼与下颌不跟随抽动";
        }
        if (containsAny(context, "说话", "口播", "台词", "讲解")) {
            return "仅保留与明确台词对应的克制口型，嘴唇连续平滑运动，鼻翼、眉毛和视线不被口型带动";
        }
        if (containsAny(context, "看向", "视线", "回眸", "低头", "抬头")) {
            return "双眼只向动作目标平滑移动一次并稳定对焦，嘴角、眉毛和鼻翼沿用上一格状态";
        }
        return "沿用上一格的放松自然表情，闭口稳定，不新增嘴角、眉毛、鼻翼、眼睑或眼球动作";
    }

    private String shotExecutionDirection(AiVideoTask task, ShotPrompt shot) {
        StringBuilder value = new StringBuilder();
        if (!shot.scene().isBlank()) value.append("场景与构图严格按脚本：").append(shot.scene()).append("；");
        if (!shot.motion().isBlank()) value.append("动作严格按脚本：").append(shot.motion()).append("；");
        if (!shot.shotSize().isBlank()) value.append("景别：").append(shot.shotSize()).append("；");
        if (!shot.cameraMovement().isBlank()) value.append("机位与运镜：").append(shot.cameraMovement()).append("；");
        value.append("手部规则：").append(shotHandDirective(task, shot)).append("；");
        return value.toString();
    }

    private String shotHandDirective(AiVideoTask task, ShotPrompt shot) {
        String context = (shot.title() + " " + shot.scene() + " " + shot.motion() + " " + shot.prompt()).toLowerCase();
        boolean explicitlyNoHand = containsAny(context, "无人手", "无手部", "不出现手", "没有手", "纯静物", "平铺", "仅商品", "no hand", "hands-free");
        boolean requiresHand = !explicitlyNoHand && containsAny(context,
                "手持", "拿取", "拿起", "拿着", "握住", "抓住", "托住", "捏住", "摆放", "放下", "拆封", "开箱", "双手", "单手", "手掌", "手指",
                "hold", "holding", "pick up", "grip", "handheld", "unbox");
        if ("MODEL_WEAR".equals(task.getGenerationMode())) {
            return requiresHand
                    ? "只执行本格写明的手势、拿取或触碰，双手数量、朝向、接触位置与脚本一致，不新增其他手势"
                    : "人物双手保持该格自然姿势，不新增拿取、指向、拉扯、点赞、挥手或持物动作";
        }
        return requiresHand
                ? "只出现完成本格动作所需的一双自然手，数量、方向、接触位置与脚本一致；其他格不得继承"
                : "不得出现人物、手臂、手掌或手指，只保留商品与脚本规定的承托关系";
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<ShotPrompt> readShots(String json) throws Exception {
        Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        Object raw = root.get("shots");
        if (!(raw instanceof List<?> values)) return List.of();
        List<ShotPrompt> shots = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> shot)) continue;
            int sequence = number(shot.get("sequence"), shots.size() + 1);
            String prompt = "";
            Object imagePrompt = shot.get("imagePrompt");
            if (imagePrompt instanceof Map<?, ?> imageMap) {
                Object promptValue = imageMap.get("prompt");
                prompt = promptValue == null ? "" : String.valueOf(promptValue);
            }
            if (prompt.isBlank() || "null".equals(prompt)) {
                prompt = safe(String.valueOf(shot.get("sceneDescription")), "按当前分镜生成真实电商画面");
            }
            shots.add(new ShotPrompt(sequence, prompt,
                    nullableText(shot.get("title")), nullableText(shot.get("sceneDescription")), nullableText(shot.get("motionDescription")),
                    nullableText(shot.get("shotSize")), nullableText(shot.get("cameraMovement")),
                    nullableText(shot.get("emotion"))));
        }
        shots.sort(Comparator.comparingInt(ShotPrompt::sequence));
        return shots;
    }

    private ReferenceImage reference(Path path, String name, String mimeType) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return new ReferenceImage(name, mimeType, Base64.getEncoder().encodeToString(bytes));
    }
    private int number(Object value, int fallback) { try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private String extension(String mime) { return "image/jpeg".equalsIgnoreCase(mime) ? "jpg" : "image/webp".equalsIgnoreCase(mime) ? "webp" : "png"; }
    private boolean preserveScriptBackground(AiVideoTask task) { return !"AI_DESIGNED".equals(task.getBackgroundMode()); }
    private String safe(String value, String fallback) { return value == null || value.isBlank() || "null".equals(value) ? fallback : value; }
    private String nullableText(Object value) { return value == null || "null".equals(String.valueOf(value)) ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim(); }
    private String readable(Exception exception) {
        Throwable source = exception;
        while ((source instanceof CompletionException || source.getMessage() == null) && source.getCause() != null) {
            source = source.getCause();
        }
        String value = source.getMessage() == null ? "图片生成失败" : source.getMessage().replaceAll("\\s+", " ").trim();
        String marker = "\"message\":\"";
        int markerIndex = value.indexOf(marker);
        if (markerIndex >= 0) {
            int messageStart = markerIndex + marker.length();
            int messageEnd = value.indexOf('"', messageStart);
            if (messageEnd > messageStart) value = value.substring(messageStart, messageEnd);
        }
        return value.substring(0, Math.min(value.length(), 1000));
    }

    private record ShotPrompt(int sequence, String prompt, String title, String scene, String motion, String shotSize,
                              String cameraMovement, String emotion) {}
    private record ReferenceImage(String name, String mimeType, String dataBase64) {}
    private record PiImageResponse(PiArtifact artifact, Map<String, Object> usage) {}
    private record PiArtifact(String name, String mimeType, String dataBase64, String modelId, String mode,
                              String prompt, String revisedPrompt) {}
}
