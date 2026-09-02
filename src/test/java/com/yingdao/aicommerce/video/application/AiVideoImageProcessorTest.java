package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiVideoImageProcessorTest {

    @TempDir
    Path tempDirectory;

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

    @Test
    void compactsLargeValidationImagesWithoutLosingVisibleTopology() throws Exception {
        BufferedImage source = new BufferedImage(1800, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.fillRect(300, 120, 1200, 660);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", png));

        ProductFunctionAnalysisService.ValidationImage compact =
                ProductFunctionAnalysisService.compactForValidation(png.toByteArray(), "image/png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(compact.bytes()));

        assertEquals("image/jpeg", compact.mimeType());
        assertNotNull(decoded);
        assertTrue(Math.max(decoded.getWidth(), decoded.getHeight()) <= 1280);
        assertTrue(compact.bytes().length < 500_000);
    }

    @Test
    void exposesCandidateValidationAsRealIntermediateProgress() {
        AiVideoTaskItem model = item();
        model.start();
        model.validationPending(false);
        assertEquals("GENERATING_MODEL", model.getStatus());
        assertEquals(8, model.getProgress());

        AiVideoTaskItem storyboard = item();
        storyboard.modelReady();
        storyboard.validationPending(true);
        assertEquals("GENERATING_STORYBOARD", storyboard.getStatus());
        assertEquals(88, storyboard.getProgress());
    }

    @Test
    void persistsPrivateCandidateAcrossRecoveryWithoutPublishingAnAsset() throws Exception {
        AiVideoImageProcessor processor = processor(tempDirectory);
        AiVideoTask task = new AiVideoTask(
                "org", "task", "script", 1, "script", "{}", "菲律宾", "MODEL_WEAR",
                "AI_DESIGNED", "服装", "按分镜", "按分镜", false, "9:16", "720p",
                false, false, "{}", "{}", "user");
        AiVideoTaskItem item = item();
        byte[] image = new byte[] {1, 2, 3, 4, 5};
        var candidate = new AiVideoImageProcessor.TopologyCandidate(
                "image/png", image, "image-model", "url", "frozen prompt", "revised");

        processor.saveTopologyCandidate(task, item, "MODEL_IMAGE", candidate);
        var recovered = processor.loadTopologyCandidate(task, item, "MODEL_IMAGE");

        assertNotNull(recovered);
        assertArrayEquals(image, recovered.bytes());
        assertEquals("frozen prompt", recovered.prompt());
        processor.discardTopologyCandidate(task, item, "MODEL_IMAGE");
        assertNull(processor.loadTopologyCandidate(task, item, "MODEL_IMAGE"));
    }

    private static AiVideoTaskItem item() {
        return new AiVideoTaskItem("org", "task", "item", "sku",
                "source.png", "C:/tmp/source.png", "image/png", 1);
    }

    private static AiVideoImageProcessor processor(Path storageRoot) {
        return new AiVideoImageProcessor(
                mock(com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper.class),
                mock(com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper.class),
                mock(com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper.class),
                mock(com.yingdao.aicommerce.video.mapper.AiVideoPendingImageResultMapper.class),
                mock(com.yingdao.aicommerce.video.mapper.VideoScriptMapper.class),
                mock(com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper.class),
                mock(com.yingdao.aicommerce.saas.application.ModelConfigService.class),
                mock(ProductFunctionAnalysisService.class),
                mock(AiVideoGenerationService.class),
                new ObjectMapper(), Runnable::run, Runnable::run,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                "http://localhost:8090", "test-token", storageRoot.toString(), storageRoot.toString());
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
        assertTrue(requirement.contains("覆盖脚本景别"));
        assertTrue(requirement.contains("远景、全景、全身、拉远"));
        assertTrue(requirement.contains("纯商品格采用填满主体区域的商品近景"));
        assertFalse(requirement.contains("才允许该格采用对应景别并显示鞋子"));
        assertFalse(requirement.contains("鞋底全部可见"));
    }

    @Test
    void fabricRequirementIncludesGravityAndDrapeWhenAClothingCornerIsLifted() throws Exception {
        String general = privateStaticString("FABRIC_AND_PRODUCT_REALISM_REQUIREMENT");
        String requirement = privateStaticString("FABRIC_GRIP_PHYSICS_REQUIREMENT");

        assertFalse(general.contains("唯一最高受力点"));
        assertTrue(requirement.contains("未被支撑的布料受重力向下垂落"));
        assertTrue(requirement.contains("放射状张力褶皱"));
        assertTrue(requirement.contains("禁止整片衣服像纸板"));
        assertTrue(requirement.contains("只约束脚本明确发生提拉的当前格"));
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
    void rawScriptFramesAreRejectedAsStoryboardModelInputs() {
        AiVideoTaskAsset rawGuide = new AiVideoTaskAsset(
                "org", "task", "item", "STORYBOARD_GUIDE", null, "/tmp/guide.png",
                "image/png", 10, "REAL_VIDEO_FRAMES_V1｜原视频九个分镜时间中点抽帧", "ffmpeg");
        AiVideoTaskAsset generatedGuide = new AiVideoTaskAsset(
                "org", "task", "item", "STORYBOARD_GUIDE", null, "/tmp/guide.png",
                "image/png", 10, "text-driven guide", "gpt-image-2");
        AiVideoTaskAsset motionGuide = new AiVideoTaskAsset(
                "org", "task", "item", "STORYBOARD_GUIDE", null, "/tmp/guide-v2.png",
                "image/png", 10, "SCRIPT_MOTION_FRAMES_V2｜原视频九个动作状态母版", "ffmpeg");

        assertTrue(AiVideoImageProcessor.isRawScriptFrameGuide(rawGuide));
        assertFalse(AiVideoImageProcessor.isRawScriptFrameGuide(generatedGuide));
        assertFalse(AiVideoImageProcessor.isRawScriptFrameGuide(null));
        assertTrue(AiVideoImageProcessor.isScriptMotionFrameGuide(motionGuide));
        assertFalse(AiVideoImageProcessor.isScriptMotionFrameGuide(rawGuide));
    }

    @Test
    void productFunctionAnalysisOnlyRunsForDailyUseCategory() {
        assertTrue(ProductFunctionAnalysisService.isDailyUseCategory("日用品"));
        assertTrue(ProductFunctionAnalysisService.isDailyUseCategory(" 日用品 "));
        assertFalse(ProductFunctionAnalysisService.isDailyUseCategory("家居"));
        assertFalse(ProductFunctionAnalysisService.isDailyUseCategory("通用商品"));
        assertFalse(ProductFunctionAnalysisService.isDailyUseCategory(null));
        assertTrue(ProductFunctionAnalysisService.isGarmentCategory("服装"));
        assertTrue(ProductFunctionAnalysisService.isGarmentCategory("女装/T恤"));
        assertFalse(ProductFunctionAnalysisService.isGarmentCategory("日用品"));
    }

    @Test
    void pipelineRetryDoesNotWaitForOptionalProductFunctionAnalysis() {
        assertTrue(AiVideoImageProcessor.shouldRunProductFunctionAnalysis(true, false, false));
        assertFalse(AiVideoImageProcessor.shouldRunProductFunctionAnalysis(true, true, false));
        assertFalse(AiVideoImageProcessor.shouldRunProductFunctionAnalysis(true, false, true));
        assertFalse(AiVideoImageProcessor.shouldRunProductFunctionAnalysis(false, false, false));
    }

    @Test
    void garmentTopologyContractKeepsBodySideAndViewProjectionSeparate() {
        ProductFunctionAnalysisService service = new ProductFunctionAnalysisService(
                null, null, new ObjectMapper(), "http://localhost:8090", "test-token");
        var task = new com.yingdao.aicommerce.video.domain.AiVideoTask(
                "org", "task", "script", 1, "script", "{}", "菲律宾", "MODEL_WEAR",
                "AI_DESIGNED", "服装", "按分镜", "按分镜", false, "9:16", "720p",
                false, false, "{}", "{}", "user");

        String block = service.promptBlock(task, """
                {"version":"GARMENT_TOPOLOGY_V1","garmentType":"非对称斜领短袖",
                "topologyClass":"ASYMMETRIC_ONE_SHOULDER","structureSymmetry":"INTRINSIC_ASYMMETRIC",
                "wearingSidePolicy":"FIXED_WEARER_SIDE","wearerRightShoulderState":"露出",
                "wearerLeftShoulderState":"覆盖","imageLeftSleeve":"短袖下落在上臂",
                "imageRightSleeve":"正常短袖","necklinePath":"从穿着者左肩斜向右肩下方",
                "supportElements":[],"viewpointProjectionRule":"转到背面后屏幕左右自然反转",
                "fixedStructures":["两只短袖始终存在"],"forbiddenChanges":["不得增加肩带"]}
                """);

        assertTrue(block.contains("服装结构与穿法契约"));
        assertTrue(block.contains("原图可见像素是最高真值"));
        assertTrue(block.contains("FIXED_WEARER_SIDE"));
        assertTrue(block.contains("两只短袖始终存在"));
        assertTrue(block.contains("背面后屏幕左右自然反转"));
        assertTrue(block.contains("不得增加肩带"));
        assertFalse(block.contains("结构类型：ASYMMETRIC_ONE_SHOULDER"));
        assertTrue(block.indexOf("原图画面左侧袖") < block.indexOf("穿着侧约束"));
        assertTrue(service.requiresTopologyValidation("""
                {"version":"GARMENT_TOPOLOGY_V1","asymmetrical":true,
                "topologyClass":"ASYMMETRIC_ONE_SHOULDER"}
                """));
        assertTrue(service.requiresTopologyValidation("""
                {"version":"GARMENT_TOPOLOGY_V1","asymmetrical":false,
                "topologyClass":"ROUND_NECK"}
                """));
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
    void topologyValidationRetriesTransientStreamFailuresWithoutRedrawing() {
        assertTrue(ProductFunctionAnalysisService.isTransientValidationFailure(
                "Stream ended without finish_reason"));
        assertTrue(ProductFunctionAnalysisService.isTransientValidationFailure(
                "upstream returned 502"));
        assertFalse(ProductFunctionAnalysisService.isTransientValidationFailure(
                "服装结构复核未通过：领口结构错误"));
    }

    @Test
    void topologyRedrawPromptPrioritizesRejectedCellsWithoutPromisingLocalEdits() {
        var validation = new ProductFunctionAnalysisService.GarmentValidation(false,
                "第03格补回左胸橙色圆环 Logo，第08格保持正面朝向",
                java.util.List.of("第03格 Logo 缺失", "第08格前后朝向不一致"), 0.92);

        String prompt = AiVideoImageProcessor.topologyCorrectionPrompt("原始九宫格提示", validation);

        assertTrue(prompt.startsWith("原始九宫格提示"));
        assertTrue(prompt.contains("定向纠错重绘"));
        assertTrue(prompt.contains("完整重生成一张图片"));
        assertTrue(prompt.contains("优先修正下面的失败项"));
        assertTrue(prompt.contains("不复制上一候选"));
        assertTrue(prompt.contains("不假设能够只改局部"));
        assertFalse(prompt.contains("只纠正下面指出的失败分镜"));
        assertFalse(prompt.contains("只修改失败格"));
        assertTrue(prompt.contains("第03格 Logo 缺失"));
        assertTrue(prompt.contains("第08格前后朝向不一致"));
        assertTrue(prompt.contains("第03格补回左胸橙色圆环 Logo"));
        assertTrue(prompt.contains("用户上传商品母版"));
        assertTrue(prompt.contains("不要重新分类"));
        assertTrue(prompt.contains("斜肩或露肩只代表领口路径不对称"));
        assertTrue(prompt.contains("必须保留两只袖子"));
        assertTrue(prompt.contains("不得为凑数量新增或复制肢体"));
        assertTrue(prompt.contains("一张完整九宫格图片"));
        assertTrue(prompt.contains("每个商品出镜格"));
        assertTrue(prompt.contains("仅在与商品母版可见像素和原提示不冲突时执行"));
    }

    @Test
    void topologyRedrawsAreBoundedToThreeBeforeContinuing() {
        assertEquals(3, AiVideoImageProcessor.MAX_TOPOLOGY_REDRAWS);
        assertEquals(4, AiVideoImageProcessor.MAX_TOPOLOGY_GENERATIONS);
    }

    @Test
    void topologyCandidateRankingUsesTotalViolationsAndRejectsMissingDetails() {
        var summarized = new ProductFunctionAnalysisService.GarmentValidation(false, "",
                java.util.List.of("问题一", "问题二", "问题三"), 0.9, 7);
        var missingDetails = new ProductFunctionAnalysisService.GarmentValidation(false, "",
                java.util.List.of(), 0.1, 0);

        assertEquals(7, AiVideoImageProcessor.topologyViolationCount(summarized));
        assertEquals(Integer.MAX_VALUE, AiVideoImageProcessor.topologyViolationCount(missingDetails));
        assertTrue(AiVideoImageProcessor.preferNewTopologyCandidate(7, 2));
        assertTrue(AiVideoImageProcessor.preferNewTopologyCandidate(2, 2));
        assertFalse(AiVideoImageProcessor.preferNewTopologyCandidate(2, 3));
        assertEquals(9, ProductFunctionAnalysisService.validationViolationCount(
                9, false, java.util.List.of("只列最关键问题")));
        assertEquals(Integer.MAX_VALUE, ProductFunctionAnalysisService.validationViolationCount(
                0, false, java.util.List.of()));
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
                "服装结构复核未通过（已完成 1 次定向纠错，不再自动重抽）：第03格 Logo 缺失"));
        assertTrue(AiVideoImageProcessor.retryableImageTransientFailure(
                "服装结构复核暂时失败，已私有保存本次候选图并将在后台继续复核：模型服务响应超时"));
        assertTrue(AiVideoImageProcessor.retryableImageTransientFailure(
                "服装结构复核暂时失败，已保留本次生图结果供后续继续复核：EOF reached while reading"));
        assertTrue(AiVideoImageProcessor.privateValidationRetryPending(
                "服装结构复核暂时失败，已私有保存本次候选图并将在后台继续复核：模型服务响应超时"));
        assertFalse(AiVideoImageProcessor.privateValidationRetryPending(
                "服装结构复核未通过：第08格商品身份不一致"));
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
                "图片生成失败：服装结构复核未通过：第08格商品身份不一致"));
        assertTrue(AiVideoImageProcessor.retryablePipelineImageFailure("VIDEO_FAILED",
                "图片生成失败：服装结构复核暂时失败，已保留本次生图结果供后续继续复核：EOF reached while reading"));
        assertTrue(AiVideoImageProcessor.retryablePipelineImageFailure("FAILED",
                "参考图片获取失败，请重新上传"));
        assertFalse(AiVideoImageProcessor.retryablePipelineImageFailure("VIDEO_FAILED",
                "视频厂商并发限制：Concurrency limit exceeded for user, please retry later"));
    }

    @Test
    void videoConcurrencyFailuresRemainEligibleForScheduledRetry() {
        assertTrue(AiVideoGenerationService.retryableVideoConcurrencyFailure(
                "Concurrency limit exceeded for user, please retry later"));
        assertTrue(AiVideoGenerationService.retryableVideoConcurrencyFailure(
                "厂商拒绝请求（429）"));
        assertTrue(AiVideoGenerationService.retryableVideoConcurrencyFailure(
                "参考图片获取失败，请重新上传"));
        assertFalse(AiVideoGenerationService.retryableVideoConcurrencyFailure(
                "copyrighted character is not allowed"));
    }

    @Test
    void preservesAProviderResultUrlAcrossRepeatedGatewayFailures() {
        var pending = new com.yingdao.aicommerce.video.domain.AiVideoPendingImageResult(
                "org-1", "task-1", "item-1", "MODEL_IMAGE", "hash",
                "https://provider.invalid/image", "image/png", "model-1", "url", null);
        for (int attempt = 0; attempt < 2; attempt++) pending.downloadStarted();
        pending.downloadFailed("RESULT_DOWNLOAD_HTTP_502 [upstream=502]");
        assertFalse(AiVideoImageProcessor.replaceUnavailablePendingImage(pending));
        pending.downloadStarted();
        pending.downloadFailed("RESULT_DOWNLOAD_HTTP_502 [upstream=502]");
        assertFalse(AiVideoImageProcessor.replaceUnavailablePendingImage(pending));
    }

    @Test
    void replacesOnlyPermanentResultFailuresAndPreservesTransportFailures() {
        var missing = new com.yingdao.aicommerce.video.domain.AiVideoPendingImageResult(
                "org-1", "task-1", "item-1", "MODEL_IMAGE", "hash",
                "https://provider.invalid/missing", "image/png", "model-1", "url", null);
        missing.downloadStarted();
        missing.downloadFailed("RESULT_DOWNLOAD_HTTP_404 [upstream=404]");
        assertTrue(AiVideoImageProcessor.replaceUnavailablePendingImage(missing));

        var timeout = new com.yingdao.aicommerce.video.domain.AiVideoPendingImageResult(
                "org-1", "task-1", "item-2", "MODEL_IMAGE", "hash",
                "https://provider.invalid/slow", "image/png", "model-1", "url", null);
        for (int attempt = 0; attempt < 3; attempt++) timeout.downloadStarted();
        timeout.downloadFailed("RESULT_DOWNLOAD_TIMEOUT");
        assertFalse(AiVideoImageProcessor.replaceUnavailablePendingImage(timeout));
        timeout.downloadStarted();
        timeout.downloadFailed("RESULT_DOWNLOAD_TIMEOUT");
        assertFalse(AiVideoImageProcessor.replaceUnavailablePendingImage(timeout));
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
