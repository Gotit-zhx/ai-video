package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiVideoImageProcessorTest {

    @Test
    void recognizesLegacyStaticModelFirstFrame() {
        AiVideoTaskAsset asset = new AiVideoTaskAsset("org", "task", "item",
                "STORYBOARD_FIRST_FRAME", 1, "old.png", "image/png", 1,
                "复用 4K AI 模特图作为视频第 01 分镜，不增加模型调用", "model");

        assertTrue(AiVideoImageProcessor.isLegacyModelFirstFrame(asset));
    }

    @Test
    void keepsStoryboardDerivedFirstFrame() {
        AiVideoTaskAsset asset = new AiVideoTaskAsset("org", "task", "item",
                "STORYBOARD_FIRST_FRAME", 1, "frame.png", "image/png", 1,
                "从最终 4K 九宫格第 01 格自动裁切", "model");

        assertFalse(AiVideoImageProcessor.isLegacyModelFirstFrame(asset));
    }

    @Test
    void completingImagesDoesNotDowngradeAnExistingVideo() {
        AiVideoTaskItem item = item();

        AiVideoImageProcessor.completeImages(item, true);

        assertEquals("VIDEO_READY", item.getStatus());
    }

    @Test
    void completingImagesWithoutVideoMarksImagesReady() {
        AiVideoTaskItem item = item();

        AiVideoImageProcessor.completeImages(item, false);

        assertEquals("IMAGE_READY", item.getStatus());
    }

    private static AiVideoTaskItem item() {
        return new AiVideoTaskItem("org", "task", "item", "sku",
                "source.png", "C:/tmp/source.png", "image/png", 1);
    }
    @Test
    void cropsTopLeftStoryboardCellAsFirstFrame() {
        BufferedImage storyboard = new BufferedImage(300, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = storyboard.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 100, 200);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(100, 0, 200, 600);
        } finally {
            graphics.dispose();
        }

        BufferedImage firstFrame = AiVideoImageProcessor.cropFirstStoryboardCell(storyboard);

        assertEquals(100, firstFrame.getWidth());
        assertEquals(200, firstFrame.getHeight());
        assertEquals(Color.RED.getRGB(), firstFrame.getRGB(50, 100));
    }

    @Test
    void closerPortraitCompositionAllocatesEnoughPixelsToFace() throws Exception {
        String requirement = privateStaticString("CLOSE_PORTRAIT_COMPOSITION_REQUIREMENT");

        assertTrue(requirement.contains("推进约二分之一距离"));
        assertTrue(requirement.contains("约 575 像素"));
        assertTrue(requirement.contains("膝盖、小腿、双脚和鞋子必须位于画面之外且不可见"));
        assertTrue(requirement.contains("不能为了保留双脚或展示整套穿搭而缩小人物"));
        assertFalse(requirement.contains("双脚完整入镜"));
    }

    @Test
    void storyboardGridUsesCloserFramingInsteadOfMandatoryFullBody() throws Exception {
        String requirement = privateStaticString("GRID_FACE_PIXEL_REQUIREMENT");

        assertTrue(requirement.contains("约 190 像素"));
        assertTrue(requirement.contains("膝盖、小腿、双脚和鞋子不可见"));
        assertFalse(requirement.contains("鞋底全部可见"));
    }

    @Test
    void fabricRequirementIncludesGravityAndDrapeWhenAClothingCornerIsLifted() throws Exception {
        String requirement = privateStaticString("FABRIC_AND_PRODUCT_REALISM_REQUIREMENT");

        assertTrue(requirement.contains("未被支撑的布料受重力向下垂落"));
        assertTrue(requirement.contains("放射状张力褶皱"));
        assertTrue(requirement.contains("禁止整片衣服像纸板"));
    }

    @Test
    void productVisibilityFollowsTheShotInsteadOfForcingTheFirstFrame() {
        assertFalse(AiVideoImageProcessor.shotTextShowsProduct(
                "无人物，画面从贴地近景开始，中线分隔旧地面与处理后地面"));
        assertFalse(AiVideoImageProcessor.shotTextShowsProduct(
                "商品不出镜，只展示空房间和地面测试"));
        assertTrue(AiVideoImageProcessor.shotTextShowsProduct(
                "当前任务商品正面对镜定住，商品包装清晰可见"));
    }

    @Test
    void primaryImageNeverReceivesTheScriptFrame() {
        assertFalse(AiVideoImageProcessor.useScriptFirstFrameForPrimary(false, true));
        assertFalse(AiVideoImageProcessor.useScriptFirstFrameForPrimary(false, false));
        assertFalse(AiVideoImageProcessor.useScriptFirstFrameForPrimary(true, true));
        assertFalse(AiVideoImageProcessor.useScriptFirstFrameForPrimary(true, false));
    }

    @Test
    void productFunctionAnalysisOnlyRunsForDailyUseCategory() {
        assertTrue(ProductFunctionAnalysisService.isDailyUseCategory("日用品"));
        assertTrue(ProductFunctionAnalysisService.isDailyUseCategory(" 日用品 "));
        assertFalse(ProductFunctionAnalysisService.isDailyUseCategory("家居"));
        assertFalse(ProductFunctionAnalysisService.isDailyUseCategory("通用商品"));
        assertFalse(ProductFunctionAnalysisService.isDailyUseCategory(null));
    }

    @Test
    void pipelineRetryDoesNotWaitForOptionalProductFunctionAnalysis() {
        assertTrue(AiVideoImageProcessor.shouldRunProductFunctionAnalysis(false, false));
        assertFalse(AiVideoImageProcessor.shouldRunProductFunctionAnalysis(false, true));
        assertFalse(AiVideoImageProcessor.shouldRunProductFunctionAnalysis(true, false));
    }

    @Test
    void productFunctionAnalysisReceivesAllScriptFunctionSignals() {
        ProductFunctionAnalysisService service = new ProductFunctionAnalysisService(
                null, null, new ObjectMapper(), "http://localhost:8090", "test-token");

        String context = service.functionalActionContext("""
                {"shots":[{"sequence":1,"title":"打开瓶盖","sceneDescription":"将药液倒入喷壶并加水稀释",
                "motionDescription":"手部旋开瓶盖后缓慢倾倒","narration":"旁白：稀释后均匀喷洒"}]}
                """);

        assertTrue(context.contains("打开瓶盖"));
        assertTrue(context.contains("倒入喷壶"));
        assertTrue(context.contains("旋开瓶盖"));
        assertTrue(context.contains("稀释后均匀喷洒"));
    }

    @Test
    void transientAndNoAssetPolicyFailuresAreEligibleForAutomaticImageRetry() {
        assertTrue(AiVideoImageProcessor.retryableImageConcurrencyFailure(
                "并发限制：图片模型当前并发已满"));
        assertTrue(AiVideoImageProcessor.retryableImageConcurrencyFailure(
                "Concurrency limit exceeded for user, please retry later"));
        assertFalse(AiVideoImageProcessor.retryableImageConcurrencyFailure(
                "版权限制：素材包含受版权保护的角色"));
        assertTrue(AiVideoImageProcessor.retryableImageTransientFailure("fetch failed"));
        assertTrue(AiVideoImageProcessor.retryableImageTransientFailure(
                "网络超时：图片模型长时间未返回响应"));
        assertTrue(AiVideoImageProcessor.retryableImageTransientFailure(
                "I/O error on POST request: HTTP/1.1 header parser received no bytes"));
        assertTrue(AiVideoImageProcessor.retryableImageTransientFailure(
                "图片模型调用失败：image generation produced no asset：该提示可能违反了我们的内容政策"));
        assertTrue(AiVideoImageProcessor.retryableImageTransientFailure(
                "内容策略可能误判：图片模型未产出图片，已快速重试 3 次，系统将继续后台自动重试"));
        assertTrue(AiVideoImageProcessor.retryableImageTransientFailure(
                "模型已生成图片，结果地址已保存；下载失败，系统将继续重试同一地址"));
        assertFalse(AiVideoImageProcessor.retryableImageTransientFailure(
                "版权限制：素材包含受版权保护的角色"));
        assertTrue(AiVideoImageProcessor.retryablePipelineImageFailure("FAILED",
                "模型已生成图片，结果地址已保存；下载失败，系统将继续重试同一地址"));
        assertTrue(AiVideoImageProcessor.retryablePipelineImageFailure("VIDEO_FAILED",
                "图片生成失败：图片模型调用失败：Concurrency limit exceeded for user, please retry later"));
        assertTrue(AiVideoImageProcessor.retryablePipelineImageFailure("VIDEO_FAILED",
                "图片生成失败：fetch failed"));
        assertTrue(AiVideoImageProcessor.retryablePipelineImageFailure("VIDEO_FAILED",
                "图片生成失败：image generation produced no asset：该提示可能违反了我们的内容政策"));
        assertFalse(AiVideoImageProcessor.retryablePipelineImageFailure("VIDEO_FAILED",
                "视频厂商并发限制：Concurrency limit exceeded for user, please retry later"));
    }

    @Test
    void videoConcurrencyFailuresRemainEligibleForScheduledRetry() {
        assertTrue(AiVideoGenerationService.retryableVideoConcurrencyFailure(
                "Concurrency limit exceeded for user, please retry later"));
        assertTrue(AiVideoGenerationService.retryableVideoConcurrencyFailure(
                "厂商拒绝请求（429）"));
        assertFalse(AiVideoGenerationService.retryableVideoConcurrencyFailure(
                "copyrighted character is not allowed"));
    }

    @Test
    void skipsDuplicateGenerationWhileTheSameTaskIsAlreadyActive() throws Exception {
        var taskMapper = mock(com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper.class);
        CountDownLatch firstGenerationEntered = new CountDownLatch(1);
        CountDownLatch allowFirstGenerationToFinish = new CountDownLatch(1);
        when(taskMapper.selectById("task-1")).thenAnswer(invocation -> {
            firstGenerationEntered.countDown();
            assertTrue(allowFirstGenerationToFinish.await(2, TimeUnit.SECONDS));
            return null;
        });
        AiVideoImageProcessor processor = new AiVideoImageProcessor(
                taskMapper,
                mock(com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper.class),
                mock(com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper.class),
                mock(com.yingdao.aicommerce.video.mapper.AiVideoPendingImageResultMapper.class),
                mock(com.yingdao.aicommerce.video.mapper.VideoScriptMapper.class),
                mock(com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper.class),
                mock(com.yingdao.aicommerce.saas.application.ModelConfigService.class),
                mock(ProductFunctionAnalysisService.class),
                mock(AiVideoGenerationService.class),
                new ObjectMapper(),
                Runnable::run,
                Runnable::run,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                "http://localhost:8090",
                "test-token",
                ".",
                ".");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> processor.generate("task-1"));
            assertTrue(firstGenerationEntered.await(2, TimeUnit.SECONDS));

            Future<?> duplicate = executor.submit(() -> processor.generate("task-1"));
            duplicate.get();
            verify(taskMapper, times(1)).selectById("task-1");

            allowFirstGenerationToFinish.countDown();
            first.get();
        }
    }

    private static String privateStaticString(String fieldName) throws Exception {
        Field field = AiVideoImageProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
