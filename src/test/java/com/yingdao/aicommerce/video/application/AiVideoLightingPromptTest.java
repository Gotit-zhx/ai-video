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
            assertTrue(prompt.contains("【REALISM V5｜背景采光优先手机随拍任务】"));
            assertTrue(prompt.contains("【商品保真】"));
            assertTrue(prompt.contains("正确穿着母版"));
            assertTrue(prompt.contains("【中近景门禁版本：CLOSE_FRAMING_GATE_V1】"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODEL_WEAR", "PRODUCT_ONLY"})
    void originalBackgroundEvidenceDoesNotLockUnderexposure(String mode) {
        String prompt = primary(task(mode, "SCRIPT_ORIGINAL"), true);
        assertNaturalExposure(prompt);
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
        assertNaturalExposure(guide);
        assertTrue(guide.contains("不复制偏暗曝光"));
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
        assertTrue(prompt.contains("浅色衣服保留本色、褶皱和印花细节，深色商品不提灰"));
        assertTrue(prompt.contains("不凭空增添或移动门窗"));
        assertTrue(prompt.contains("不把原墙面刷成白色"));
        assertTrue(prompt.contains("不强行加入白昼日光"));
        assertTrue(prompt.contains("允许符合场景的柔和自然采光、漫射窗光和合理补光"));
        assertTrue(prompt.contains("不得改变场景类型、昼夜设定、商品本色"));
        assertTrue(prompt.contains("不得把皮肤漂白"));
        assertTrue(prompt.contains("避免昏暗、压黑、过曝"));
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
