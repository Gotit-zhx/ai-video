package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiVideoLightingPromptTest {
    private static final String SCRIPT = """
            {"shots":[{"sequence":1,"title":"展示商品","sceneDescription":"室内柜体在画面右侧",
            "motionDescription":"自然站立","cameraMovement":"固定机位",
            "imagePrompt":{"prompt":"浅灰墙面与明亮柜体的简洁室内，自然室内光"}}]}
            """;
    @TempDir Path directory;
    private final AiVideoTaskAssetMapper assets = mock(AiVideoTaskAssetMapper.class);
    private final ModelConfigService models = mock(ModelConfigService.class);
    private AiVideoImageProcessor processor;
    private List<?> shots;

    @BeforeEach void setUp() {
        var analysis = mock(ProductFunctionAnalysisService.class);
        when(analysis.promptBlock(any(), nullable(String.class))).thenReturn("");
        processor = new AiVideoImageProcessor(mock(AiVideoTaskMapper.class), mock(AiVideoTaskItemMapper.class),
                assets, mock(AiVideoPendingImageResultMapper.class), mock(VideoScriptMapper.class),
                mock(VideoScriptShotMapper.class), models, analysis, mock(AiVideoGenerationService.class),
                new ObjectMapper(), Runnable::run, Runnable::run, mock(ObjectProvider.class),
                "http://127.0.0.1:1", "test-only", directory.toString(), directory.toString());
        shots = ReflectionTestUtils.invokeMethod(processor, "readShots", SCRIPT);
    }

    @ParameterizedTest
    @CsvSource({"MODEL_WEAR,SCRIPT_ORIGINAL", "MODEL_WEAR,AI_DESIGNED",
            "PRODUCT_ONLY,SCRIPT_ORIGINAL", "PRODUCT_ONLY,AI_DESIGNED"})
    void primaryImagesAreNaturallyBrightWithoutChangingProducts(String mode, String background) {
        var task = task(mode, background);
        String prompt = primary(task, false);
        assertNaturalExposure(prompt);
        assertNaturalFabric(prompt);
        assertPhotographicEdges(prompt);
        assertFalse(prompt.contains("不主动改变场景亮度"));
        assertFalse(prompt.contains("不额外设计或添加窗光"));
        if ("SCRIPT_ORIGINAL".equals(background)) {
            assertTrue(prompt.contains("【脚本原背景｜按文字场景还原】"));
            assertTrue(prompt.contains("浅灰墙面与明亮柜体"));
            assertTrue(prompt.contains("以下脚本素材仅用于提取环境与采光"));
            assertFalse(prompt.contains("【AI 自动搭配背景】"));
        } else {
            assertTrue(prompt.contains("【AI 自动搭配背景】"));
            assertTrue(prompt.contains("采光充足"));
            assertFalse(prompt.contains("浅灰墙面与明亮柜体"));
        }
        if ("MODEL_WEAR".equals(mode)) {
            assertTrue(prompt.contains("【REALISM V6｜自然采光与面料保真手机随拍任务】"));
            assertTrue(prompt.contains("【商品保真】"));
            assertTrue(prompt.contains("正确穿着母版"));
            assertTrue(prompt.contains("【中近景门禁版本：CLOSE_FRAMING_GATE_V1】"));
            if ("AI_DESIGNED".equals(background)) {
                assertTrue(prompt.contains("生活使用痕迹仅限家具与房间陈设"));
                assertTrue(prompt.contains("衣服不继承背景的磨损或旧物质感"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODEL_WEAR", "PRODUCT_ONLY"})
    void originalBackgroundEvidenceDoesNotLockUnderexposure(String mode) {
        String prompt = primary(task(mode, "SCRIPT_ORIGINAL"), true);
        assertNaturalExposure(prompt);
        assertNaturalFabric(prompt);
        assertPhotographicEdges(prompt);
        assertTrue(prompt.contains("参考图2"));
        assertTrue(prompt.contains("不照搬欠曝"));
        assertFalse(prompt.contains("曝光、白平衡、景深、机位方向"));
    }

    @Test void missingSceneEvidenceHasABrightFallbackWithoutAddingSourceProductReferences() {
        shots = ReflectionTestUtils.invokeMethod(processor, "readShots", "{\"shots\":[{\"sequence\":1}]}");
        String prompt = primary(task("MODEL_WEAR", "SCRIPT_ORIGINAL"), false);
        assertTrue(prompt.contains("未说明环境时采用采光充足的简洁日常空间"));
        assertNaturalExposure(prompt);
        assertFalse(AiVideoImageProcessor.useScriptFirstFrameForPrimary(true, true));
        assertFalse(AiVideoImageProcessor.useScriptFirstFrameForPrimary(false, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODEL_WEAR", "PRODUCT_ONLY"})
    void roomLightingPreservesExplicitNightScenesAndTheirExistingLightSources(String mode) {
        shots = ReflectionTestUtils.invokeMethod(processor, "readShots", """
                {"shots":[{"sequence":1,"sceneDescription":"夜晚卧室，右侧床头灯照明",
                "imagePrompt":{"prompt":"保留夜景与原有墙面，背景偏暗"}}]}
                """);
        String prompt = primary(task(mode, "SCRIPT_ORIGINAL"), false);

        assertTrue(prompt.contains("夜晚卧室，右侧床头灯照明"));
        assertTrue(prompt.contains("保留夜景与原有墙面，背景偏暗"));
        assertNaturalExposure(prompt);
        assertTrue(prompt.contains("脚本明确为夜景时保留夜景和原有光源"));
        assertTrue(prompt.contains("不强行加入白昼日光"));
    }

    @ParameterizedTest
    @CsvSource({"SCRIPT_ORIGINAL,true", "SCRIPT_ORIGINAL,false", "AI_DESIGNED,true", "AI_DESIGNED,false"})
    void storyboardBackgroundModeIsIndependentOfWhetherRealFramesExist(String background, boolean frames) {
        var task = task("MODEL_WEAR", background);
        String prompt = ReflectionTestUtils.invokeMethod(processor, "storyboardIdentityTransferPrompt",
                task, item(task), shots, frames);
        assertNaturalExposure(prompt);
        assertNaturalFabric(prompt);
        assertPhotographicEdges(prompt);
        assertTrue(prompt.contains("【动作与外观隔离 V1】"));
        assertTrue(prompt.contains("不提供任何皮肤、材质或绘画笔触"));
        assertTrue(prompt.contains("手臂、手腕、手掌与每根可见手指"));
        assertTrue(prompt.contains("不能只换脸和衣服"));
        assertTrue(prompt.contains("不能只把灰色占位手涂成肤色"));
        assertTrue(prompt.contains("即使参考图3已有黑边、线稿或平涂"));
        assertTrue(prompt.contains("禁止描摹其黑色边线"));
        assertTrue(prompt.contains("不沿用参考图的欠曝"));
        assertFalse(prompt.contains("不得反过来修改背景去迁就人物"));
        assertFalse(prompt.contains("曝光、白平衡和锐度参数"));
        if ("SCRIPT_ORIGINAL".equals(background)) {
            assertFalse(prompt.contains("当前任务选择 AI 自动搭配背景"));
            assertTrue(prompt.contains(frames ? "参考图3来自原视频真实抽帧"
                    : "当前任务选择保留脚本原背景，但没有可用的真实抽帧母版"));
        } else {
            assertTrue(prompt.contains("当前任务选择 AI 自动搭配背景"));
            assertFalse(prompt.contains("第 01–09 格的背景、动作和机位均取参考图3"));
        }
    }

    @ParameterizedTest
    @CsvSource({"SCRIPT_ORIGINAL,true", "SCRIPT_ORIGINAL,false", "AI_DESIGNED,true", "AI_DESIGNED,false"})
    void productStoryboardsAndGeneratedGuidesAlsoCorrectDarkReferences(String background, boolean frames) {
        var task = task("PRODUCT_ONLY", background);
        String product = ReflectionTestUtils.invokeMethod(processor, "productStoryboardGridPrompt",
                task, item(task), shots, frames);
        String guide = ReflectionTestUtils.invokeMethod(processor, "storyboardGridPrompt", task, shots);
        assertNaturalExposure(product);
        assertNaturalFabric(product);
        assertPhotographicEdges(product);
        assertNaturalExposure(guide);
        assertFalse(guide.contains("【服装自然质感 V1】"));
        assertTrue(guide.contains("不复制偏暗曝光"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCRIPT_ORIGINAL", "AI_DESIGNED"})
    void generatedPoseGuidesUseSolidShadingWithoutSuggestingLineArt(String background) {
        String prompt = ReflectionTestUtils.invokeMethod(processor, "storyboardGridPrompt",
                task("MODEL_WEAR", background), shots);

        assertTrue(prompt.contains("【实体动作底图 V2】"));
        assertTrue(prompt.contains("无脸、无身份的中性灰哑光实体人体模型"));
        assertTrue(prompt.contains("手掌和手指必须是有真实体积、连续明暗与自然遮挡的完整实体表面"));
        assertTrue(prompt.contains("边缘不加描线"));
        assertTrue(prompt.contains("禁止线稿、素描、漫画、二维剪影、骨架线、关节标记、黑色描边"));
        assertTrue(prompt.contains("不能保留占位身体像素"));
        assertTrue(prompt.contains("不穿带有颜色、图案、文字或款式特征的具体服装"));
        assertFalse(prompt.contains("或简洁写实的人体姿态轮廓"));
        assertFalse(prompt.contains("后续视频会用独立模特图替换该人体"));
        assertFalse(prompt.contains("【真实摄影边缘 V1】"));
        assertNaturalExposure(prompt);
    }

    @Test void ordinaryPosesDoNotAcquireGripWrinkles() {
        String primary = primary(task("PRODUCT_ONLY", "AI_DESIGNED"), false);
        String grip = ReflectionTestUtils.invokeMethod(processor, "fabricGripPhysicsRequirement",
                shots.getFirst());

        assertNaturalFabric(primary);
        assertEquals("", grip);
        assertFalse(primary.contains("放射状张力褶皱"));
    }

    @Test void explicitGripKeepsLocalTensionWithoutWrinklingTheEntireGarment() {
        shots = ReflectionTestUtils.invokeMethod(processor, "readShots", """
                {"shots":[{"sequence":1,"motionDescription":"单手捏住衣角，轻轻提起一角"}]}
                """);
        String prompt = primary(task("PRODUCT_ONLY", "SCRIPT_ORIGINAL"), false);

        assertNaturalFabric(prompt);
        assertTrue(prompt.contains("未被支撑的布料受重力向下垂落"));
        assertTrue(prompt.contains("与原面料厚度和挺度相符的少量放射状张力褶皱"));
        assertTrue(prompt.contains("远离受力点的布面保持自然舒展"));
        assertTrue(prompt.contains("不扩散成全件密集碎皱"));
        assertTrue(prompt.contains("只约束脚本明确发生提拉的当前格"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT_ONLY", "PARTIAL_INTERACT", "CAST_PRODUCT"})
    void policyChangesDoNotDeleteOrRegenerateAlreadySavedProductImages(String mode) throws Exception {
        var task = task(mode, "SCRIPT_ORIGINAL");
        var item = item(task);
        byte[] original = {1, 2, 3};
        Path file = directory.resolve("existing.png");
        Files.write(file, original);
        var stored = List.of("PRODUCT_SCENE_IMAGE", "STORYBOARD_GRID", "STORYBOARD_FIRST_FRAME").stream()
                .map(type -> new AiVideoTaskAsset("org", task.getId(), item.getId(), type, null,
                        file.toString(), "image/png", original.length, "older prompt", "existing-model")).toList();
        when(assets.selectList(any())).thenReturn(stored);

        ReflectionTestUtils.invokeMethod(processor, "generateItem", task, item, shots, null, true);

        assertEquals("IMAGE_READY", item.getStatus());
        assertArrayEquals(original, Files.readAllBytes(file));
        verify(assets).selectList(any());
        verifyNoMoreInteractions(assets);
        verifyNoInteractions(models);
    }

    private String primary(AiVideoTask task, boolean frame) {
        return ReflectionTestUtils.invokeMethod(processor,
                "MODEL_WEAR".equals(task.getGenerationMode()) ? "modelWearPrompt" : "productScenePrompt",
                task, item(task), shots.getFirst(), frame);
    }

    private static void assertNaturalExposure(String prompt) {
        assertNotNull(prompt);
        assertTrue(prompt.contains("【自然明亮曝光 V2】"));
        assertTrue(prompt.contains("【背景采光优先】"));
        assertTrue(prompt.contains("重点提亮房间和背景的环境光"));
        assertTrue(prompt.contains("墙面、家具、地面和房间深处都有柔和照明"));
        assertTrue(prompt.contains("避免人亮背景暗、墙面灰闷或远处角落压黑"));
        assertTrue(prompt.contains("日间或未指定昼夜的普通室内"));
        assertTrue(prompt.contains("充足的大面积漫射自然日光与墙面反射补光"));
        assertTrue(prompt.contains("阴影边缘柔和、暗部可辨"));
        assertTrue(prompt.contains("保留真实接触阴影、材质纹理和空间层次"));
        assertTrue(prompt.contains("人物和商品保持正常曝光，不整体拉高曝光"));
        assertTrue(prompt.contains("浅色衣服保留本色、织纹和印花细节，深色商品不提灰"));
        assertTrue(prompt.contains("不凭空增添或移动门窗"));
        assertTrue(prompt.contains("不把原墙面刷成白色"));
        assertTrue(prompt.contains("不强行加入白昼日光"));
        assertTrue(prompt.contains("允许符合场景的柔和自然采光、漫射窗光和合理补光"));
        assertTrue(prompt.contains("不得改变场景类型、昼夜设定、商品本色"));
        assertTrue(prompt.contains("不得把皮肤漂白"));
        assertTrue(prompt.contains("避免昏暗、压黑、过曝"));
    }

    private static void assertNaturalFabric(String prompt) {
        assertTrue(prompt.contains("【服装自然质感 V1】"));
        assertTrue(prompt.contains("正常整洁、自然舒展"));
        assertTrue(prompt.contains("面料厚度、织物密度、遮盖性、挺度和垂感以原商品为准"));
        assertTrue(prompt.contains("只在实际受力、弯曲或接触位置保留少量柔和褶皱"));
        assertTrue(prompt.contains("不额外添加密集碎皱、深折痕"));
        assertTrue(prompt.contains("保留原商品自带的百褶、压褶、纹理和做旧设计"));
        assertTrue(prompt.contains("不得额外新增磨损、起球、褪色、破损或洗旧质感"));
        assertTrue(prompt.contains("原本不透的衣料必须保持不透"));
        assertTrue(prompt.contains("原商品本身的薄纱、网眼或透视设计则按原样保留"));
        assertTrue(prompt.contains("不擅自加厚"));
        assertFalse(prompt.contains("连续褶皱"));
    }

    private static void assertPhotographicEdges(String prompt) {
        assertTrue(prompt.contains("【真实摄影边缘 V1】"));
        assertTrue(prompt.contains("保留正常接触阴影，不沿外缘额外画线"));
        assertTrue(prompt.contains("禁止给实体添加黑色描边"));
        assertTrue(prompt.contains("商品原有印花、插画、文字、Logo 内的黑线"));
        assertTrue(prompt.contains("真实缝线、滚边、头发和自然暗部必须按原样保留"));
        assertTrue(prompt.contains("不能统一去黑或模糊边缘"));
        assertTrue(prompt.contains("印花的漫画笔触只属于布面图案"));
    }

    private static AiVideoTask task(String mode, String background) {
        return new AiVideoTask("org", "lighting", "script", 1, "script", SCRIPT, "菲律宾", mode,
                background, "服装", "按分镜", "按分镜", false, "9:16", "720p",
                false, false, "{}", "{}", "user");
    }

    private AiVideoTaskItem item(AiVideoTask task) {
        return new AiVideoTaskItem("org", task.getId(), "shirt", "sku", "source.png",
                directory.resolve("source.png").toString(), "image/png", 3);
    }
}
