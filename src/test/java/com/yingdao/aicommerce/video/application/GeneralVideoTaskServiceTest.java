package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoPendingImageResultMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneralVideoTaskServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void validatesReferenceClipRangeAgainstOriginalVideoAndFifteenSecondLimit() {
        assertEquals(3.0, AiVideoTaskService.generalVideoClipWindow(3.0, 8.0, 20)[0]);
        assertEquals(8.0, AiVideoTaskService.generalVideoClipWindow(3.0, 8.0, 20)[1]);
        for (Double[] range : List.of(new Double[]{-1.0,5.0}, new Double[]{0.0,16.0},
                new Double[]{5.0,5.0},new Double[]{2.0,21.0},new Double[]{Double.NaN,5.0},
                new Double[]{20.01,20.03},new Double[]{null,5.0})) {
            assertThrows(BusinessException.class, () -> AiVideoTaskService.generalVideoClipWindow(range[0],range[1],20));
        }
    }

    @Test
    void acceptsNineImagesAndOneVideoAndRejectsASecondVideo() {
        var files = new ArrayList<MultipartFile>();
        for (int i = 0; i < 9; i++) files.add(image("image" + i + ".png"));
        var video = new MockMultipartFile("files", "ref.mp4", "video/mp4", new byte[] {1});
        files.add(video);
        var media = AiVideoTaskService.generalReferences(files);
        assertEquals(9, media.images().size());
        assertEquals(1, media.videos().size());
        files.add(video);
        assertThrows(BusinessException.class, () -> AiVideoTaskService.generalReferences(files));
        assertEquals(0, AiVideoTaskService.generalReferences(List.of(video)).images().size());
    }

    @Test
    void rejectsVideoOnUnsupportedSpeedRouteBeforeCreatingTask() {
        Fixture fixture = fixture();
        var video = new MockMultipartFile("files", "ref.mp4", "video/mp4", new byte[] {1});
        assertThrows(BusinessException.class, () -> fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, "@视频1", 5, "9:16", "720p", "SPEED", false), List.of(video), principal()));
        verify(fixture.taskMapper, never()).insert(any(AiVideoTask.class));
    }

    @Test
    void mentionNumbersMustResolveToUploadedMedia() {
        AiVideoTaskService.validateGeneralMentions("让 @图片2 模仿 @视频1", 2, 1);
        assertThrows(BusinessException.class, () -> AiVideoTaskService.validateGeneralMentions("@图片3", 2, 1));
        assertThrows(BusinessException.class, () -> AiVideoTaskService.validateGeneralMentions("@视频2", 2, 1));
        assertThrows(BusinessException.class, () -> AiVideoTaskService.validateGeneralMentions("@视频1", 2, 0));
        assertTrue(AiVideoGenerationService.generalReferencePrompt("@图片1 模仿 @视频1").contains("唯一的视频素材"));
    }

    @Test
    void createsGeneralTaskWithOriginalPromptAndOneToNineReferences() {
        Fixture fixture = fixture();
        String prompt = "  保留首尾空格的用户提示词\n第二行  ";
        List<MultipartFile> files = List.of(image("front.png"), image("detail.png"));

        AiVideoTask task = fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand("新品动态展示", prompt, 8,
                        "9:16", "1080p", "QUALITY", false), files, principal());

        assertEquals("GENERAL", task.getTaskType());
        assertEquals("新品动态展示", task.getName());
        assertEquals(8, task.getDurationSeconds());
        assertTrue(task.isDirectReferenceVideo());
        assertEquals("READY", task.getStatus());

        ArgumentCaptor<AiVideoTaskItem> itemUpdates = ArgumentCaptor.forClass(AiVideoTaskItem.class);
        verify(fixture.itemMapper, atLeastOnce()).updateById(itemUpdates.capture());
        assertEquals(prompt, itemUpdates.getAllValues().getLast().getVideoPromptText());

        ArgumentCaptor<AiVideoTaskAsset> assets = ArgumentCaptor.forClass(AiVideoTaskAsset.class);
        verify(fixture.assetMapper, org.mockito.Mockito.times(2)).insert(assets.capture());
        assertTrue(assets.getAllValues().stream()
                .allMatch(asset -> "VIDEO_REFERENCE_IMAGE".equals(asset.getAssetType())));
    }

    @Test
    void derivesReadableGeneralTaskNameFromPromptWithoutChangingOriginalPrompt() {
        Fixture fixture = fixture();
        String prompt = "  @front.png  \n！！！  柔和光线下展示新品包袋，，，   镜头缓慢推进  ";

        AiVideoTask task = fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand("  ", prompt, 6,
                        "9:16", "720p", "QUALITY", false), List.of(image("front.png")), principal());

        assertEquals("柔和光线下展示新品包袋， 镜头缓慢推进", task.getName());
        ArgumentCaptor<AiVideoTaskItem> itemUpdates = ArgumentCaptor.forClass(AiVideoTaskItem.class);
        verify(fixture.itemMapper, atLeastOnce()).updateById(itemUpdates.capture());
        assertEquals(prompt, itemUpdates.getAllValues().getLast().getVideoPromptText());
    }

    @Test
    void truncatesPromptDerivedNameToACompactUnicodeSafeTitle() {
        Fixture fixture = fixture();
        String prompt = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸春夏秋冬东西南北天地日月星辰山川湖海";

        AiVideoTask task = fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, prompt, 6,
                        "9:16", "720p", "QUALITY", false), List.of(image("reference.png")), principal());

        assertEquals(33, task.getName().codePointCount(0, task.getName().length()));
        assertTrue(task.getName().endsWith("…"));
        assertTrue(prompt.startsWith(task.getName().substring(0, task.getName().length() - 1)));
    }

    @Test
    void fallsBackToFirstReferenceFileNameAndThenGenericName() {
        Fixture fileNameFixture = fixture();
        AiVideoTask fileNamedTask = fileNameFixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, " @hero product.png ！！！ ", 6,
                        "9:16", "720p", "QUALITY", false), List.of(image("hero product.png")), principal());
        assertEquals("hero product", fileNamedTask.getName());

        Fixture genericFixture = fixture();
        MockMultipartFile unnamedImage = new MockMultipartFile("files", null, "image/png", pngBytes());
        AiVideoTask genericTask = genericFixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, " @@@ ！！！ ", 6,
                        "9:16", "720p", "QUALITY", false), List.of(unnamedImage), principal());
        assertEquals("通用生成", genericTask.getName());
    }

    @Test
    void rejectsMoreThanNineReferenceImagesBeforePersistingTask() {
        Fixture fixture = fixture();
        List<MultipartFile> files = new ArrayList<>();
        for (int index = 0; index < 10; index++) files.add(image("reference-" + index + ".png"));

        assertThrows(BusinessException.class, () -> fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, "prompt", 6,
                        "9:16", "720p", "QUALITY", false), files, principal()));

        verify(fixture.taskMapper, never()).insert(any(AiVideoTask.class));
    }

    @Test
    void acceptsFifteenSecondMaximumAndRejectsLongerDuration() {
        Fixture accepted = fixture();
        AiVideoTask task = accepted.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, "十五秒镜头", 15,
                        "9:16", "720p", "QUALITY", false), List.of(image("reference.png")), principal());
        assertEquals(15, task.getDurationSeconds());

        Fixture rejected = fixture();
        assertThrows(BusinessException.class, () -> rejected.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, "超长镜头", 16,
                        "9:16", "720p", "QUALITY", false), List.of(image("reference.png")), principal()));
        verify(rejected.taskMapper, never()).insert(any(AiVideoTask.class));
    }

    @Test
    void rejectsSvgAndForgedImageContentTypesBeforePersistingTask() {
        Fixture fixture = fixture();
        MultipartFile forgedPng = new MockMultipartFile("files", "payload.png", "image/png",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".getBytes());

        assertThrows(BusinessException.class, () -> fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, "prompt", 6,
                        "9:16", "720p", "QUALITY", false), List.of(forgedPng), principal()));
        assertThrows(BusinessException.class, () -> fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, "prompt", 6,
                        "9:16", "720p", "QUALITY", false),
                List.of(new MockMultipartFile("files", "payload.svg", "image/svg+xml",
                        "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes())), principal()));
        assertThrows(BusinessException.class, () -> fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, "prompt", 6,
                        "9:16", "720p", "QUALITY", false),
                List.of(image("payload.png", "image/jpeg", pngBytes())), principal()));

        verify(fixture.taskMapper, never()).insert(any(AiVideoTask.class));
    }

    @Test
    void acceptsOnlyWhitelistedImageSignaturesAndStoresCanonicalTypes() {
        Fixture fixture = fixture();
        List<MultipartFile> files = List.of(
                image("front.jpg", "image/jpeg", jpegBytes()),
                image("detail.png", "image/png", pngBytes()),
                image("motion.webp", "image/webp", webpBytes()),
                image("loop.gif", "image/gif", gifBytes()));

        fixture.service.createGeneral(new AiVideoTaskService.GeneralCreateCommand(null, "prompt", 6,
                "9:16", "720p", "QUALITY", false), files, principal());

        ArgumentCaptor<AiVideoTaskAsset> assets = ArgumentCaptor.forClass(AiVideoTaskAsset.class);
        verify(fixture.assetMapper, org.mockito.Mockito.times(4)).insert(assets.capture());
        assertEquals(List.of("image/jpeg", "image/png", "image/webp", "image/gif"),
                assets.getAllValues().stream().map(AiVideoTaskAsset::getContentType).toList());
    }

    @Test
    void rejectsGeneralReferenceBatchAboveNinetyMegabytes() {
        Fixture fixture = fixture();
        List<MultipartFile> files = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            files.add(sizedPng("reference-" + index + ".png", 19L * 1024 * 1024));
        }

        assertThrows(BusinessException.class, () -> fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand(null, "prompt", 6,
                        "9:16", "720p", "QUALITY", false), files, principal()));

        verify(fixture.taskMapper, never()).insert(any(AiVideoTask.class));
    }

    @Test
    void autoStartEntersExistingVideoSubmissionService() {
        Fixture fixture = fixture();

        AiVideoTask task = fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand("自动提交", "原样提示词", 6,
                        "9:16", "720p", "QUALITY", true), List.of(image("reference.png")), principal());

        verify(fixture.videoGenerationService).start(task);
    }

    @Test
    void removesLocalReferenceFilesWhenAutoStartFails() throws Exception {
        Fixture fixture = fixture();
        when(fixture.videoGenerationService.start(any())).thenThrow(new IllegalStateException("route unavailable"));

        assertThrows(BusinessException.class, () -> fixture.service.createGeneral(
                new AiVideoTaskService.GeneralCreateCommand("auto", "prompt", 6,
                        "9:16", "720p", "QUALITY", true), List.of(image("reference.png")), principal()));

        try (var paths = Files.walk(storageRoot)) {
            assertTrue(paths.noneMatch(Files::isRegularFile));
        }
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        AiVideoTaskMapper taskMapper = mock(AiVideoTaskMapper.class);
        AiVideoTaskItemMapper itemMapper = mock(AiVideoTaskItemMapper.class);
        AiVideoTaskAssetMapper assetMapper = mock(AiVideoTaskAssetMapper.class);
        AiVideoGenerationService videoGenerationService = mock(AiVideoGenerationService.class);
        AtomicReference<AiVideoTask> persistedTask = new AtomicReference<>();
        when(taskMapper.insert(any(AiVideoTask.class))).thenAnswer(invocation -> {
            persistedTask.set(invocation.getArgument(0));
            return 1;
        });
        when(taskMapper.selectOne(any())).thenAnswer(invocation -> persistedTask.get());
        when(videoGenerationService.start(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(itemMapper.selectCount(any())).thenReturn(1L);
        when(assetMapper.selectCount(any())).thenReturn(0L);
        ObjectProvider<OssStorageService> oss = mock(ObjectProvider.class);
        when(oss.getIfAvailable()).thenReturn(null);
        AiVideoTaskService service = new AiVideoTaskService(taskMapper, itemMapper, assetMapper,
                mock(AiVideoPendingImageResultMapper.class), mock(VideoScriptMapper.class),
                mock(VideoScriptShotMapper.class), mock(AiVideoImageProcessor.class), new ObjectMapper(),
                mock(ProductFunctionAnalysisService.class), videoGenerationService,
                mock(NarrationLocalizationService.class), oss, storageRoot.toString());
        return new Fixture(service, taskMapper, itemMapper, assetMapper, videoGenerationService);
    }

    private MockMultipartFile image(String name) {
        return image(name, "image/png", pngBytes());
    }

    private MockMultipartFile image(String name, String contentType, byte[] content) {
        return new MockMultipartFile("files", name, contentType, content);
    }

    private MultipartFile sizedPng(String name, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(size);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getOriginalFilename()).thenReturn(name);
        try {
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(pngBytes()));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        return file;
    }

    private byte[] jpegBytes() { return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}; }
    private byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
    }
    private byte[] webpBytes() { return new byte[]{'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'}; }
    private byte[] gifBytes() { return new byte[]{'G', 'I', 'F', '8', '9', 'a'}; }

    private PlatformPrincipal principal() {
        return new PlatformPrincipal("user-1", "org-1", "company", "13800000000",
                "测试账号", "encoded", true, List.of("OPERATOR"), List.of("video:task:manage"));
    }

    private record Fixture(AiVideoTaskService service, AiVideoTaskMapper taskMapper,
                           AiVideoTaskItemMapper itemMapper, AiVideoTaskAssetMapper assetMapper,
                           AiVideoGenerationService videoGenerationService) {}
}
