package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.domain.AiVideoPendingImageResult;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.domain.VideoScriptShot;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoPendingImageResultMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiVideoImageProcessor {
    private static final Logger log = LoggerFactory.getLogger(AiVideoImageProcessor.class);
    static final int MAX_TOPOLOGY_REDRAWS = 3;
    static final int MAX_TOPOLOGY_GENERATIONS = 1 + MAX_TOPOLOGY_REDRAWS;
    private static final double STORYBOARD_FIRST_CELL_SAFE_SCALE = 0.95d;
    private static final String CLOSE_FRAMING_GATE_VERSION = "CLOSE_FRAMING_GATE_V1";
    static final String PRODUCT_REAL_FRAME_BACKGROUND_REQUIREMENT =
            "背景逐格沿用参考图2对应位置的原视频真实场景；连续分镜锁定空间、材质、光线和机位关系，原视频明确换景时才切换。";
    static final String PRODUCT_REAL_FRAME_OUTPUT_CHECK =
            "输出前把生成结果与参考图2逐格核对：01→09 的手部有无、进入方向、接触点、商品位置、朝向、动作与光影阶段必须一一对应；"
                    + "景别和主体尺度不继承原远景，所有商品出镜格统一为主体填满主要画面的商品近景；";
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
            + "眼睛大小与脸型比例自然，不追求网红大眼。嘴唇放松且自然闭合，不露齿、不张口，不呈现说话、唱歌或对口型姿态；眼神有明确落点，像朋友用手机临时拍下的自然一瞬。";
    private static final String ANTI_MANNEQUIN_REQUIREMENT = "人物处在真实呼吸与承重状态：一侧肩髋略低，重心落在一条腿，构图内可见手臂姿态不完全相同，"
            + "颈肩、锁骨和衣料褶皱呈现同一受力关系。人物边缘、发丝、皮肤和背景都服从同一现场光线；"
            + "保留手机照片自然的局部锐度差和暗部细微传感器纹理，4K 只增加真实信息，不把人脸处理成均匀、对称、无表情的假人。";
    private static final String FACE_COLOR_CONTINUITY_REQUIREMENT = "【肤色连续】脸、耳、颈、锁骨、手臂和双腿属于同一暖中性自然肤色，"
            + "明暗与冷暖只随现场光线渐变；脸颊可有极淡且不对称的血色，鼻翼和眼下保留细微真实色差。"
            + "不得出现灰脸、黄绿脸、单独脸部滤镜、粉底面具边界或脸颈色温断层。";
    private static final String CLOSE_PORTRAIT_COMPOSITION_REQUIREMENT = "【中近景门禁版本：CLOSE_FRAMING_GATE_V1】【近距离清晰构图｜最高优先级】相机相对原全身构图向前推进约二分之一距离，"
            + "这不是完整全身照。按示例红框比例构图：画面上沿在头顶上方保留 5%–8% 空间，画面下沿必须明确落在大腿中下段至膝盖上方，膝盖、小腿、双脚和鞋子必须位于画面之外且不可见。"
            + "头顶、完整五官、胸前商品、双肩、构图内可见手臂和执行动作实际需要的手必须清楚入镜；不可见的手臂或手无需补出，不能为了保留双脚或展示整套穿搭而缩小人物。"
            + "人脸从发际线到下巴的有效高度不得低于整张 2160×3840 图片高度的 15%，约 575 像素，"
            + "双眼虹膜边缘、上下眼睑、鼻翼、唇线、毛孔和细小绒毛必须具有真实可辨的像素信息。必须通过真实相机靠近和重新构图获得清晰度，禁止先生成远景再数码放大、裁切或锐化。";
    private static final String GRID_FACE_PIXEL_REQUIREMENT = "【中近景门禁版本：CLOSE_FRAMING_GATE_V1】【九宫格中近景硬约束｜覆盖脚本景别】每个 720×1280 子画面只要有人物，就必须采用头顶至大腿中下段、接近膝盖上方的中近景。"
            + "格子下边界必须落在大腿中下段至膝盖上方，膝盖、小腿、双脚和鞋子不可见且始终位于画面之外；正面或侧脸可见格中，脸从发际线到下巴不得低于单格高度的 15%，约 190 像素。背身、后脑、低头遮脸或商品遮脸格不得为通过校验强行补画五官或把人物转正，改以头部轮廓不低于单格高度的 15% 锁定人物尺度。头顶、该朝向自然可见的五官、本格按脚本可见的当前商品部位、构图内可见手臂和本格动作实际需要的手必须清楚，不可见肢体无需补出，"
            + "不得为了保留全身、脚部、背景或原视频远景而缩小人物。即使脚本、原视频、参考图或 shotSize 写了远景、全景、全身、拉远、后退、看到脚部，也必须改写成中近景内能够完成的动作和运镜；"
            + "纯商品格采用填满主体区域的商品近景。01–09 的主体尺度必须稳定，禁止任何一格突然拉远。";
    private static final String GRID_POSE_SCALE_REQUIREMENT = "【动作底图尺度】每个 720×1280 子画面采用头顶至大腿中下段的中近景；"
            + "无脸人体的头部轮廓高度不得低于单格高度的 15%，但不得生成可识别五官。构图内自然可见的头部、躯干、手臂及本格动作实际需要的手部结构必须完整；被遮挡或出画的肢体无需补出，"
            + "不为显示膝盖以下、双脚、鞋子或原视频远景而缩小人物；纯商品格采用填满主体区域的商品近景。";
    private static final String FABRIC_AND_PRODUCT_REALISM_REQUIREMENT = "材质必须按真实物理方式受光：棉料保留细微织纹、厚薄、"
            + "缝线和受力褶皱，印花贴合布面起伏而非悬浮；纸、塑料、玻璃、金属和木材分别呈现正确的漫反射、粗糙度、"
            + "透光或反射特征。避免所有表面同样光滑、边缘融化、无来源高光和不符合重力的悬浮。"
            + "布料在任何姿态下都保留重量、柔软度、连续褶皱和真实支撑关系，不得像纸板、塑料片或硬壳。";
    private static final String GARMENT_PIXEL_STRUCTURE_REQUIREMENT = "【商品结构】参考图1可见像素是最高真值，结构摘要只用于指出细节。"
            + "复现原商品的外轮廓、领口路径、左右袖片与袖口、开口、肩带和可见接缝连接。露肩或斜领不等于无袖；"
            + "参考图1两侧各有袖片与袖口时必须保留两袖。不得镜像、换侧或改成相似款。";
    private static final String FABRIC_GRIP_PHYSICS_REQUIREMENT = "【仅限本格明确提拉布料时的重力】手指捏住、提起或掀起衣角、下摆、袖口时，接触点必须准确贴合指腹；只有捏住的位置被向上牵引，未被支撑的布料受重力向下垂落，"
            + "尤其捏住领口或肩部一角时，被捏角是全件衣服唯一最高受力点，对侧领口、对侧肩袖和衣摆必须明显低于捏点并耷拉下去，衣服重心垂直落在捏点下方；"
            + "从受力点向外形成疏密自然的放射状张力褶皱和弧形垂坠，下摆有轻微滞后、惯性与阻尼。禁止整片衣服像纸板、塑料片或硬壳一样平直抬起，"
            + "禁止无重量、无褶皱、冻结悬空或所有布面同步刚性移动。本条只约束脚本明确发生提拉的当前格，不得据此给其他格新增手、捏角或提拉动作。";
    private final AiVideoTaskMapper taskMapper;
    private final AiVideoTaskItemMapper itemMapper;
    private final AiVideoTaskAssetMapper assetMapper;
    private final AiVideoPendingImageResultMapper pendingImageResultMapper;
    private final VideoScriptMapper scriptMapper;
    private final VideoScriptShotMapper shotMapper;
    private final ModelConfigService modelConfigService;
    private final ProductFunctionAnalysisService productFunctionAnalysisService;
    private final AiVideoGenerationService videoGenerationService;
    private final ObjectMapper objectMapper;
    private final RestClient piClient;
    private final Executor imageExecutor;
    private final Executor imageCoordinatorExecutor;
    private final Set<String> activeGenerationTaskIds = ConcurrentHashMap.newKeySet();
    private final Set<String> generationContinuationTaskIds = ConcurrentHashMap.newKeySet();
    private final Path storageRoot;
    private final Path videoScriptStorageRoot;
    private final OssStorageService ossStorage;

    public AiVideoImageProcessor(AiVideoTaskMapper taskMapper, AiVideoTaskItemMapper itemMapper,
                                 AiVideoTaskAssetMapper assetMapper,
                                 AiVideoPendingImageResultMapper pendingImageResultMapper,
                                 VideoScriptMapper scriptMapper,
                                 VideoScriptShotMapper shotMapper, ModelConfigService modelConfigService,
                                 ProductFunctionAnalysisService productFunctionAnalysisService,
                                 AiVideoGenerationService videoGenerationService,
                                 ObjectMapper objectMapper,
                                  @Qualifier("aiVideoImageExecutor") Executor imageExecutor,
                                  @Qualifier("aiVideoImageCoordinatorExecutor") Executor imageCoordinatorExecutor,
                                  ObjectProvider<OssStorageService> ossStorage,
                                  @Value("${app.pi-runtime-url}") String piRuntimeUrl,
                                 @Value("${app.pi-runtime-token}") String piRuntimeToken,
                                 @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storageRoot,
                                 @Value("${app.video-script-storage:/data/video-scripts}") String videoScriptStorageRoot) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.pendingImageResultMapper = pendingImageResultMapper;
        this.scriptMapper = scriptMapper;
        this.shotMapper = shotMapper;
        this.modelConfigService = modelConfigService;
        this.productFunctionAnalysisService = productFunctionAnalysisService;
        this.videoGenerationService = videoGenerationService;
        this.objectMapper = objectMapper;
        this.imageExecutor = imageExecutor;
        this.imageCoordinatorExecutor = imageCoordinatorExecutor;
        this.ossStorage = ossStorage.getIfAvailable();
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
                .in(AiVideoTask::getStatus, List.of("READY", "FAILED", "IMAGE_READY", "VIDEO_READY"))
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

    @Scheduled(initialDelay = 3_000, fixedDelay = 5_000)
    public void recoverAutoStartPipelines() {
        Instant retryBefore = Instant.now();
        List<AiVideoTask> recoverable = taskMapper.selectList(new LambdaQueryWrapper<AiVideoTask>()
                .and(candidate -> candidate
                        .eq(AiVideoTask::isAutoStartVideoAfterImages, true)
                        .in(AiVideoTask::getStatus, List.of("IMAGE_READY", "FAILED"))
                        .or()
                        .eq(AiVideoTask::getStatus, "FAILED")
                        .or()
                        .eq(AiVideoTask::getStatus, "IMAGE_READY").isNotNull(AiVideoTask::getErrorMessage))
                .orderByDesc(AiVideoTask::getUpdatedAt)
                .last("LIMIT 100"));
        for (AiVideoTask task : recoverable) {
            try {
                List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                        .eq(AiVideoTaskItem::getTaskId, task.getId()));
                List<AiVideoTaskItem> failedItems = items.stream()
                        .filter(item -> "FAILED".equals(item.getStatus())).toList();
                boolean hasFailedItem = !failedItems.isEmpty();
                boolean hasTerminalTaskFailure = terminalTaskFailureWithoutFailedItem(
                        task.getStatus(), task.getErrorMessage(), hasFailedItem);
                boolean hasRetryableTaskFailure = "FAILED".equals(task.getStatus())
                        && !hasFailedItem && !hasTerminalTaskFailure;
                boolean hasTerminalFailure = failedItems.stream()
                        .anyMatch(item -> !retryableImageTransientFailure(item.getErrorMessage()))
                        || hasTerminalTaskFailure;
                boolean hasRetryableFailure = failedItems.stream()
                        .anyMatch(item -> retryableImageTransientFailure(item.getErrorMessage()))
                        || hasRetryableTaskFailure;
                boolean retryDelayElapsed = failedItems.stream()
                        .filter(item -> retryableImageTransientFailure(item.getErrorMessage()))
                        .map(AiVideoTaskItem::getUpdatedAt)
                        .filter(value -> value != null)
                        .anyMatch(value -> !value.isAfter(retryBefore));
                if (hasRetryableTaskFailure) {
                    Instant updatedAt = task.getUpdatedAt();
                    retryDelayElapsed = retryDelayElapsed
                            || updatedAt == null || !updatedAt.isAfter(retryBefore);
                }
                if (hasTerminalFailure && !hasRetryableFailure) {
                    if (task.isAutoStartVideoAfterImages()) {
                        task.consumeAutoStartVideo();
                        taskMapper.updateById(task);
                    }
                    continue;
                }
                if (!hasRetryableFailure && !task.isAutoStartVideoAfterImages()) continue;
                if (hasRetryableFailure && !retryDelayElapsed) continue;
                prepare(task);
                dispatchRecoveredGeneration(task.getId());
            } catch (Exception ignored) {
                // Another request may have claimed the task between select and update.
            }
        }
        List<AiVideoTask> interruptedImageTasks = taskMapper.selectList(
                new LambdaQueryWrapper<AiVideoTask>()
                        .eq(AiVideoTask::getStatus, "IMAGE_GENERATING")
                        .le(AiVideoTask::getUpdatedAt, Instant.now().minus(Duration.ofSeconds(30)))
                        .orderByAsc(AiVideoTask::getUpdatedAt)
                        .last("LIMIT 100"));
        for (AiVideoTask task : interruptedImageTasks) dispatchRecoveredGeneration(task.getId());

        List<AiVideoTask> pipelined = taskMapper.selectList(new LambdaQueryWrapper<AiVideoTask>()
                .eq(AiVideoTask::getStatus, "VIDEO_GENERATING")
                .orderByDesc(AiVideoTask::getUpdatedAt)
                .last("LIMIT 100"));
        for (AiVideoTask task : pipelined) dispatchRecoveredGeneration(task.getId());
    }

    static boolean retryableImageConcurrencyFailure(String message) {
        if (message == null || message.isBlank()) return false;
        String value = message.toLowerCase();
        return value.contains("并发限制") || value.contains("并发已满")
                || value.contains("限流") || value.contains("concurrency limit exceeded")
                || value.contains("too many concurrent") || value.contains("rate limit")
                || value.contains("too many requests") || value.contains("(429)") || value.contains("（429）");
    }

    static boolean retryableImageTransientFailure(String message) {
        if (message == null || message.isBlank()) return false;
        String value = message.toLowerCase();
        // A validator outage is fail-open inside the bounded generation method and must never
        // re-enter the scheduled image pipeline, even if its nested cause mentions 429 or policy.
        if (value.contains("服装结构复核") || value.contains("garment validation")) return false;
        if (value.startsWith("图片自动恢复等待重试：")) return true;
        if (retryableImageConcurrencyFailure(message)) return true;
        if (value.contains("模型已生成图片")
                && value.contains("结果地址已保存")
                && value.contains("下载失败")) return true;
        if (value.contains("旧图片结果地址持续不可用")
                && value.contains("重新生成")) return true;
        if (value.contains("内容策略可能误判")
                || value.contains("image generation produced no asset")
                || value.contains("该提示可能违反了我们的内容政策")
                || value.contains("该提示可能违反我们的内容政策")
                || value.contains("content policy") && value.contains("no asset")) return true;
        return value.contains("fetch failed") || value.contains("网络超时")
                || value.contains("响应超时") || value.contains("headers timeout")
                || value.contains("header timeout") || value.contains("body timeout")
                || value.contains("und_err_headers_timeout") || value.contains("und_err_body_timeout")
                || value.contains("etimedout") || value.contains("header parser received no bytes")
                || value.contains("i/o error on post request") || value.contains("unexpected end of file")
                || value.contains("connection reset");
    }

    static boolean terminalTaskFailureWithoutFailedItem(String status, String message,
                                                        boolean hasFailedItem) {
        return "FAILED".equals(status) && !hasFailedItem
                && !retryableImageTransientFailure(message);
    }

    static boolean retryablePipelineImageFailure(String status, String message) {
        if (!retryableImageTransientFailure(message)) return false;
        if ("FAILED".equals(status)) return true;
        if (!"VIDEO_FAILED".equals(status) || message == null) return false;
        String value = message.toLowerCase();
        return value.contains("图片生成失败") || value.contains("图片模型")
                || value.contains("image generation") || value.contains("image model");
    }

    private void dispatchRecoveredGeneration(String taskId) {
        imageCoordinatorExecutor.execute(() -> generate(taskId));
    }

    @Async("aiVideoImageCoordinatorExecutor")
    public void generate(String taskId) {
        if (!activeGenerationTaskIds.add(taskId)) {
            requestGenerationContinuation(taskId);
            return;
        }
        generationContinuationTaskIds.remove(taskId);
        try {
            generateClaimed(taskId);
        } finally {
            activeGenerationTaskIds.remove(taskId);
            if (generationContinuationTaskIds.remove(taskId)) {
                dispatchRecoveredGeneration(taskId);
            }
        }
    }

    private void requestGenerationContinuation(String taskId) {
        generationContinuationTaskIds.add(taskId);
        // The active owner can finish between the failed claim and this marker. In that narrow
        // window it has already missed the marker, so dispatch the continuation from here.
        if (!activeGenerationTaskIds.contains(taskId)
                && generationContinuationTaskIds.remove(taskId)) {
            dispatchRecoveredGeneration(taskId);
        }
    }

    private void generateClaimed(String taskId) {
        AiVideoTask task = taskMapper.selectById(taskId);
        boolean pipelineRecovery = task != null && "VIDEO_GENERATING".equals(task.getStatus());
        if (task == null || !("IMAGE_GENERATING".equals(task.getStatus()) || pipelineRecovery)) return;
        List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getTaskId, taskId).orderByAsc(AiVideoTaskItem::getCreatedAt));
        if (pipelineRecovery) {
            Instant retryBefore = Instant.now().minus(Duration.ofSeconds(5));
            boolean waitingForConcurrencyRetry = items.stream()
                    .filter(item -> retryablePipelineImageFailure(item.getStatus(), item.getErrorMessage()))
                    .map(AiVideoTaskItem::getUpdatedAt)
                    .filter(value -> value != null)
                    .anyMatch(value -> value.isAfter(retryBefore));
            if (waitingForConcurrencyRetry) return;
            items = items.stream()
                    .filter(item -> item.getStatus() == null
                            || retryablePipelineImageFailure(item.getStatus(), item.getErrorMessage())
                            || (!item.getStatus().startsWith("VIDEO_") && !"FAILED".equals(item.getStatus())))
                    .toList();
            if (items.isEmpty()) {
                task.consumeAutoStartVideo();
                taskMapper.updateById(task);
                videoGenerationService.refreshPipelinedTask(taskId);
                return;
            }
        }
        final List<AiVideoTaskItem> generationItems = items;
        try {
            ModelConfigService.RuntimeModelConnection model = modelConfigService.imageConnection(task.getOrganizationId())
                    .orElseThrow(() -> new IllegalStateException("图片模型连接已失效"));
            List<ShotPrompt> shots = readShots(task.getScriptJson());
            if (shots.size() != 9) throw new IllegalStateException("冻结脚本不是完整 9 个分镜");
            AtomicInteger processed = new AtomicInteger();
            AtomicInteger completedCount = new AtomicInteger();
            List<CompletableFuture<String>> futures = generationItems.stream()
                    .map(item -> CompletableFuture.supplyAsync(() -> {
                        String failure = null;
                        try {
                            // Recovery belongs to this item, not the allOf task barrier below.
                            // A stalled sibling must not delay downloading an already-paid image.
                            runWithImageRecovery(
                                    () -> generateItem(task, item, shots, model, pipelineRecovery),
                                    message -> {
                                        item.fail("图片自动恢复等待重试：" + message);
                                        itemMapper.updateById(item);
                                        log.info("Image item {} awaiting independent recovery", item.getId());
                                    },
                                    () -> {
                                        Thread.sleep(10_000);
                                        AiVideoTask latest = taskMapper.selectById(task.getId());
                                        if (latest == null || !("IMAGE_GENERATING".equals(latest.getStatus())
                                                || "VIDEO_GENERATING".equals(latest.getStatus()))) {
                                            throw new InterruptedException("图片任务已停止，取消自动恢复");
                                        }
                                    });
                            completedCount.incrementAndGet();
                            AiVideoTask latest = taskMapper.selectById(task.getId());
                            if (latest != null && latest.isAutoStartVideoAfterImages()) {
                                try {
                                    videoGenerationService.startReadyItemDuringImages(task.getId(), item.getId());
                                } catch (Exception exception) {
                                    failure = item.getName() + "：图片已完成，但自动提交视频失败：" + readable(exception);
                                }
                            }
                        } catch (Exception exception) {
                            String message = readable(exception);
                            item.fail(message);
                            itemMapper.updateById(item);
                            failure = item.getName() + "：" + message;
                        } finally {
                            int finished = processed.incrementAndGet();
                            synchronized (task) {
                                AiVideoTask current = taskMapper.selectById(task.getId());
                                if (current != null && "IMAGE_GENERATING".equals(current.getStatus())) {
                                    current.reportProgress(Math.min(99, Math.max(2,
                                                    (finished * 100) / Math.max(1, generationItems.size()))),
                                            completedCount.get());
                                    taskMapper.updateById(current);
                                }
                            }
                        }
                        return failure;
                    }, imageExecutor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            List<String> failures = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(message -> message != null && !message.isBlank())
                    .toList();
            int completed = completedCount.get();
            AiVideoTask current = taskMapper.selectById(task.getId());
            if (current != null && current.getStatus() != null && current.getStatus().startsWith("VIDEO_")) {
                boolean waitingForPersistedDownload = false;
                for (AiVideoTaskItem candidate : generationItems) {
                    AiVideoTaskItem latest = itemMapper.selectById(candidate.getId());
                    if (latest != null && "FAILED".equals(latest.getStatus())) {
                        if (hasPendingImageResult(latest.getId())) {
                            waitingForPersistedDownload = true;
                            continue;
                        }
                        String message = latest.getErrorMessage();
                        latest.videoFail("图片生成失败：" + (message == null || message.isBlank() ? "未生成有效图片" : message));
                        itemMapper.updateById(latest);
                    }
                }
                if (!waitingForPersistedDownload) current.consumeAutoStartVideo();
                taskMapper.updateById(current);
                if ("VIDEO_GENERATING".equals(current.getStatus())) {
                    videoGenerationService.refreshPipelinedTask(task.getId());
                }
                return;
            }
            if (completed == 0) {
                task.fail(failures.isEmpty() ? "图片模型没有生成有效结果" : String.join("；", failures));
            } else {
                task.complete(completed, failures.isEmpty() ? null : String.join("；", failures));
            }
            taskMapper.updateById(task);
            if (failures.isEmpty() && task.isAutoStartVideoAfterImages()) {
                task.consumeAutoStartVideo();
                taskMapper.updateById(task);
                try {
                    videoGenerationService.start(task);
                } catch (Exception exception) {
                    task.fail("图片已完成，但自动提交视频失败：" + readable(exception));
                    taskMapper.updateById(task);
                }
            }
        } catch (Exception exception) {
            task.fail(readable(exception));
            taskMapper.updateById(task);
        }
    }

    @FunctionalInterface
    interface ImageAttempt { void run() throws Exception; }

    @FunctionalInterface
    interface ImageRetryWait { void await() throws InterruptedException; }

    static void runWithImageRecovery(ImageAttempt attempt, java.util.function.Consumer<String> onRetry,
                                     ImageRetryWait retryWait) throws Exception {
        int malformedResponses = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("图片恢复已中断");
            try {
                attempt.run();
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception failure) {
                String message = readable(failure);
                boolean malformed = message.contains("服装结构识别结果缺少必填字段");
                // Incomplete model JSON may be sampled again, but never bypass the contract or
                // turn a persistent schema problem into unlimited paid model calls.
                boolean retry = malformed ? ++malformedResponses <= 2 : retryableImageException(failure);
                if (!retry) throw failure;
                onRetry.accept(message);
                try {
                    retryWait.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
    }

    private static boolean retryableImageException(Exception failure) {
        String message = readable(failure);
        if (message.contains("服装结构复核") || message.toLowerCase().contains("garment validation")) return false;
        if (retryableImageTransientFailure(message)) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response
                    && (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429)) {
                return true;
            }
        }
        return false;
    }

    private void generateItem(AiVideoTask task, AiVideoTaskItem item, List<ShotPrompt> shots,
                              ModelConfigService.RuntimeModelConnection model,
                              boolean pipelineRecovery) throws Exception {
        List<AiVideoTaskAsset> existing = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, item.getId()));
        boolean modelWear = "MODEL_WEAR".equals(task.getGenerationMode());
        boolean preserveScriptBackground = preserveScriptBackground(task);
        String primaryAssetType = modelWear ? "MODEL_IMAGE" : "PRODUCT_SCENE_IMAGE";
        AiVideoTaskAsset modelAsset = usableAsset(existing, primaryAssetType);
        AiVideoTaskAsset storyboardAsset = usableAsset(existing, "STORYBOARD_GRID");
        AiVideoTaskAsset firstFrameAsset = usableAsset(existing, "STORYBOARD_FIRST_FRAME");
        if (modelWear) {
            modelAsset = recheckLegacyFramingAsset(task, item, modelAsset, primaryAssetType);
            storyboardAsset = recheckLegacyFramingAsset(task, item, storyboardAsset, "STORYBOARD_GRID");
        }
        if (modelAsset != null) reconcileCompletedTopologyAsset(task, item, primaryAssetType);
        if (storyboardAsset != null) reconcileCompletedTopologyAsset(task, item, "STORYBOARD_GRID");
        // Older MODEL_WEAR tasks copied the static model image as the video first frame. That made
        // the video model overfit to a single holding pose and ignore the storyboard state changes.
        // Migrate those assets lazily so retries use the actual first storyboard cell.
        if (modelWear && isLegacyModelFirstFrame(firstFrameAsset)) {
            deleteGeneratedAsset(firstFrameAsset);
            firstFrameAsset = null;
        }
        AiVideoTaskAsset storyboardGuideAsset = usableAsset(existing, "STORYBOARD_GUIDE");
        if (isRawScriptFrameGuide(storyboardGuideAsset)) {
            // A raw video frame can contain the previous video's product. Passing it into the
            // image model lets those pixels compete with the current SKU even when the prompt
            // says otherwise. Rebuild a clean, text-driven action guide instead.
            deleteGeneratedAsset(storyboardGuideAsset);
            storyboardGuideAsset = null;
        }
        boolean hasVideoResult = usableAsset(existing, "VIDEO_RESULT") != null;
        boolean hasModel = modelAsset != null;
        boolean hasStoryboard = storyboardAsset != null;
        // Publish recovery before the potentially 1000-second analysis request, not after it.
        item.start();
        if (hasModel && !hasStoryboard) item.modelReady();
        itemMapper.updateById(item);
        if (shouldRunProductFunctionAnalysis(productFunctionAnalysisService.supportsAnalysis(task),
                pipelineRecovery, hasModel)) {
            // Scope the new analysis to genuinely new primary-image work. Existing/running tasks and
            // pipeline recovery retain their persisted prompts and assets unchanged.
            productFunctionAnalysisService.ensureAnalysis(task, item);
        }
        boolean productVisibleInFirstShot = modelWear || shotShowsProduct(shots.getFirst());
        boolean useScriptFrameForPrimary = useScriptFirstFrameForPrimary(modelWear, preserveScriptBackground);
        ReferenceImage scriptFirstFrame = useScriptFrameForPrimary
                ? firstScriptFrameReference(task) : null;
        ShotPrompt productAnchorShot = shots.stream().filter(this::shotShowsProduct).findFirst().orElse(shots.getLast());
        String modelPrompt = modelWear
                ? modelWearPrompt(task, item, shots.getFirst(), scriptFirstFrame != null)
                : productScenePrompt(task, item, productAnchorShot, false);
        if (!modelWear && modelAsset != null && !modelPrompt.equals(modelAsset.getPromptText())) {
            deleteGeneratedAsset(modelAsset);
            modelAsset = null;
            hasModel = false;
        }
        item.start();
        if (hasModel && !hasStoryboard) item.modelReady();
        itemMapper.updateById(item);
        if (hasModel && hasStoryboard) {
            if (firstFrameAsset == null) {
                if (modelWear) ensureStoryboardFirstFrame(task, storyboardAsset);
                else ensureProductTimelineFirstFrame(task, modelAsset, storyboardAsset, productVisibleInFirstShot);
            }
            completeImages(item, hasVideoResult);
            itemMapper.updateById(item);
            return;
        }
        ReferenceImage garment = reference(Path.of(item.getSourceFilePath()), "参考图1 商品素材", item.getSourceContentType());
        if (!hasModel) {
            List<ReferenceImage> modelReferences = useScriptFrameForPrimary && scriptFirstFrame != null
                    ? List.of(garment, scriptFirstFrame)
                    : List.of(garment);
            modelAsset = generateTopologyVerifiedAsset(task, item, model, primaryAssetType,
                    modelPrompt, modelReferences);
            item.modelReady();
            itemMapper.updateById(item);
        }
        if (!hasStoryboard) {
            if (storyboardGuideAsset == null) {
                // Always retain the source video's real action states for storyboard construction.
                // This is independent from the optional final video-reference submission switch:
                // the latter affects the video provider, while this guide only prevents a textual
                // summary from flattening front/edge/back views and changing hand contact points.
                storyboardGuideAsset = createRealFrameStoryboardGuide(task, item);
            }
            ReferenceImage generatedModel = modelWear
                    ? modelIdentityGridReference(modelAsset.getFilePath(), modelAsset.getContentType())
                    : reference(modelAsset.getFilePath(), "AI商品场景图", modelAsset.getContentType());
            ReferenceImage productIdentityGrid = productIdentityGridReference(
                    Path.of(item.getSourceFilePath()), item.getSourceContentType());
            if (modelWear) {
                boolean realFrameGuide = false;
                if (storyboardGuideAsset == null) {
                    String guidePrompt = storyboardGridPrompt(task, shots);
                    storyboardGuideAsset = generateAsset(task, item, model, "STORYBOARD_GUIDE", guidePrompt,
                            List.of(generatedModel));
                } else {
                    realFrameGuide = isScriptMotionFrameGuide(storyboardGuideAsset);
                }
                ReferenceImage storyboardGuide = reference(storyboardGuideAsset.getFilePath(),
                        "参考图3 动作场景九宫格底图", storyboardGuideAsset.getContentType());
                String identityPrompt = storyboardIdentityTransferPrompt(task, item, shots, realFrameGuide);
                storyboardAsset = generateTopologyVerifiedAsset(task, item, model, "STORYBOARD_GRID",
                        identityPrompt, List.of(garment, generatedModel, storyboardGuide, productIdentityGrid));
            } else {
                boolean realFrameGuide = isScriptMotionFrameGuide(storyboardGuideAsset);
                String productStoryboardPrompt = productStoryboardGridPrompt(task, item, shots, realFrameGuide);
                List<ReferenceImage> productReferences;
                if (realFrameGuide) {
                    ReferenceImage productMaster = reference(Path.of(item.getSourceFilePath()),
                            "参考图1 用户上传商品外观唯一母版", item.getSourceContentType());
                    ReferenceImage storyboardGuide = reference(storyboardGuideAsset.getFilePath(),
                            "参考图2 原视频真实九帧动作结构母版", storyboardGuideAsset.getContentType());
                    productReferences = List.of(productMaster, storyboardGuide, generatedModel, productIdentityGrid);
                } else {
                    productReferences = List.of(generatedModel, garment, productIdentityGrid);
                }
                storyboardAsset = generateTopologyVerifiedAsset(task, item, model, "STORYBOARD_GRID",
                        productStoryboardPrompt, productReferences);
            }
            if (modelWear) ensureStoryboardFirstFrame(task, storyboardAsset);
            else ensureProductTimelineFirstFrame(task, modelAsset, storyboardAsset, productVisibleInFirstShot);
        }
        completeImages(item, hasVideoResult);
        itemMapper.updateById(item);
    }

    AiVideoTaskAsset recheckLegacyFramingAsset(AiVideoTask task, AiVideoTaskItem item,
                                               AiVideoTaskAsset asset, String assetType) throws Exception {
        if (asset == null || (asset.getPromptText() != null
                && asset.getPromptText().contains(CLOSE_FRAMING_GATE_VERSION))) return asset;
        ProductFunctionAnalysisService.GarmentValidation validation;
        try {
            validation = productFunctionAnalysisService.validateGenerated(task, item, assetType,
                    readAsset(asset.getFilePath()), asset.getContentType());
        } catch (Exception exception) {
            log.warn("旧图片服装结构复核暂时不可用，保留现有资产继续，taskId={}, itemId={}, assetType={}: {}",
                    task.getId(), item.getId(), assetType, exception.getMessage());
            return asset;
        }
        if (!validation.valid()) {
            deleteGeneratedAsset(asset);
            return null;
        }
        String validatedPrompt = (asset.getPromptText() == null ? "" : asset.getPromptText())
                + "\n【中近景门禁版本：" + CLOSE_FRAMING_GATE_VERSION + "】";
        assetMapper.update(null, new LambdaUpdateWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getId, asset.getId())
                .set(AiVideoTaskAsset::getPromptText, validatedPrompt));
        return asset;
    }

    static boolean isLegacyModelFirstFrame(AiVideoTaskAsset asset) {
        return asset != null
                && asset.getPromptText() != null
                && asset.getPromptText().startsWith("复用 4K AI 模特图作为视频第 01 分镜");
    }

    static void completeImages(AiVideoTaskItem item, boolean hasVideoResult) {
        if (hasVideoResult) item.videoComplete();
        else item.complete();
    }

    static boolean shouldRunProductFunctionAnalysis(boolean analysisSupported, boolean pipelineRecovery,
                                                    boolean hasPrimaryAsset) {
        return analysisSupported && !pipelineRecovery && !hasPrimaryAsset;
    }

    static boolean useScriptFirstFrameForPrimary(boolean modelWear, boolean preserveScriptBackground) {
        // The primary image establishes the current SKU's product identity. Script frames may
        // contain a different product/person and therefore must only be used by the storyboard
        // stage as scene/action guides, never as primary-image model inputs.
        return false;
    }

    static boolean isRawScriptFrameGuide(AiVideoTaskAsset asset) {
        return asset != null
                && asset.getPromptText() != null
                && asset.getPromptText().startsWith("REAL_VIDEO_FRAMES_V1");
    }

    static boolean isScriptMotionFrameGuide(AiVideoTaskAsset asset) {
        return asset != null
                && asset.getPromptText() != null
                && asset.getPromptText().startsWith("SCRIPT_MOTION_FRAMES_V2");
    }

    private boolean hasUsableAsset(List<AiVideoTaskAsset> assets, String type) {
        return usableAsset(assets, type) != null;
    }

    private AiVideoTaskAsset usableAsset(List<AiVideoTaskAsset> assets, String type) {
        for (AiVideoTaskAsset asset : assets) {
            if (!type.equals(asset.getAssetType())) continue;
            try {
                if (ossStorage != null && ossStorage.isOssLocation(asset.getFilePath())
                        && ossStorage.exists(asset.getFilePath())) return asset;
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
            PiArtifact artifact = call(task, item, assetType, model, prompt, references);
            AiVideoTaskAsset stored = store(task, item, assetType, null, artifact, prompt);
            clearPendingImageResult(item.getId(), assetType, imageRequestHash(prompt, references));
            return stored;
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    private AiVideoTaskAsset generateTopologyVerifiedAsset(AiVideoTask task, AiVideoTaskItem item,
                                                             ModelConfigService.RuntimeModelConnection model,
                                                             String assetType, String basePrompt,
                                                             List<ReferenceImage> references) {
        try {
            TopologyCandidate candidate = loadTopologyCandidate(task, item, assetType);
            BestTopologyCandidate best = loadBestTopologyCandidate(task, item, assetType);
            TopologyRetryState state = loadTopologyRetryState(task, item, assetType);
            AiVideoPendingImageResult pending = pendingImageResult(item.getId(), assetType);
            if (state == null) {
                boolean legacyRecovery = candidate != null || best != null || pending != null;
                String recoveredPrompt = candidate != null ? candidate.prompt()
                        : pending != null ? basePrompt : "";
                state = TopologyRetryState.initial(legacyRecovery, recoveredPrompt);
                saveTopologyRetryState(task, item, assetType, state);
            } else if (state.legacyRecovery() && state.reservedPrompt().isBlank()
                    && candidate == null && pending != null) {
                state = state.recoverPending(basePrompt);
                saveTopologyRetryState(task, item, assetType, state);
            }

            boolean reservedInThisInvocation = false;
            while (true) {
                if (candidate == null) {
                    if (state.reservedPrompt().isBlank()) {
                        if (!state.canGenerate()) {
                            if (best == null) {
                                throw new IllegalStateException("服装结构复核重抽预算已用完且没有可恢复候选图");
                            }
                            logExhaustedTopologyBudget(assetType, item, state, best.violationCount());
                            return storeFinalTopologyCandidate(task, item, assetType, best.candidate());
                        }
                        // Any pending row left after a completed rejection belongs to the old prompt.
                        // Remove it before reserving the next bounded redraw.
                        clearPendingImageResult(item.getId(), assetType);
                        int nextAttempt = state.attemptsReserved() + 1;
                        String prompt = nextAttempt == 1 || state.previousValidation() == null
                                ? basePrompt
                                : topologyCorrectionPrompt(basePrompt, state.previousValidation());
                        state = state.reserve(prompt);
                        saveTopologyRetryState(task, item, assetType, state);
                        reservedInThisInvocation = true;
                    }

                    pending = pendingImageResult(item.getId(), assetType);
                    String expectedPromptHash = imageRequestHash(state.reservedPrompt(), references);
                    if (pending != null && !state.acceptsPendingHash(
                            pending.getPromptHash(), expectedPromptHash)) {
                        pendingImageResultMapper.deleteById(pending.getId());
                        pending = null;
                    }
                    if (!state.mayProcessReservedAttempt(
                            reservedInThisInvocation, pending != null)) {
                        // The process stopped after reserving this slot but before persisting a
                        // recoverable result. Count the slot as consumed; never replay the POST.
                        state = state.abandonReservation();
                        saveTopologyRetryState(task, item, assetType, state);
                        if (best != null) {
                            if (!state.canGenerate()) {
                                logExhaustedTopologyBudget(assetType, item, state, best.violationCount());
                                return storeFinalTopologyCandidate(task, item, assetType, best.candidate());
                            }
                            continue;
                        }
                        if (state.canGenerate()) continue;
                        throw new IllegalStateException("服装候选图已不可恢复；为避免超过四次生成上限，已停止继续重抽");
                    }
                    PiArtifact artifact;
                    try {
                        artifact = pending == null
                                ? call(task, item, assetType, model, state.reservedPrompt(), references)
                                : downloadPendingImage(model, state.reservedPrompt(), pending);
                    } catch (Exception generationFailure) {
                        // A failed request with no saved provider result produced no candidate and
                        // therefore does not consume one of the four candidate slots. A saved URL
                        // keeps the reservation so recovery only retries its download.
                        boolean unavailableSavedResult = generationFailure
                                instanceof UnavailablePendingImageResultException;
                        boolean hasPendingAfterFailure = pendingImageResult(item.getId(), assetType) != null;
                        TopologyRetryState reconciled = reconcileTopologyGenerationFailure(
                                state, unavailableSavedResult, pending != null,
                                hasPendingAfterFailure, reservedInThisInvocation);
                        if (!reconciled.equals(state)) {
                            state = reconciled;
                            saveTopologyRetryState(task, item, assetType, state);
                        }
                        if (unavailableSavedResult) {
                            pending = null;
                            reservedInThisInvocation = false;
                            if (!state.canGenerate()) {
                                if (best != null) {
                                    logExhaustedTopologyBudget(assetType, item, state, best.violationCount());
                                    return storeFinalTopologyCandidate(task, item, assetType, best.candidate());
                                }
                                throw new IllegalStateException(
                                        "图片结果地址已失效且服装结构重抽预算已用完，未取得可用候选图",
                                        generationFailure);
                            }
                            continue;
                        }
                        throw generationFailure;
                    }
                    byte[] candidateBytes = Base64.getDecoder().decode(artifact.dataBase64());
                    validateTopologyCandidateSize(candidateBytes);
                    candidate = TopologyCandidate.fromArtifact(
                            artifact, state.reservedPrompt(), candidateBytes);
                    saveTopologyCandidate(task, item, assetType, candidate);
                    reservedInThisInvocation = false;
                }

                PiArtifact artifact = candidate.toArtifact();
                byte[] candidateBytes = candidate.bytes();
                validateTopologyCandidateSize(candidateBytes);
                ProductFunctionAnalysisService.GarmentValidation validation;
                try {
                    validation = productFunctionAnalysisService.validateGenerated(task, item, assetType,
                            candidateBytes, artifact.mimeType());
                } catch (Exception validationFailure) {
                    TopologyCandidate fallback = best == null ? candidate : best.candidate();
                    log.warn("Garment validation unavailable for {} on item {}; storing the best known "
                                    + "candidate and continuing without another redraw: {}",
                            assetType, item.getId(), validationFailure.getMessage());
                    return storeFinalTopologyCandidate(task, item, assetType, fallback);
                }
                if (validation.valid()) {
                    return storeFinalTopologyCandidate(task, item, assetType, candidate);
                }

                int violationCount = topologyViolationCount(validation);
                if (best == null || preferNewTopologyCandidate(best.violationCount(), violationCount)) {
                    best = new BestTopologyCandidate(candidate, violationCount, state.attemptsReserved());
                    saveBestTopologyCandidate(task, item, assetType, best);
                }
                state = state.reject(validation);
                saveTopologyRetryState(task, item, assetType, state);
                clearPendingImageResult(item.getId(), assetType);
                discardTopologyCandidate(task, item, assetType);
                candidate = null;
                reservedInThisInvocation = false;

                if (!state.canGenerate()) {
                    logExhaustedTopologyBudget(assetType, item, state, best.violationCount());
                    return storeFinalTopologyCandidate(task, item, assetType, best.candidate());
                }
            }
        } catch (Exception exception) {
            throw exception instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private void validateTopologyCandidateSize(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 64L * 1024 * 1024) {
            throw new IllegalStateException("生成图片大小无效");
        }
    }

    private AiVideoTaskAsset storeFinalTopologyCandidate(AiVideoTask task, AiVideoTaskItem item,
                                                          String assetType,
                                                          TopologyCandidate candidate) throws Exception {
        AiVideoTaskAsset stored = store(task, item, assetType, null,
                candidate.toArtifact(), candidate.prompt());
        clearPendingImageResult(item.getId(), assetType);
        discardTopologyRetryState(task, item, assetType);
        return stored;
    }

    private void logExhaustedTopologyBudget(String assetType, AiVideoTaskItem item,
                                             TopologyRetryState state, int bestViolationCount) {
        List<String> finalViolations = state.previousValidation() == null
                || state.previousValidation().violations() == null
                ? List.of() : state.previousValidation().violations();
        log.warn("Garment validation still rejected {} for item {} after {} redraws; "
                        + "storing the best candidate with {} violations and continuing. Last violations: {}",
                assetType, item.getId(), MAX_TOPOLOGY_REDRAWS, bestViolationCount,
                String.join("; ", finalViolations));
    }

    void saveTopologyCandidate(AiVideoTask task, AiVideoTaskItem item, String assetType,
                               TopologyCandidate candidate) throws Exception {
        Path dataPath = topologyCandidatePath(task, item, assetType, ".bin");
        Path metadataPath = topologyCandidatePath(task, item, assetType, ".json");
        Files.createDirectories(dataPath.getParent());
        writeAtomically(dataPath, candidate.bytes());
        writeAtomically(metadataPath, objectMapper.writeValueAsBytes(topologyCandidateMetadata(candidate)));
    }

    TopologyCandidate loadTopologyCandidate(AiVideoTask task, AiVideoTaskItem item, String assetType) {
        Path dataPath = topologyCandidatePath(task, item, assetType, ".bin");
        Path metadataPath = topologyCandidatePath(task, item, assetType, ".json");
        if (!Files.isRegularFile(dataPath) || !Files.isRegularFile(metadataPath)) return null;
        try {
            return readTopologyCandidate(dataPath, metadataPath);
        } catch (Exception exception) {
            discardTopologyCandidate(task, item, assetType);
            return null;
        }
    }

    void discardTopologyCandidate(AiVideoTask task, AiVideoTaskItem item, String assetType) {
        deleteTopologyFiles(task, item, assetType, ".bin", ".json");
    }

    void saveBestTopologyCandidate(AiVideoTask task, AiVideoTaskItem item, String assetType,
                                   BestTopologyCandidate best) throws Exception {
        String dataSuffix = bestTopologyDataSuffix(best.attemptNumber());
        Path dataPath = topologyCandidatePath(task, item, assetType, dataSuffix);
        Path metadataPath = topologyCandidatePath(task, item, assetType, ".best.json");
        Files.createDirectories(dataPath.getParent());
        writeAtomically(dataPath, best.candidate().bytes());
        Map<String, Object> metadata = topologyCandidateMetadata(best.candidate());
        metadata.put("violationCount", best.violationCount());
        metadata.put("attemptNumber", best.attemptNumber());
        metadata.put("sizeBytes", best.candidate().bytes().length);
        metadata.put("sha256", sha256(best.candidate().bytes()));
        writeAtomically(metadataPath, objectMapper.writeValueAsBytes(metadata));
    }

    BestTopologyCandidate loadBestTopologyCandidate(AiVideoTask task, AiVideoTaskItem item,
                                                     String assetType) {
        Path metadataPath = topologyCandidatePath(task, item, assetType, ".best.json");
        if (!Files.isRegularFile(metadataPath)) return null;
        try {
            Map<String, Object> metadata = objectMapper.readValue(
                    metadataPath.toFile(), new TypeReference<>() {});
            int attemptNumber = number(metadata.get("attemptNumber"), 0);
            Path dataPath = topologyCandidatePath(task, item, assetType,
                    attemptNumber > 0 ? bestTopologyDataSuffix(attemptNumber) : ".best.bin");
            TopologyCandidate candidate = readTopologyCandidate(dataPath, metadata);
            int expectedSize = number(metadata.get("sizeBytes"), candidate.bytes().length);
            if (expectedSize != candidate.bytes().length) {
                throw new IllegalStateException("历史最佳候选图大小不一致");
            }
            String expectedHash = nullableText(metadata.get("sha256"));
            if (!expectedHash.isBlank() && !expectedHash.equals(sha256(candidate.bytes()))) {
                throw new IllegalStateException("历史最佳候选图校验和不一致");
            }
            int violationCount = Math.max(1, number(metadata.get("violationCount"), Integer.MAX_VALUE));
            return new BestTopologyCandidate(candidate, violationCount,
                    attemptNumber > 0 ? attemptNumber : MAX_TOPOLOGY_GENERATIONS);
        } catch (Exception exception) {
            deleteTopologyFiles(task, item, assetType, ".best.json");
            return null;
        }
    }

    private String bestTopologyDataSuffix(int attemptNumber) {
        if (attemptNumber < 1 || attemptNumber > MAX_TOPOLOGY_GENERATIONS) {
            throw new IllegalStateException("历史最佳候选图轮次无效");
        }
        return ".best-" + attemptNumber + ".bin";
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("候选图校验和计算失败", exception);
        }
    }

    void saveTopologyRetryState(AiVideoTask task, AiVideoTaskItem item, String assetType,
                                TopologyRetryState state) throws Exception {
        Path statePath = topologyCandidatePath(task, item, assetType, ".state.json");
        Files.createDirectories(statePath.getParent());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attemptsReserved", state.attemptsReserved());
        metadata.put("reservedPrompt", state.reservedPrompt());
        metadata.put("legacyRecovery", state.legacyRecovery());
        if (state.previousValidation() != null) {
            ProductFunctionAnalysisService.GarmentValidation validation = state.previousValidation();
            Map<String, Object> previous = new LinkedHashMap<>();
            previous.put("valid", validation.valid());
            previous.put("correctionPrompt", validation.correctionPrompt());
            previous.put("violations", validation.violations() == null ? List.of() : validation.violations());
            previous.put("confidence", validation.confidence());
            previous.put("violationCount", validation.violationCount());
            metadata.put("previousValidation", previous);
        }
        writeAtomically(statePath, objectMapper.writeValueAsBytes(metadata));
    }

    TopologyRetryState loadTopologyRetryState(AiVideoTask task, AiVideoTaskItem item,
                                               String assetType) {
        Path statePath = topologyCandidatePath(task, item, assetType, ".state.json");
        if (!Files.isRegularFile(statePath)) return null;
        try {
            Map<String, Object> metadata = objectMapper.readValue(
                    statePath.toFile(), new TypeReference<>() {});
            int attempts = number(metadata.get("attemptsReserved"), 0);
            String reservedPrompt = nullableText(metadata.get("reservedPrompt"));
            boolean legacyRecovery = Boolean.TRUE.equals(metadata.get("legacyRecovery"));
            ProductFunctionAnalysisService.GarmentValidation previous =
                    readTopologyValidation(metadata.get("previousValidation"));
            return new TopologyRetryState(attempts, reservedPrompt, previous, legacyRecovery);
        } catch (Exception exception) {
            log.warn("服装结构重抽状态损坏，按四次预算已用完保守恢复，taskId={}, itemId={}, assetType={}: {}",
                    task.getId(), item.getId(), assetType, exception.getMessage());
            return TopologyRetryState.initial(true, "");
        }
    }

    private ProductFunctionAnalysisService.GarmentValidation readTopologyValidation(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) return null;
        List<String> violations = value.get("violations") instanceof List<?> items
                ? items.stream().map(this::nullableText).filter(text -> !text.isBlank()).limit(20).toList()
                : List.of();
        double confidence;
        try {
            confidence = Double.parseDouble(String.valueOf(value.get("confidence")));
        } catch (Exception ignored) {
            confidence = 0.0;
        }
        return new ProductFunctionAnalysisService.GarmentValidation(
                Boolean.TRUE.equals(value.get("valid")), nullableText(value.get("correctionPrompt")),
                violations, confidence, number(value.get("violationCount"),
                violations.isEmpty() ? Integer.MAX_VALUE : violations.size()));
    }

    private Map<String, Object> topologyCandidateMetadata(TopologyCandidate candidate) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mimeType", candidate.mimeType());
        metadata.put("modelId", candidate.modelId());
        metadata.put("mode", candidate.mode());
        metadata.put("prompt", candidate.prompt());
        metadata.put("revisedPrompt", candidate.revisedPrompt());
        metadata.put("sizeBytes", candidate.bytes().length);
        metadata.put("sha256", sha256(candidate.bytes()));
        return metadata;
    }

    private TopologyCandidate readTopologyCandidate(Path dataPath, Path metadataPath) throws Exception {
        Map<String, Object> metadata = objectMapper.readValue(
                metadataPath.toFile(), new TypeReference<>() {});
        return readTopologyCandidate(dataPath, metadata);
    }

    private TopologyCandidate readTopologyCandidate(Path dataPath,
                                                    Map<String, Object> metadata) throws Exception {
        byte[] bytes = Files.readAllBytes(dataPath);
        validateTopologyCandidateSize(bytes);
        int expectedSize = number(metadata.get("sizeBytes"), bytes.length);
        String expectedHash = nullableText(metadata.get("sha256"));
        if (expectedSize != bytes.length
                || !expectedHash.isBlank() && !expectedHash.equals(sha256(bytes))) {
            throw new IllegalStateException("私有候选图完整性校验失败");
        }
        String mimeType = nullableText(metadata.get("mimeType"));
        String prompt = nullableText(metadata.get("prompt"));
        if (!Set.of("image/jpeg", "image/png", "image/webp").contains(mimeType.toLowerCase())
                || prompt.isBlank()) {
            throw new IllegalStateException("私有候选图元数据无效");
        }
        return new TopologyCandidate(mimeType, bytes, nullableText(metadata.get("modelId")),
                nullableText(metadata.get("mode")), prompt,
                nullableText(metadata.get("revisedPrompt")));
    }

    private Path topologyCandidatePath(AiVideoTask task, AiVideoTaskItem item,
                                       String assetType, String suffix) {
        String safeAssetType = assetType == null ? "IMAGE"
                : assetType.replaceAll("[^A-Za-z0-9_-]", "_");
        Path directory = storageRoot.resolve(task.getOrganizationId()).resolve(task.getId())
                .resolve(item.getId()).resolve("private-validation").normalize();
        if (!directory.startsWith(storageRoot)) throw new IllegalStateException("候选图目录不安全");
        return directory.resolve(safeAssetType.toLowerCase() + suffix).normalize();
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.write(temporary, content);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void discardTopologyRetryState(AiVideoTask task, AiVideoTaskItem item,
                                            String assetType) {
        deleteTopologyFiles(task, item, assetType,
                ".bin", ".json", ".best.bin", ".best-1.bin", ".best-2.bin",
                ".best-3.bin", ".best-4.bin", ".best.json", ".state.json");
    }

    private void reconcileCompletedTopologyAsset(AiVideoTask task, AiVideoTaskItem item,
                                                  String assetType) {
        try {
            clearPendingImageResult(item.getId(), assetType);
            discardTopologyRetryState(task, item, assetType);
        } catch (Exception exception) {
            log.warn("已完成图片的服装重抽临时状态清理失败，taskId={}, itemId={}, assetType={}: {}",
                    task.getId(), item.getId(), assetType, exception.getMessage());
        }
    }

    public void resetTopologyRetryState(AiVideoTask task, AiVideoTaskItem item,
                                        List<String> assetTypes) {
        if (task == null || item == null || assetTypes == null) return;
        for (String assetType : assetTypes) {
            clearPendingImageResult(item.getId(), assetType);
            discardTopologyRetryState(task, item, assetType);
        }
    }

    private void deleteTopologyFiles(AiVideoTask task, AiVideoTaskItem item,
                                     String assetType, String... suffixes) {
        for (String suffix : suffixes) {
            try {
                Files.deleteIfExists(topologyCandidatePath(task, item, assetType, suffix));
                Files.deleteIfExists(topologyCandidatePath(task, item, assetType, suffix + ".part"));
            } catch (IOException ignored) {
                // The final database asset remains authoritative; stale private files are never served.
            }
        }
    }

    static String topologyCorrectionPrompt(String basePrompt,
                                           ProductFunctionAnalysisService.GarmentValidation validation) {
        List<String> violations = validation == null || validation.violations() == null
                ? List.of() : validation.violations();
        String correction = validation == null || validation.correctionPrompt() == null
                ? "" : validation.correctionPrompt().trim();
        StringBuilder prompt = new StringBuilder(basePrompt).append("\n\n")
                .append("【定向纠错重绘】上一候选图未通过复核，请完整重生成一张图片。")
                .append("优先修正下面的失败项；其他内容继续遵循原提示并从参考图重建，不复制上一候选，也不假设能够只改局部。")
                .append("不要重新分类，直接按原提示指定的用户上传商品母版逐像素核对：分别清点画面左侧和右侧的袖筒、袖口、袖片，")
                .append("并核对领口开口路径及每片布料的缝线连接。斜肩或露肩只代表领口路径不对称，不等于一侧无袖；")
                .append("参考图两侧都有袖片和袖口证据时，生成结果必须保留两只袖子。")
                .append("可见范围服从原提示的构图要求；不得为凑数量新增或复制肢体，每只可见手只能连接一条自然手臂。")
                .append("如输出九宫格，仍须生成一张完整九宫格图片，并让每个商品出镜格保持同一商品结构。\n")
                .append("复核失败项：")
                .append(violations.isEmpty() ? "商品结构或标识未与参考图一致" : String.join("；", violations));
        if (!correction.isBlank()) {
            prompt.append("\n复核模型纠正要求（仅在与商品母版可见像素和原提示不冲突时执行）：")
                    .append(correction);
        }
        return prompt.toString();
    }

    static TopologyRetryState reconcileTopologyGenerationFailure(
            TopologyRetryState state,
            boolean unavailableSavedResult,
            boolean hadPendingBeforeCall,
            boolean hasPendingAfterFailure,
            boolean reservedInThisInvocation) {
        if (unavailableSavedResult) return state.abandonReservation();
        if (!hadPendingBeforeCall && !hasPendingAfterFailure && reservedInThisInvocation) {
            return state.releaseReservation();
        }
        return state;
    }

    static int topologyViolationCount(ProductFunctionAnalysisService.GarmentValidation validation) {
        if (validation == null || validation.valid()) return 0;
        List<String> violations = validation.violations();
        int detailedCount = violations == null ? 0 : violations.size();
        if (detailedCount == 0 && validation.violationCount() <= 0) return Integer.MAX_VALUE;
        return Math.max(validation.violationCount(), detailedCount);
    }

    static boolean preferNewTopologyCandidate(int currentBestViolationCount, int newViolationCount) {
        return newViolationCount <= currentBestViolationCount;
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
                SourceFramePoint sourcePoint = sourceFramePoint(shot);
                double start = shot.getStartSecond() == null ? -1.0 : shot.getStartSecond().doubleValue();
                double end = shot.getEndSecond() == null ? -1.0 : shot.getEndSecond().doubleValue();
                double midpoint = start >= 0.0 && end >= start ? (start + end) / 2.0 : -1.0;
                int frameIndex;
                if (sourcePoint != null) {
                    frameIndex = (int) Math.round((sourcePoint.timestampSeconds() / sourcePoint.durationSeconds())
                            * (frames.size() - 1));
                } else {
                    frameIndex = timelineEnd > 0.0 && midpoint >= 0.0
                            ? (int) Math.round((midpoint / timelineEnd) * (frames.size() - 1))
                            : (int) Math.round(index * (frames.size() - 1.0) / 8.0);
                }
                selected.add(frames.get(Math.max(0, Math.min(frames.size() - 1, frameIndex))));
            }
            BufferedImage guide = composePortraitStoryboard(selected);
            byte[] content = png(guide);
            StoredMedia stored = storeGenerated(task, item, content, "image/png", "storyboard-guide.png");
            String marker = "SCRIPT_MOTION_FRAMES_V2｜原视频九个动作状态母版；只继承动作、朝向、接触点、机位与背景，不继承旧商品外观";
            AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(), item.getId(),
                    "STORYBOARD_GUIDE", null, stored.location(), "image/png", stored.size(), marker, "ffmpeg");
            assetMapper.insert(asset);
            return asset;
        } catch (Exception ignored) {
            // Old/manual scripts or missing source frames safely fall back to the generated guide path.
            return null;
        }
    }

    private SourceFramePoint sourceFramePoint(VideoScriptShot shot) {
        if (shot.getImagePromptJson() == null || shot.getImagePromptJson().isBlank()) return null;
        try {
            Map<?, ?> prompt = objectMapper.readValue(shot.getImagePromptJson(), Map.class);
            double timestamp = number(prompt.get("sourceTimestampSeconds"));
            double duration = number(prompt.get("sourceDurationSeconds"));
            if (!Double.isFinite(timestamp) || !Double.isFinite(duration)
                    || timestamp < 0.0 || duration <= 0.0 || timestamp > duration) return null;
            return new SourceFramePoint(timestamp, duration);
        } catch (Exception ignored) {
            return null;
        }
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return Double.NaN;
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
            if (assetReadable(existing)) return existing;
            assetMapper.deleteById(existing.getId());
        }
        try {
            if (!assetReadable(modelAsset)) throw new IllegalStateException("AI 模特图不存在，无法复用为首帧");
            AiVideoTaskItem item = itemMapper.selectById(modelAsset.getItemId());
            if (item == null) throw new IllegalStateException("首帧所属商品不存在");
            String fileName = "storyboard-first-frame." + extension(modelAsset.getContentType());
            StoredMedia stored = storeGenerated(task, item, readAsset(modelAsset.getFilePath()),
                    modelAsset.getContentType(), fileName);
            AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(),
                    modelAsset.getItemId(), "STORYBOARD_FIRST_FRAME", 1, stored.location(),
                    modelAsset.getContentType(), stored.size(),
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
        } catch (Exception exception) {
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
            if (assetReadable(existing)) return existing;
            assetMapper.deleteById(existing.getId());
        }
        try {
            if (!assetReadable(productSceneAsset)) throw new IllegalStateException("AI 商品场景图不存在，无法复用为首帧");
            AiVideoTaskItem item = itemMapper.selectById(productSceneAsset.getItemId());
            if (item == null) throw new IllegalStateException("首帧所属商品不存在");
            String fileName = "storyboard-first-frame." + extension(productSceneAsset.getContentType());
            StoredMedia stored = storeGenerated(task, item, readAsset(productSceneAsset.getFilePath()),
                    productSceneAsset.getContentType(), fileName);
            AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(),
                    productSceneAsset.getItemId(), "STORYBOARD_FIRST_FRAME", 1, stored.location(),
                    productSceneAsset.getContentType(), stored.size(),
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
        } catch (Exception exception) {
            throw new IllegalStateException("复用 AI 商品场景图作为首帧失败", exception);
        }
    }

    public AiVideoTaskAsset ensureProductTimelineFirstFrame(AiVideoTask task, AiVideoTaskAsset productSceneAsset,
                                                             AiVideoTaskAsset storyboardAsset) {
        try {
            List<ShotPrompt> shots = readShots(task.getScriptJson());
            boolean productVisible = shots.isEmpty() || shotShowsProduct(shots.getFirst());
            return ensureProductTimelineFirstFrame(task, productSceneAsset, storyboardAsset, productVisible);
        } catch (Exception ignored) {
            return ensureProductFirstFrame(task, productSceneAsset);
        }
    }

    private AiVideoTaskAsset ensureProductTimelineFirstFrame(AiVideoTask task, AiVideoTaskAsset productSceneAsset,
                                                              AiVideoTaskAsset storyboardAsset,
                                                              boolean productVisibleInFirstShot) {
        if (!productVisibleInFirstShot && storyboardAsset != null) {
            return ensureStoryboardFirstFrame(task, storyboardAsset);
        }
        return ensureProductFirstFrame(task, productSceneAsset);
    }

    private void deleteGeneratedAsset(AiVideoTaskAsset asset) {
        String location = asset.getFilePath();
        if (location != null && !location.isBlank()) {
            if (ossStorage != null && ossStorage.isOssLocation(location)) {
                ossStorage.delete(location);
            } else {
                try {
                    Path path = Path.of(location).toAbsolutePath().normalize();
                    if (path.startsWith(storageRoot)) Files.deleteIfExists(path);
                } catch (IOException ignored) { }
            }
        }
        assetMapper.deleteById(asset.getId());
    }

    public AiVideoTaskAsset ensureStoryboardFirstFrame(AiVideoTask task, AiVideoTaskAsset storyboardAsset) {
        if (storyboardAsset == null || !"STORYBOARD_GRID".equals(storyboardAsset.getAssetType())) return null;
        AiVideoTaskAsset existing = assetMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, storyboardAsset.getItemId())
                .eq(AiVideoTaskAsset::getAssetType, "STORYBOARD_FIRST_FRAME")
                .last("LIMIT 1"));
        if (existing != null) {
            if (assetReadable(existing)) return existing;
            assetMapper.deleteById(existing.getId());
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(readAsset(storyboardAsset.getFilePath())));
            if (source == null) throw new IllegalStateException("最终九宫格文件不存在，无法裁切首帧");
            BufferedImage firstFrame = cropFirstStoryboardCell(source);
            byte[] content = png(firstFrame);
            AiVideoTaskItem item = itemMapper.selectById(storyboardAsset.getItemId());
            if (item == null) throw new IllegalStateException("首帧所属商品不存在");
            StoredMedia stored = storeGenerated(task, item, content, "image/png", "storyboard-first-frame.png");
            AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(),
                    storyboardAsset.getItemId(), "STORYBOARD_FIRST_FRAME", 1, stored.location(), "image/png",
                    stored.size(), "从最终 4K 九宫格第 01 格自动裁切", storyboardAsset.getModelId());
            try {
                assetMapper.insert(asset);
                return asset;
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                return assetMapper.selectOne(new LambdaQueryWrapper<AiVideoTaskAsset>()
                        .eq(AiVideoTaskAsset::getItemId, storyboardAsset.getItemId())
                        .eq(AiVideoTaskAsset::getAssetType, "STORYBOARD_FIRST_FRAME")
                        .last("LIMIT 1"));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("九宫格首帧裁切失败：" + exception.getMessage(), exception);
        }
    }

    static BufferedImage cropFirstStoryboardCell(BufferedImage source) {
        if (source == null || source.getWidth() < 3 || source.getHeight() < 3) {
            throw new IllegalStateException("最终九宫格图片无法读取");
        }
        int width = source.getWidth() / 3;
        int height = source.getHeight() / 3;
        // Image models occasionally place a grid divider slightly before the mathematical third.
        // Keep the top-left origin, trim the trailing edges equally, then scale back to the
        // expected cell size so the video model never receives pixels from an adjacent cell.
        int sourceWidth = Math.max(1, (int) Math.floor(width * STORYBOARD_FIRST_CELL_SAFE_SCALE));
        int sourceHeight = Math.max(1, (int) Math.floor(height * STORYBOARD_FIRST_CELL_SAFE_SCALE));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height,
                    0, 0, sourceWidth, sourceHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }


    private PiArtifact call(AiVideoTask task, AiVideoTaskItem item, String assetType,
                            ModelConfigService.RuntimeModelConnection model, String prompt,
                            List<ReferenceImage> references) {
        String promptHash = imageRequestHash(prompt, references);
        AiVideoPendingImageResult pending = pendingImageResult(item.getId(), assetType);
        if (pending != null && !promptHash.equals(pending.getPromptHash())) {
            pendingImageResultMapper.deleteById(pending.getId());
            pending = null;
        }
        if (pending != null) return downloadPendingImage(model, prompt, pending);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", Map.of("baseUrl", model.baseUrl(), "modelId", model.modelId(),
                "apiFormat", model.apiFormat(), "apiKey", model.apiKey()));
        body.put("prompt", prompt);
        body.put("size", PORTRAIT_4K_SIZE);
        body.put("inputFidelity", references.isEmpty() ? "" : "high");
        body.put("references", references);
        PiImageResponse response = piClient.post().uri("/v1/images/generate")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(PiImageResponse.class);
        if (response != null && response.artifact() != null && response.artifact().dataBase64() != null) {
            return response.artifact();
        }
        PiImageResult result = response == null ? null : response.result();
        if (result == null || result.url() == null || result.url().isBlank()) {
            throw new IllegalStateException("Pi Agent 图片工具没有返回图片");
        }
        pending = new AiVideoPendingImageResult(task.getOrganizationId(), task.getId(), item.getId(), assetType,
                promptHash, result.url(), result.mimeType(), result.modelId(), result.mode(), result.revisedPrompt());
        pendingImageResultMapper.insert(pending);
        return downloadPendingImage(model, prompt, pending);
    }

    private PiArtifact downloadPendingImage(ModelConfigService.RuntimeModelConnection model, String prompt,
                                            AiVideoPendingImageResult pending) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", Map.of("baseUrl", model.baseUrl(), "modelId", model.modelId(),
                "apiFormat", model.apiFormat(), "apiKey", model.apiKey()));
        body.put("resultUrl", pending.getResultUrl());
        body.put("mimeType", pending.getResultContentType());
        body.put("modelId", pending.getModelId());
        body.put("mode", pending.getMode());
        body.put("prompt", prompt);
        body.put("revisedPrompt", pending.getRevisedPrompt());
        try {
            PiImageResponse response = piClient.post().uri("/v1/images/download")
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(PiImageResponse.class);
            if (response == null || response.artifact() == null || response.artifact().dataBase64() == null) {
                throw new IllegalStateException("结果图片下载接口没有返回图片");
            }
            return response.artifact();
        } catch (Exception exception) {
            pending.downloadFailed(pendingDownloadFailure(exception));
            pendingImageResultMapper.updateById(pending);
            if (replaceUnavailablePendingImage(pending)) {
                pendingImageResultMapper.deleteById(pending.getId());
                throw new UnavailablePendingImageResultException(
                        "旧图片结果地址持续不可用，已放弃旧地址并将重新生成该图片", exception);
            }
            throw new IllegalStateException("模型已生成图片，结果地址已保存；下载失败，系统将继续重试同一地址", exception);
        }
    }

    static boolean replaceUnavailablePendingImage(AiVideoPendingImageResult pending) {
        if (pending == null || pending.getLastError() == null) return false;
        String message = pending.getLastError().toLowerCase();
        if (message.contains("result_download_http_404") || message.contains("result_download_http_410")
                || message.contains("(404)") || message.contains("（404）")
                || message.contains("(410)") || message.contains("（410）")) {
            return pending.getDownloadAttempts() >= 1;
        }
        if (message.contains("result_download_http_401") || message.contains("result_download_http_403")
                || message.contains("(401)") || message.contains("（401）")
                || message.contains("(403)") || message.contains("（403）")) {
            return pending.getDownloadAttempts() >= 2;
        }
        if (message.contains("result_download_http_502") || message.contains("result_download_http_503")
                || message.contains("result_download_http_504")
                || message.contains("(502)") || message.contains("（502）")
                || message.contains("(503)") || message.contains("（503）")
                || message.contains("(504)") || message.contains("（504）")) {
            return false;
        }
        if (message.contains("result_download_timeout")
                || message.contains("result_download_network_error")) return false;
        return false;
    }

    private String pendingDownloadFailure(Exception exception) {
        if (exception instanceof RestClientResponseException responseException) {
            try {
                Map<String, Object> payload = objectMapper.readValue(responseException.getResponseBodyAsString(),
                        new TypeReference<Map<String, Object>>() {});
                String failureCode = nullableText(payload.get("failureCode"));
                String message = nullableText(payload.get("message"));
                Object upstreamStatus = payload.get("upstreamStatus");
                if (!failureCode.isBlank()) {
                    return failureCode + (upstreamStatus == null ? "" : " [upstream=" + upstreamStatus + "]")
                            + (message.isBlank() ? "" : ": " + message);
                }
            } catch (Exception ignored) {
                // Fall through to Spring's transport message if an older Pi runtime returned no JSON body.
            }
        }
        return readable(exception);
    }

    private AiVideoPendingImageResult pendingImageResult(String itemId, String assetType) {
        return pendingImageResultMapper.selectOne(new LambdaQueryWrapper<AiVideoPendingImageResult>()
                .eq(AiVideoPendingImageResult::getItemId, itemId)
                .eq(AiVideoPendingImageResult::getAssetType, assetType)
                .last("LIMIT 1"));
    }

    private boolean hasPendingImageResult(String itemId) {
        return pendingImageResultMapper.selectCount(new LambdaQueryWrapper<AiVideoPendingImageResult>()
                .eq(AiVideoPendingImageResult::getItemId, itemId)) > 0;
    }

    private void clearPendingImageResult(String itemId, String assetType, String promptHash) {
        pendingImageResultMapper.delete(new LambdaQueryWrapper<AiVideoPendingImageResult>()
                .eq(AiVideoPendingImageResult::getItemId, itemId)
                .eq(AiVideoPendingImageResult::getAssetType, assetType)
                .eq(AiVideoPendingImageResult::getPromptHash, promptHash));
    }

    private void clearPendingImageResult(String itemId, String assetType) {
        pendingImageResultMapper.delete(new LambdaQueryWrapper<AiVideoPendingImageResult>()
                .eq(AiVideoPendingImageResult::getItemId, itemId)
                .eq(AiVideoPendingImageResult::getAssetType, assetType));
    }

    private String imageRequestHash(String prompt, List<ReferenceImage> references) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prompt.getBytes(StandardCharsets.UTF_8));
            for (ReferenceImage reference : references) {
                digest.update((byte) 0);
                digest.update(reference.name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(reference.mimeType().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(reference.dataBase64().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算图片请求标识", exception);
        }
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
        StoredMedia stored = storeGenerated(task, item, bytes, artifact.mimeType(), fileName);
        AiVideoTaskAsset asset = new AiVideoTaskAsset(task.getOrganizationId(), task.getId(), item.getId(), type,
                sequence, stored.location(), artifact.mimeType(), stored.size(), prompt, artifact.modelId());
        assetMapper.insert(asset);
        return asset;
    }

    private String productScenePrompt(AiVideoTask task, AiVideoTaskItem item,
                                      ShotPrompt anchorShot, boolean hasScriptFirstFrame) {
        String backgroundRequirement = hasScriptFirstFrame
                ? "参考图2是脚本第 01 分镜的真实背景，必须沿用其空间、家具、材质、光源、曝光、白平衡和机位关系，只移除旧主体并放入当前商品，不得重新设计或美化背景。"
                : "由模型根据商品类型、目标市场和展示方式搭配一个真实、克制、日常可落地的场景；不得复制脚本原视频房间，不使用空洞影棚、塑料感样板间或与商品无关的奢华布景。";
        String functionBlock = productFunctionAnalysisService.promptBlock(
                task, item.getProductFunctionAnalysisJson());
        StringBuilder prompt = new StringBuilder("【主体】基于参考商品图生成一张高保真电商商品场景主图。商品类型：")
                .append(safe(task.getProductCategory(), "通用商品")).append("。")
                .append("必须完整复制商品的颜色、结构、尺寸比例、Logo、包装文字、标签、图案、材质和边缘形状，")
                .append("不得重新设计、增加第二件同款、改变文字、改变颜色、融化、变形、破损或悬浮。")
                .append(functionBlock.isBlank() ? "" : functionBlock + "\n")
                .append("【背景方式】").append(backgroundRequirement)
                .append("【商品首次出镜分镜 ").append(String.format("%02d", anchorShot.sequence())).append("】")
                .append(shotExecutionDirection(task, anchorShot)).append("主体层级清楚，")
                .append("构图像真实商业摄影而非素材拼贴，前景、主体和背景保持可信的尺寸与透视关系。")
                .append("【光线与材质】").append(REAL_PHOTOGRAPHY_REQUIREMENT)
                .append(FABRIC_AND_PRODUCT_REALISM_REQUIREMENT)
                .append(fabricGripPhysicsRequirement(anchorShot))
                .append("商品与承托面建立真实接触和阴影，背景干净，无水印和促销文字。")
                .append("【输出】").append(PORTRAIT_4K_REQUIREMENT);
        return prompt.toString();
    }

    private String modelWearPrompt(AiVideoTask task, AiVideoTaskItem item, ShotPrompt firstShot, boolean hasScriptFirstFrame) {
        String sceneRequirement = hasScriptFirstFrame
                ? "【脚本首帧只提供背景】参考图2仅用于锁定脚本第 01 分镜的房间、墙面、门窗、家具、物品、材质磨损、光源方向、阴影、曝光、白平衡、景深、机位方向与人物所处位置，不得重新设计、整理、美化或替换这些背景信息。参考图2的远近景别和人物尺寸不作为约束，必须在相同机位方向上把相机向人物推进约二分之一距离，并按近距离清晰构图重新取景。参考图2中的旧人物不是人物参考，必须完整移除；绝对不能继承或融合旧人物的五官、发型、发色、肤色、年龄、身材、妆容、表情、配饰、上装、下装或鞋履。新人物继续使用本提示词规定的独立模特五官与发型特色，只把参考图1商品准确穿在新人物身上；与原生成逻辑相比，背景空间与机位方向来自参考图2。"
                : "【AI 自动搭配背景】不要复刻脚本原视频房间。根据商品颜色、品类、目标市场与第 01 分镜动作，设计一个真实有人居住、适合该商品的普通日常空间；后续九宫格以此为视觉母版。画面可自然出现少量书、杯子、折叠衣物、充电线、使用过的家具或不完全对齐的靠垫，但不能脏乱或遮挡商品。保留轻微材质磨损、墙面色差和自然遮挡，不要使用空样板间、完全对称布置、统一米白影棚或奢华网红布景。";
        String topologyBlock = productFunctionAnalysisService.promptBlock(
                task, item.getProductFunctionAnalysisJson());
        return "【REALISM V3｜手机日常随拍任务】参考图1是唯一商品素材。基于参考图1生成一张普通人用手机主摄在较近距离随手拍下的竖版服装展示照片。"
                + "日常生活感是最高风格优先级：成片应像同事、朋友或家人在普通居住空间临时记录今天穿搭，"
                + "而不是品牌广告、影棚 Lookbook、网红写真、模特卡或精修样片。目标市场："
                + safe(task.getTargetMarket(), "通用电商市场") + "。只出现一位成年人物，头顶、五官、胸前商品、双肩和构图内可见手臂清楚入镜；没有动作需要时手可以自然遮挡或出画，不为凑齐双手而补手。"
                + FRIENDLY_MODEL_FACE_BASELINE
                + "人物大致面向镜头但不要证件照式立正：身体相对画面中心轻微偏移，重心自然落在一条腿上，两侧肩髋不完全水平，"
                + "构图内可见手臂以略有差异的方式自然放置，可见手臂可轻微弯曲，头部有极小角度偏转，眼神像刚注意到拍摄者；"
                + "表情处在尚未刻意摆拍的自然瞬间，只允许闭口的轻微嘴角变化；嘴唇必须自然闭合，不露齿、不张口、不呈现说话或对口型姿态，不能露出模板化甜美微笑。"
                + "姿态松弛但能够完整展示上衣，不摆夸张时尚姿势；身体比例、关节和五根手指正确。"
                + "【AI 模特穿着母版｜不是视频首帧】这张图的唯一职责是建立同一人物正确穿着当前商品的高清母版，九宫格会另行生成脚本第 01 分镜首帧。"
                + "人物必须已经按参考商品的真实开口和穿法把衣服完整穿在身上；头、双臂分别穿过正确开口，领口依靠真实裁片、缝线和肩部支撑自然落位。"
                + "禁止把衣服举在胸前、拿在手里、披在身体前方或靠双手向外拉成露肩；禁止用手指牵拉领边伪造宽领、斜领或单肩结构。"
                + "可见的手自然下垂或在远离领口、肩线和袖口的位置轻松放置，不能遮挡领口连续路径、左右袖连接点和露肩侧；遮挡或出画的手不补出。"
                + "脚本第 01 分镜中的举起、展开、包装、拿取、比对、抓领口和其他商品状态只由后续九宫格执行，绝不能覆盖本图的‘正确穿着母版’职责。"
                + "【清晰正脸约束】人物先建立可识别的近正面人脸："
                + "脸部可保留约 2–10 度的自然轻微偏转或侧倾，双眼、眉毛、鼻梁、鼻翼、嘴唇和下颌均清楚可见；"
                + "两只眼睛共同看向镜头附近而非僵直盯住镜头，头发不遮挡主要五官。上半身接近正面，胸前商品完整无遮挡；"
                + "人物保持稳定自然站姿，不能生成侧脸、背脸、低头闭眼或无法进行人脸捕捉的画面。"
                + "【商品保真】必须保持参考服装的颜色、版型、领口、袖长、纹理、缝线、印花内容、位置和比例完全一致，"
                + "不得增加、删除、简化或重新设计服装细节。"
                + GARMENT_PIXEL_STRUCTURE_REQUIREMENT
                + (topologyBlock.isBlank() ? "" : topologyBlock + "\n")
                + FABRIC_AND_PRODUCT_REALISM_REQUIREMENT
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

    private String productStoryboardGridPrompt(AiVideoTask task, AiVideoTaskItem item,
                                               List<ShotPrompt> shots, boolean realFrameGuide) {
        String backgroundRequirement = preserveScriptBackground(task)
                ? (realFrameGuide
                    ? PRODUCT_REAL_FRAME_BACKGROUND_REQUIREMENT
                    : "背景沿用已生成的脚本原场景主图；连续分镜锁定同一空间、材质、光线和机位关系，脚本明确换景时才切换。")
                : "背景以已生成的 AI 场景主图为视觉母版；根据各分镜动作合理延展空间，保持同一套真实摄影风格，脚本明确换景时才设计协调的新场景。";
        String referenceContract = realFrameGuide
                ? "参考图1是用户上传商品外观唯一母版；参考图2是原视频按九个动作阶段抽取并拼成的真实 3×3 动作结构母版；参考图3是已生成的新商品场景主图；参考图4是服务器把参考图1原样重复到九个位置的商品身份锁定板。"
                    + "四张参考图职责不可互换：参考图1以最高优先级锁定新商品颜色、结构、文字、Logo、图案、标签和材质；参考图2逐格决定机位方向、商品位置、正面/侧面/背面朝向、手部有无、手部进入方向、抓握区域、动作阶段、环境和光影；参考图3只补充新场景摄影质感；参考图4逐格重复锁定参考图1的商品身份。"
                    + "参考图2的原景别与主体尺度不继承：输出中凡商品出镜都必须重新构图为主体填满主要区域的商品近景，远景、全景、拉远、后退和主体缩小信息无效。"
                    + "参考图4只用于让输出第01–09格各自直接回锚同一件原商品，不能照搬其中白底、静止姿态或九格重复构图；输出每格动作仍取参考图2。"
                    + "参考图2中的旧商品像素、旧颜色、旧图案和旧版型全部无效，必须完整替换为参考图1，但不能因此抹掉参考图2的手部接触点、旋转方向、可见正背面或悬垂状态。原格没有商品时绝对不得添加商品。"
                : "参考图1是商品场景与构图参考，参考图2是用户上传商品外观唯一母版，参考图3是服务器把参考图2原样重复到九个位置的商品身份锁定板。参考图2的商品拥有最高优先级；参考图3只负责让输出每一格重新回锚原商品，不得照搬白底和重复构图。";
        String productMasterName = realFrameGuide ? "参考图1" : "参考图2";
        String functionBlock = productFunctionAnalysisService.promptBlock(
                task, item.getProductFunctionAnalysisJson());
        StringBuilder prompt = new StringBuilder(referenceContract)
                .append("生成一张严格的 3×3 连续商品展示九宫格，按从左到右、从上到下对应 01→09。")
                .append(PORTRAIT_4K_REQUIREMENT)
                .append("【九格商品身份必须独立回锚】生成 01–09 每一格之前，都必须重新直接读取")
                .append(productMasterName).append("的原始商品像素，分别重建同一件商品；后一个格子只能继承动作时间，")
                .append("绝对不能把前一个已生成格子的商品轮廓、领口、袖型、图案或文字当作新的商品参考。")
                .append("禁止按 01→02→03 的方式连续改画商品，禁止误差沿时间轴累积；第 09 格与第 01 格承担完全相同的商品保真标准。")
                .append("先锁定").append(productMasterName).append("中商品的轮廓与表面内容，再生成九格；只在对应脚本明确要求商品出镜的格子放入商品，商品首次出镜之前的铺垫、环境和测试画面禁止强塞商品。商品一旦出镜，后续所有出镜格必须逐像素级保持同一件商品的颜色、结构、比例、Logo、包装文字、标签、插画、人物图案和材质一致。")
                .append("禁止采用分镜脚本文字或原视频中出现的任何其他商品、品牌、颜色、Logo、包装、印花或文案。")
                .append("不得把").append(productMasterName).append("的复杂插画或印花替换成纯文字、其他商品主题或模型自行设计的图案，也不得简化、概括或重新排版。")
                .append(functionBlock.isBlank() ? "" : functionBlock + "\n")
                .append(backgroundRequirement)
                .append(realFrameGuide
                        ? "九格必须逐格对照参考图2的同一位置复刻动作结构，不能只模仿整体风格。参考图2某格无人手时该格绝对不能出现手、手臂或人体；"
                            + "某格有手时必须复刻同一只手的进入方向、掌心朝向、手指姿态、遮挡区域和接触位置。参考图2的光斑、动作阶段和俯拍角度逐格保留；商品占比按商品近景门禁重新归一。"
                        : "没有真实抽帧母版时，严格按照下面每格文字执行手部与构图状态。")
                .append("每格场景、展示方式、构图意图和手部状态只读取对应分镜，禁止用任一格覆盖九格；所有商品出镜格的尺度统一服从商品近景门禁。")
                .append("原视频即使全程同一只手持商品，也必须逐格区分抓握位置、手腕角度、商品正面/斜侧/窄边/背面、展开程度和摆动方向；禁止把‘同一只手’误解成‘九格同一姿势’。")
                .append("相邻格只推进一个小幅动作或镜头变化；商品不得复制、跳变、漂移、悬浮、融化、穿模或突然更换背景。")
                .append("每格都必须保持真实摄影的透视、接触阴影和光源方向，并让商品材质对光线产生正确响应。")
                .append(FABRIC_AND_PRODUCT_REALISM_REQUIREMENT)
                .append("连续分镜若都要求手部互动，必须保持同一只手或同一双手，数量、进入方向和接触点连续；只有脚本明确重新抓握时才改变接触点，只在指定边界出现或退出手部。")
                .append("不得添加编号、文字、边框、水印、额外商品或无关配件。每格要求如下：\n");
        for (ShotPrompt shot : shots) {
            String value = productMotionDirection(task, shot);
            if (value.length() > 600) value = value.substring(0, 600);
            prompt.append("分镜 ").append(String.format("%02d", shot.sequence())).append("：").append(value).append('\n');
        }
        prompt.append(realFrameGuide
                        ? PRODUCT_REAL_FRAME_OUTPUT_CHECK
                        : "输出前逐格核对手部状态、商品位置、朝向、动作与光影阶段；所有商品出镜格必须保持主体填满主要区域的商品近景；")
                .append("同时检查商品数量唯一、文字和 Logo 未改变、结构未变形、接触与阴影真实；发现任一格不合格时重新完成整张九宫格，不承诺局部修改。")
                .append("尤其将第 07、08、09 格分别与商品母版直接对照，不能只与第 06 格对照；若后段出现领口缩小、袖型变短、版型变窄、印花简化或材质改变，整张九宫格判废并重算。")
                .append("最终只输出一张 2160×3840 的完整 4K 九宫格图片。");
        return prompt.toString();
    }

    private String productMotionDirection(AiVideoTask task, ShotPrompt shot) {
        StringBuilder value = new StringBuilder();
        if (!shot.title().isBlank()) value.append("目标：").append(shot.title()).append("；");
        if (!shot.scene().isBlank()) value.append("场景与构图意图：").append(shot.scene()).append("；");
        if (!shot.motion().isBlank()) value.append("动作：").append(shot.motion()).append("；");
        value.append("景别硬覆写：").append(imageFramingDirective(task.getGenerationMode())).append("；");
        value.append("执行运镜：").append(normalizedImageCameraMovement(shot.cameraMovement())).append("；");
        if (value.isEmpty()) value.append("保持同一商品，进行轻微且连续的电商展示变化；");
        value.append("手部：").append(shotHandDirective(task, shot)).append("；");
        value.append(fabricGripPhysicsRequirement(shot));
        if (shotShowsProduct(shot)) {
            value.append("本格明确要求当前任务商品出镜；只执行动作与运镜，任何旧商品外观描述均无效，商品外观严格复制本提示词指定的用户上传商品唯一母版。");
        } else {
            value.append("本格未要求当前任务商品出镜；严格复刻铺垫、环境或测试内容，禁止添加参考图2商品、包装、Logo 或商品替身。");
        }
        return value.toString();
    }

    private boolean shotShowsProduct(ShotPrompt shot) {
        String value = String.join(" ", shot.title(), shot.scene(), shot.motion(), shot.prompt()).toLowerCase();
        return shotTextShowsProduct(value);
    }

    static boolean shotTextShowsProduct(String value) {
        value = value == null ? "" : value.toLowerCase();
        if (containsAny(value, "无商品", "不出现商品", "商品不出镜", "不要商品", "no product", "without product")) {
            return false;
        }
        return containsAny(value, "商品", "产品", "包装", "瓶身", "罐体", "盒体",
                "product appears", "show the product", "product close-up", "package", "bottle", "container");
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
                + "生成一张严格的 3×3 九宫格连续动作控制图。本图只负责动作、站位、构图意图、机位方向和环境，景别统一服从中近景门禁，"
                + "不得生成任何可识别的真实人物身份。每格使用同一个无脸中性人体模型或简洁写实的人体姿态轮廓："
                + "脸部保持空白，不画五官、发型、肤色、年龄特征，不穿带有颜色、图案、文字或款式特征的具体服装。"
                + "人体只用于准确表达关节、肢体朝向、重心和动作轨迹；后续视频会用独立模特图替换该人体。"
                + PORTRAIT_4K_REQUIREMENT
                + "整张图必须正好包含 9 个大小一致的 9:16 竖屏画面，每格在 4K 总图中约为 720×1280 像素，"
                + "按从左到右、从上到下的顺序对应分镜 1 到 9。01→09 是按时间排序的动作关键帧，不是 9 张互不相关的摆拍照片。"
                + "同一真实镜头内，相邻格只推进一个小而可达的主要动作，结束姿态自然衔接下一格；"
                + "只有脚本标记的真实镜头边界才切换场景或机位方向；主体中近景尺度始终稳定。不得瞬移、肢体跳变、无原因回正或承重漂移。"
                + GRID_POSE_SCALE_REQUIREMENT
                + "画外不可见的承重与重心必须连续稳定；不得为了显示脚或地面接触而拉远。"
                + "九格中的无脸人体模型必须保持相同身体比例，但不携带任何人物身份与穿搭信息；分镜描述中的人物外貌、发型、衣服和配饰一律忽略。"
                + "分镜描述中的原视频商品类别、颜色、版型、材质、印花、文字、Logo、字幕和卖点同样一律忽略；动作区域只允许称为当前任务商品，最终商品以任务上传商品图和后续模特图为准。"
                + "所有姿态必须符合真实人体骨骼、关节角度、重心与惯性；手臂和手指不得穿过身体或衣服，双腿不得粘连，"
                + "人物不得穿入家具、墙面或地面，不得出现反关节、肢体增减、手指融合、身体扭曲、重影或衣服穿模。"
                + "不要添加文字、数字、边框、水印或额外格子。每格内容如下：\n");
        for (ShotPrompt shot : shots) {
            prompt.append("分镜 ").append(shot.sequence()).append("：")
                    .append(shotExecutionDirection(task, shot)).append("\n");
        }
        prompt.append("最终只输出一张 2160×3840 的完整 4K 九宫格图片，不得降级为 1024 像素级别。"
                + "构图清楚、格子边界规整，背景真实自然，无多余肢体。输出前逐格核对：九格不得出现真实人脸或具体服装，"
                + "且任意相邻两格都能由真实人体在短时间内连续完成；任何一格出现穿模、反关节或诡异肢体都必须重算该格。");
        return prompt.toString();
    }

    private String storyboardIdentityTransferPrompt(AiVideoTask task, AiVideoTaskItem item,
                                                    List<ShotPrompt> shots, boolean realFrameGuide) {
        String topologyBlock = productFunctionAnalysisService.promptBlock(
                task, item.getProductFunctionAnalysisJson());
        StringBuilder prompt = new StringBuilder("执行一次严格的九宫格人物与商品替换编辑。四张参考图职责不可互换：")
                .append("参考图1是用户上传的真实商品原图，锁定商品颜色、结构、面料、缝线、印花、文字、图形、Logo、位置和比例；")
                .append("参考图2是把同一张 AI 模特穿着母版原样重复九次的人物身份锁定板，只负责唯一人物身份、脸、发型、身材、商品穿着位置、下装、配饰和整体摄影风格；九个重复格不是动作证据。")
                .append("参考图3是 3×3 动作场景底图，只提供每格可见场景、主要物体、空间关系、机位方向和姿态证据；下方逐格脚本决定最终必须执行的主体状态、动作阶段和时间顺序。")
                .append("参考图4是服务器把参考图1原样重复到九个对应位置的商品身份锁定板；生成输出第01–09格时必须分别读取参考图4同位置的原商品结构，不得让前一输出格成为后一格的商品参考。参考图4的白底、静止姿态和九格重复布局不是动作证据，不能覆盖参考图3与脚本。")
                .append("参考图3中的任何人物身份、脸、头发、肤色、衣服、颜色、印花、文字、Logo、下装和配饰全部是无效占位内容，必须删除，绝对不能继承、融合或折中。")
                .append("【商品状态】无论商品处于穿着、手持、展开、折叠、包装或特写状态，都复现参考图1和下方结构证据中的同一件商品；不得从参考图3的占位服装继承结构。")
                .append("参考图2若因生成误差与参考图1的商品细节不一致，以参考图1为准纠正商品；参考图2只提供商品穿在人身上的位置、尺度、遮挡和自然布料形变。")
                .append("逐格同时核对参考图3与下方脚本：场景、物体和可见姿态取参考图3，主体是包装中、折叠、展开、手持、穿上、已穿着或局部特写等状态以及动作阶段严格取下方同编号脚本。两者冲突时不得自行折中或统一成同一姿势，必须以脚本状态和动作为准重建该格；原图中的远近景别和人物尺寸不保留，所有人物格必须重新按本提示词的近距离比例构图。")
                .append(realFrameGuide
                        ? "参考图3来自原视频真实抽帧。第 01–09 格的背景、动作和机位均取参考图3对应位置；人物身份与非商品搭配取参考图2。允许为了近景构图自然放大和重新取景，不得把九格改造成统一样板间。"
                        : "当前任务选择 AI 自动搭配背景。参考图3决定每格动作、站位、场景类型、场景切换和构图意图，景别只服从中近景门禁；参考图2提供人物、穿着关系与真实摄影风格。不复刻脚本原视频房间，只重新设计每格环境的具体陈设。若背景存在塑料感、CG感、假材质、无来源高光或过度整洁，必须按参考图2的真实摄影标准重建材质与光线。")
                .append(realFrameGuide
                        ? "新人物必须适应参考图3各格原有的真实光源、色温、曝光、阴影与景深；不得反过来修改背景去迁就人物。"
                        : "但九格的色温、曝光、对比度、手机随拍质感、光线自然度、材质表现和日常生活氛围统一匹配参考图2。")
                .append(realFrameGuide
                        ? "参考图2只提供人物身份、非商品搭配与商品穿着尺度，不提供任何格的背景或动作。不同分镜原本存在的换景和商品特写必须保留；人物格的景别和可见范围只服从下方近景构图规则，不在此另设脚部例外。"
                        : "只迁移参考图2的视觉风格，不能把九格背景全部替换成参考图2的同一个房间，也不能抹掉参考图3要求的换景和商品特写。")
                .append("把九格中的每一个占位人体逐格替换为参考图2中的同一个真实人物。必须复制参考图2的同一张脸、五官比例、肤色、")
                .append("发型、发长、身材比例、构图内可见下装和配饰；上衣商品外观必须以参考图1为准。九格人物必须是同一个人、同一套穿搭，不得重新选角或重新设计服装。")
                .append("【人物身份】参考图2的脸是唯一人物母版。参考图3残留的人脸、年龄、肤色或发型均为占位内容，不得继承或融合。")
                .append("逐格保持参考图2的脸型轮廓、眉形、眼裂、眼距、虹膜、鼻梁、鼻翼、嘴唇、下颌线和年龄观感；只允许因转头、透视和轻微表情产生正常变化。")
                .append("如果动作姿态与人脸身份发生冲突，优先保住参考图2的人脸身份与自然五官，再对姿态做最小幅度调整；禁止重新抽取另一位模特。")
                .append("九格审美基调完全继承参考图2，不得为了所谓美化重新画脸，也不得把参考图2变老、变疲惫、变凶或改变亲和感。")
                .append("【第 01 格】人物身份取参考图2；商品状态、动作、姿态和场景只按参考图3第01格及同编号脚本执行，不预设手持、穿着、正脸或商品特写。")
                .append(GRID_FACE_PIXEL_REQUIREMENT)
                .append("参考图2如果是远景，不沿用其人物尺寸；每格按近景规则重新构图，但姿态、场景和机位方向仍取参考图3与脚本。")
                .append("【商品外观】逐格复制参考图1的真实商品设计，包括颜色、结构、面料、缝线、印花、文字、图形、Logo、大小、位置与排列顺序；参考图2只用于确认穿着尺度与自然布料形变。")
                .append(GARMENT_PIXEL_STRUCTURE_REQUIREMENT)
                .append(topologyBlock.isBlank() ? "" : topologyBlock + "\n")
                .append("不得重新理解商品、不得生成相似款、不得继承参考图3的占位衣服，也不得改字、漏字、增加装饰、替换图案或改变版型。")
                .append("侧身、坐姿和特写只允许产生符合真实透视的轻微布料形变，商品身份和全部可见设计必须保持不变。")
                .append(PORTRAIT_SKIN_QUALITY_REQUIREMENT)
                .append(FACE_COLOR_CONTINUITY_REQUIREMENT)
                .append(NATURAL_HAIR_REQUIREMENT)
                .append(NATURAL_EYE_REQUIREMENT)
                .append(REAL_PHOTOGRAPHY_REQUIREMENT)
                .append(FABRIC_AND_PRODUCT_REALISM_REQUIREMENT)
                .append("九格必须锁定参考图2的同一套基础肤色、脸与脖子的色差关系、曝光、白平衡和锐度参数，不能逐格重新美颜、重新上妆或改变皮肤质感。")
                .append("【模特静默】所有人物格都必须保持嘴唇自然闭合和下颌稳定，不露齿、不张口、不说话、不唱歌、不做口型。脚本中的对白、台词、口播、讲解、旁白或 narration 只作为后期画外旁白语义，绝不能变成人物口型。")
                .append("微表情不需要每格都变化。不能为了让九格看起来不同而逐格重新生成眉毛、眼睑、眼球、鼻翼、嘴唇和嘴角。只有不涉及开口的明确面部动作才允许建立一次起势→过程→到位的连续变化，不能突然大笑、张嘴、露齿、瞪眼或改变年龄和脸型。每格面部状态如下：\n");
        for (ShotPrompt shot : shots) {
            String expression = inferMicroExpression(shot);
            prompt.append("分镜 ").append(String.format("%02d", shot.sequence()))
                    .append("：").append(shotExecutionDirection(task, shot))
                    .append(expression).append("；视线与该格动作目标一致，双眼共同对焦。")
                    .append(fabricGripPhysicsRequirement(shot)).append("\n");
        }
        prompt.append("仅允许改变人物姿态和上述微表情以贴合参考图3，人物身份和参考图1的商品外观不得随姿态改变。")
                .append("【异常规避优先级】九宫格的可用性高于复杂动作的精确复刻。如果某个动作会导致脸部崩坏、手指异常、肢体穿模、重心失衡或服装图案扭曲，")
                .append("必须把该格动作幅度缩小为解剖正确的中间姿态，不能带着异常输出。每个人的身体最多两条手臂、两只手和两条腿；")
                .append("只约束构图内可见部分，不为凑数量补出被遮挡或出画的手脚。每只可见手必须连接唯一手臂，头颈肩连接自然。侧身格也保持同一人物和穿搭。")
                .append("第 01–09 格的朝向均服从各自脚本；不得为了人脸锚定把侧脸、低头或商品特写强制改成正脸。")
                .append("人物的画外承重与重心必须连续稳定并与环境建立合理遮挡；不得为了显示脚地接触而拉远。手臂、手指、头发和构图内可见下肢不得穿过身体、衣服、家具或墙面；")
                .append("输出前逐格检查人物边界与可见脸部或头部轮廓：头顶、该朝向自然可见的五官、本格按脚本可见的当前商品部位、构图内可见手臂和执行动作实际需要的手不得越界、贴边或被截断；不可见肢体无需补出。背身、后脑、低头遮脸或商品遮脸格不得强行补画五官或转成正脸。默认在大腿中下段至膝盖上方结束构图。可见人脸或头部轮廓低于约 190 像素、人物被缩成远景时，必须保持原动作把相机推进并重新构图，不得通过后期裁切放大补救。")
                .append("不得出现换脸、脸型漂移、年龄变化、发型变化、衣服变化、图案乱码、反关节、手指融合、肢体增减、身体扭曲或重影。")
                .append("最终只输出一张 2160×3840 的 9:16 竖屏 4K 九宫格成图，不输出解释、文字、编号、水印或额外画面。")
                .append("输出前只对各格实际可见的人脸、耳朵、脖子、手臂、头发、穿搭和背景做一致性核验；不可见部位无需补出。"
                        + "可见皮肤出现偏灰、偏黄、偏绿、局部泛红、面具边界或与参考图2肤色不一致时，该格视为不合格。");
        return prompt.toString();
    }

    private String inferMicroExpression(ShotPrompt shot) {
        return inferMicroExpression(shot.title(), shot.motion());
    }

    static String inferMicroExpression(String title, String motion) {
        String context = ((title == null ? "" : title) + " " + (motion == null ? "" : motion)).toLowerCase();
        if (containsAny(context, "说话", "口播", "台词", "讲解", "对白", "旁白", "解说",
                "speak", "talk", "dialogue", "voiceover", "voice over", "lip sync", "narration")) {
            return "说话、口播、台词或旁白只作为画外音语义；人物嘴唇自然闭合且稳定，不露齿、不张口、不生成口型，可保留克制的闭口微笑";
        }
        if (containsAny(context, "眨眼", "闭眼", "睁眼")) {
            return "按动作只完成一次双眼同步的完整眨眼，其他五官保持稳定，不连续眨眼或眼睑颤动";
        }
        if (containsAny(context, "微笑", "笑", "开心", "愉悦")) {
            return "嘴唇自然闭合、不露齿，嘴角从原状态缓慢形成轻微闭口微笑并稳定保持，眉毛、鼻翼与下颌不跟随抽动";
        }
        if (containsAny(context, "看向", "视线", "回眸", "低头", "抬头")) {
            return "双眼只向动作目标平滑移动一次并稳定对焦，嘴角、眉毛和鼻翼沿用上一格状态";
        }
        return "沿用上一格的放松自然表情，嘴唇闭合稳定、不露齿、不张口，不新增嘴角、眉毛、鼻翼、眼睑或眼球动作";
    }

    private String shotExecutionDirection(AiVideoTask task, ShotPrompt shot) {
        StringBuilder value = new StringBuilder();
        if (!shot.title().isBlank()) value.append("目标：").append(shot.title()).append("；");
        if (!shot.scene().isBlank()) {
            value.append("场景与构图意图（其中远景、全身、脚部和拉远信息无效）：")
                    .append(shot.scene()).append("；");
        }
        if (!shot.motion().isBlank()) value.append("动作严格按脚本：").append(shot.motion()).append("；");
        value.append("人物静默硬覆写：脚本中的说话、口播、讲解、对白、台词或旁白只作为画外音语义；人物嘴唇自然闭合，不张口、不做口型；");
        value.append("景别硬覆写：").append(imageFramingDirective(task.getGenerationMode()))
                .append("；执行运镜：").append(normalizedImageCameraMovement(shot.cameraMovement())).append("；");
        value.append("手部规则：").append(shotHandDirective(task, shot)).append("；");
        return value.toString();
    }

    static String imageFramingDirective(String generationMode) {
        return "PRODUCT_ONLY".equals(generationMode)
                ? "商品近景，主体填满主要画面，禁止远景、全景、拉远和主体缩小"
                : "人物中近景，头顶至大腿中下段、下边界在膝盖上方、可见脸或头部轮廓高度至少15%，禁止远景、全身、脚部入镜和拉远；背身或遮脸动作不得强行补脸或转正";
    }

    static String normalizedImageCameraMovement(String movement) {
        if (movement == null || movement.isBlank()) return "固定机位或近景范围内轻微运动";
        String normalized = movement.toLowerCase();
        boolean pullsAway = containsAny(normalized,
                "拉远", "后退", "远离", "广角缩人", "zoom out", "dolly out", "pull back", "truck out", "wide shot");
        if (pullsAway) return "原拉远、后退或广角缩人运镜已禁用，改为近景范围内轻微横移、环绕或固定机位";
        return "仅在不改变主体近景尺度的前提下执行原运镜：" + movement;
    }

    private String shotHandDirective(AiVideoTask task, ShotPrompt shot) {
        String context = (shot.title() + " " + shot.scene() + " " + shot.motion() + " " + shot.prompt()).toLowerCase();
        boolean explicitlyNoHand = containsAny(context, "无人手", "无手部", "不出现手", "没有手", "纯静物", "平铺", "仅商品", "no hand", "hands-free");
        boolean requiresHand = !explicitlyNoHand && AiVideoTaskService.handInteractionRequired(context);
        if ("MODEL_WEAR".equals(task.getGenerationMode())) {
            if (explicitlyNoHand) {
                return "按本格构图让手自然遮挡或出画，不为凑齐数量补手，也不得从画面边缘新增手掌";
            }
            return requiresHand
                    ? "只执行本格写明的手势、拿取或触碰，可见手的数量、朝向和接触位置与脚本一致，不新增其他手势"
                    : "沿用本格实际可见的手部状态；允许自然遮挡或出画，不新增拿取、指向、拉扯、点赞、挥手或持物动作";
        }
        return AiVideoTaskService.productOnlyHandDirective(
                shot.title() + " " + shot.motion() + " " + shot.prompt(), shot.scene());
    }

    private String fabricGripPhysicsRequirement(ShotPrompt shot) {
        String context = String.join(" ", shot.title(), shot.scene(), shot.motion(), shot.prompt()).toLowerCase();
        if (!containsAny(context,
                "捏住", "捏起", "提起一角", "提着一角", "掀起", "拎起", "拉起衣角", "拉起下摆", "拉起袖口",
                "pinch", "lift a corner", "lifted corner", "grip a corner")) return "";
        if (containsAny(context, "双手", "两手", "左右两侧", "两边", "both hands", "two hands")) {
            return "【双手布料受力】只使用脚本中的两个真实接触点，接触点之间形成自然张力，未支撑部分受重力下垂；"
                    + "不得增加第三只手，也不得改变袖片、袖口、领口和接缝连接。";
        }
        return FABRIC_GRIP_PHYSICS_REQUIREMENT;
    }

    private static boolean containsAny(String value, String... candidates) {
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
                    nullableText(shot.get("cameraMovement")),
                    nullableText(shot.get("emotion"))));
        }
        shots.sort(Comparator.comparingInt(ShotPrompt::sequence));
        return shots;
    }

    private ReferenceImage reference(Path path, String name, String mimeType) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return new ReferenceImage(name, mimeType, Base64.getEncoder().encodeToString(bytes));
    }

    private ReferenceImage reference(String location, String name, String mimeType) throws Exception {
        byte[] bytes = readAsset(location);
        return new ReferenceImage(name, mimeType, Base64.getEncoder().encodeToString(bytes));
    }

    private ReferenceImage productIdentityGridReference(Path sourcePath, String sourceMimeType) throws Exception {
        BufferedImage source = ImageIO.read(sourcePath.toFile());
        if (source == null) return reference(sourcePath, "参考图4 九格商品身份锁定板", sourceMimeType);
        int cellWidth = 512;
        int cellHeight = 910;
        BufferedImage guide = new BufferedImage(cellWidth * 3, cellHeight * 3, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = guide.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, guide.getWidth(), guide.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.min((cellWidth * 0.92) / source.getWidth(),
                    (cellHeight * 0.92) / source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            for (int index = 0; index < 9; index++) {
                int x = (index % 3) * cellWidth + (cellWidth - width) / 2;
                int y = (index / 3) * cellHeight + (cellHeight - height) / 2;
                graphics.drawImage(source, x, y, width, height, null);
            }
        } finally {
            graphics.dispose();
        }
        return new ReferenceImage("参考图4 九格商品身份锁定板；每格均为同一原商品",
                "image/png", Base64.getEncoder().encodeToString(png(guide)));
    }

    private ReferenceImage modelIdentityGridReference(String location, String sourceMimeType) throws Exception {
        byte[] sourceBytes = readAsset(location);
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
        if (source == null) {
            return new ReferenceImage("参考图2 AI模特九格身份锁定板", sourceMimeType,
                    Base64.getEncoder().encodeToString(sourceBytes));
        }
        int cellWidth = 512;
        int cellHeight = 910;
        BufferedImage guide = new BufferedImage(cellWidth * 3, cellHeight * 3, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = guide.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, guide.getWidth(), guide.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
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
            for (int index = 0; index < 9; index++) {
                int x = (index % 3) * cellWidth;
                int y = (index / 3) * cellHeight;
                graphics.drawImage(source, x, y, x + cellWidth, y + cellHeight,
                        sourceX, sourceY, sourceX + cropWidth, sourceY + cropHeight, null);
            }
        } finally {
            graphics.dispose();
        }
        return new ReferenceImage("参考图2 AI模特九格身份锁定板；九格均为同一人物，只锁身份不锁动作",
                "image/png", Base64.getEncoder().encodeToString(png(guide)));
    }

    private boolean assetReadable(AiVideoTaskAsset asset) {
        if (asset == null || asset.getFilePath() == null) return false;
        try {
            if (ossStorage != null && ossStorage.isOssLocation(asset.getFilePath())) {
                return ossStorage.exists(asset.getFilePath());
            }
            Path path = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
            return path.startsWith(storageRoot) && Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] readAsset(String location) throws Exception {
        if (ossStorage != null && ossStorage.isOssLocation(location)) {
            return ossStorage.readAllBytes(location, 64 * 1024 * 1024);
        }
        Path path = Path.of(location).toAbsolutePath().normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) {
            throw new IllegalStateException("生成素材不存在");
        }
        return Files.readAllBytes(path);
    }

    private StoredMedia storeGenerated(AiVideoTask task, AiVideoTaskItem item, byte[] bytes,
                                       String contentType, String fileName) throws Exception {
        if (ossStorage != null) {
            String category = "generated-images/" + task.getOrganizationId() + "/" + task.getId() + "/" + item.getId();
            OssStorageService.StoredObject stored = ossStorage.store(bytes, contentType, category, fileName);
            return new StoredMedia(stored.location(), stored.size());
        }
        Path directory = storageRoot.resolve(task.getOrganizationId()).resolve(task.getId())
                .resolve(item.getId()).resolve("outputs").normalize();
        if (!directory.startsWith(storageRoot)) throw new IllegalStateException("生成目录不安全");
        Files.createDirectories(directory);
        Path path = directory.resolve(fileName).normalize();
        Files.write(path, bytes);
        return new StoredMedia(path.toString(), bytes.length);
    }

    private byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG 图片编码失败");
        return output.toByteArray();
    }
    private int number(Object value, int fallback) { try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private String extension(String mime) { return "image/jpeg".equalsIgnoreCase(mime) ? "jpg" : "image/webp".equalsIgnoreCase(mime) ? "webp" : "png"; }
    private boolean preserveScriptBackground(AiVideoTask task) { return !"AI_DESIGNED".equals(task.getBackgroundMode()); }
    private String safe(String value, String fallback) { return value == null || value.isBlank() || "null".equals(value) ? fallback : value; }
    private String nullableText(Object value) { return value == null || "null".equals(String.valueOf(value)) ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim(); }
    private static String readable(Exception exception) {
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

    private record ShotPrompt(int sequence, String prompt, String title, String scene, String motion,
                              String cameraMovement, String emotion) {}
    private record ReferenceImage(String name, String mimeType, String dataBase64) {}
    private record SourceFramePoint(double timestampSeconds, double durationSeconds) {}
    private record StoredMedia(String location, long size) {}
    record TopologyRetryState(int attemptsReserved, String reservedPrompt,
                              ProductFunctionAnalysisService.GarmentValidation previousValidation,
                              boolean legacyRecovery) {
        TopologyRetryState {
            attemptsReserved = Math.max(0, Math.min(MAX_TOPOLOGY_GENERATIONS, attemptsReserved));
            reservedPrompt = reservedPrompt == null ? "" : reservedPrompt;
        }

        static TopologyRetryState initial(boolean legacyRecovery, String recoveredPrompt) {
            return new TopologyRetryState(
                    legacyRecovery ? MAX_TOPOLOGY_GENERATIONS : 0,
                    legacyRecovery ? recoveredPrompt : "", null, legacyRecovery);
        }

        boolean canGenerate() {
            return attemptsReserved < MAX_TOPOLOGY_GENERATIONS;
        }

        TopologyRetryState reserve(String prompt) {
            if (!canGenerate() || !reservedPrompt.isBlank()) {
                throw new IllegalStateException("服装结构重抽轮次状态无效");
            }
            return new TopologyRetryState(attemptsReserved + 1, prompt, previousValidation, false);
        }

        TopologyRetryState reject(ProductFunctionAnalysisService.GarmentValidation validation) {
            return new TopologyRetryState(attemptsReserved, "", validation, legacyRecovery);
        }

        TopologyRetryState recoverPending(String prompt) {
            return new TopologyRetryState(attemptsReserved, prompt, previousValidation, true);
        }

        TopologyRetryState abandonReservation() {
            if (reservedPrompt.isBlank()) return this;
            return new TopologyRetryState(attemptsReserved, "", previousValidation, legacyRecovery);
        }

        TopologyRetryState releaseReservation() {
            if (reservedPrompt.isBlank()) return this;
            return new TopologyRetryState(Math.max(0, attemptsReserved - 1), "",
                    previousValidation, legacyRecovery);
        }

        boolean acceptsPendingHash(String pendingHash, String expectedHash) {
            return legacyRecovery || expectedHash != null && expectedHash.equals(pendingHash);
        }

        boolean mayProcessReservedAttempt(boolean reservedInThisInvocation,
                                          boolean hasRecoverablePending) {
            return !reservedPrompt.isBlank()
                    && (reservedInThisInvocation || hasRecoverablePending);
        }
    }
    record TopologyCandidate(String mimeType, byte[] bytes, String modelId, String mode,
                             String prompt, String revisedPrompt) {
        static TopologyCandidate fromArtifact(PiArtifact artifact, String prompt, byte[] bytes) {
            return new TopologyCandidate(artifact.mimeType(), bytes, artifact.modelId(), artifact.mode(),
                    prompt, artifact.revisedPrompt());
        }

        PiArtifact toArtifact() {
            return new PiArtifact("private-validation", mimeType,
                    Base64.getEncoder().encodeToString(bytes), modelId, mode, prompt, revisedPrompt);
        }
    }
    record BestTopologyCandidate(TopologyCandidate candidate, int violationCount, int attemptNumber) {}
    private record PiImageResponse(PiArtifact artifact, PiImageResult result, Map<String, Object> usage) {}
    private record PiImageResult(String url, String mimeType, String modelId, String mode,
                                 String prompt, String revisedPrompt) {}
    private record PiArtifact(String name, String mimeType, String dataBase64, String modelId, String mode,
                              String prompt, String revisedPrompt) {}
    private static final class UnavailablePendingImageResultException extends IllegalStateException {
        private UnavailablePendingImageResultException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
