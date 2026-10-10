package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.application.provider.VideoGenerationProvider;
import com.yingdao.aicommerce.video.application.provider.VideoProviderRegistry;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiVideoIndividualFrameReferencesTest {
    @TempDir Path storage;
    private final AiVideoTaskAssetMapper assets = mock(AiVideoTaskAssetMapper.class);
    private final OssStorageService oss = mock(OssStorageService.class);

    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), AiVideoTaskAsset.class);
    }

    @Test void modeIsOptInAndDirectReferenceTasksKeepTheirExistingSemantics() {
        AiVideoTask task = task("GRID", false);
        assertThat(AiVideoGenerationService.usesIndividualFrames(task)).isFalse();
        assertThat(AiVideoGenerationService.usesIndividualFrames(task("INDIVIDUAL_FRAMES", true))).isFalse();
        assertThat(AiVideoGenerationService.usesIndividualFrames(task("INDIVIDUAL_FRAMES", false))).isTrue();
        AiVideoGenerationService.validateIndividualFrameRoute(task, connection("FAST", "model"));
        assertThatThrownBy(() -> AiVideoGenerationService.validateIndividualFrameRoute(task("INDIVIDUAL_FRAMES", false), connection("FAST", "minimax-h3")))
                .hasMessageContaining("仅支持 DAYAN/minimax-h3");
        assertThatThrownBy(() -> AiVideoGenerationService.validateIndividualFrameRoute(task("INDIVIDUAL_FRAMES", false), connection("DAYAN", "other-model")))
                .hasMessageContaining("未提交");
    }

    @Test void individualProtocolRetainsEntireCustomPromptAndStructureContractWithoutOldGridRoles() {
        String original = "【服装结构与穿法契约】固定双袖【逐镜头时间轴】01开始→09结束";
        String result = AiVideoGenerationService.individualFramesSafetyPrompt(original, "9:16");
        assertThat(result).endsWith(original).contains("九张独立单画面", "图01是完整首帧", "铺满9:16");
        assertThat(result).doesNotContain("图2是按", "图3锁定", "图2中同编号");
    }

    @Test void readsOriginalOssGridAndPublishesExactlyNineOrderedCropsWithoutGridOrTenthProductReference() throws Exception {
        AiVideoTaskAsset grid = asset("STORYBOARD_GRID", "oss://bucket/grid.png");
        when(assets.selectList(any())).thenReturn(List.of(grid, asset("STORYBOARD_FIRST_FRAME", "oss://bucket/old-first.png")));
        when(oss.isOssLocation("oss://bucket/grid.png")).thenReturn(true);
        byte[] image = StoryboardFrameExtractorTest.png(StoryboardFrameExtractorTest.grid());
        when(oss.download("oss://bucket/grid.png")).thenReturn(new OssStorageService.Download(new ByteArrayResource(image), image.length, "image/png"));
        List<Path> uploaded = new ArrayList<>();
        when(oss.publish(any(Path.class), eq("image/jpeg"), eq("video-storyboard-frames"))).thenAnswer(invocation -> {
            Path path = invocation.getArgument(0); uploaded.add(path); return "https://test.invalid/" + path.getFileName();
        });
        List<VideoGenerationProvider.Reference> result = references(service(), task("INDIVIDUAL_FRAMES", false), item(), connection("DAYAN", "minimax-h3"));
        assertThat(result).hasSize(9);
        for (int i = 0; i < 9; i++) assertThat(result.get(i).url()).isEqualTo(String.format("https://test.invalid/%02d.jpg", i + 1));
        assertThat(uploaded).hasSize(9);
        verify(oss, never()).signedUrl(anyString());
        verify(oss, never()).publish(any(Path.class), anyString(), eq("source-images"));
    }

    @Test void localGridUsesNineSignedUrlsWithoutOss() throws Exception {
        Path path = storage.resolve("grid.png");
        java.nio.file.Files.write(path, StoryboardFrameExtractorTest.png(StoryboardFrameExtractorTest.grid()));
        AiVideoTaskAsset grid = asset("STORYBOARD_GRID", path.toString());
        when(assets.selectList(any())).thenReturn(List.of(grid));
        var service = service();
        ReflectionTestUtils.setField(service, "ossStorage", null);
        var links = new VideoReferenceLinkService("https://media.test", false, "qa-secret", java.time.Duration.ofMinutes(5));
        ReflectionTestUtils.setField(service, "referenceLinks", links);
        var result = references(service, task("INDIVIDUAL_FRAMES", false), item(), connection("DAYAN", "minimax-h3"));
        assertThat(result).hasSize(9);
        for (int i = 0; i < 9; i++) assertThat(result.get(i).url()).contains("/storyboard-frame/" + (i + 1) + "/" + grid.getId(), "signature=");
        verifyNoInteractions(oss);
    }

    @Test void unsupportedRouteFailsBeforeDownloadingOrPublishingAnything() {
        when(assets.selectList(any())).thenReturn(List.of(asset("STORYBOARD_GRID", "oss://bucket/grid.png")));
        assertThatThrownBy(() -> references(service(), task("INDIVIDUAL_FRAMES", false), item(), connection("FAST", "minimax-h3")))
                .hasMessageContaining("仅支持");
        verifyNoInteractions(oss);
    }

    @Test void defaultGridStillPublishesFirstGridAndOriginalProductInTheSameOrder() throws Exception {
        when(assets.selectList(any())).thenReturn(List.of(asset("STORYBOARD_GRID", "oss://bucket/grid.png"), asset("STORYBOARD_FIRST_FRAME", "oss://bucket/first.png")));
        when(oss.isOssLocation(anyString())).thenReturn(true);
        when(oss.signedUrl(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(oss.publish(any(Path.class), anyString(), eq("source-images"))).thenReturn("original-product");
        var result = references(service(), task("GRID", false), item(), connection("DAYAN", "minimax-h3"));
        assertThat(result).extracting(VideoGenerationProvider.Reference::url)
                .containsExactly("oss://bucket/first.png", "oss://bucket/grid.png", "original-product");
        verify(oss, never()).download(anyString());
    }

    @Test void directReferenceOrderIsNotReplacedEvenIfIndividualModeWasStored() {
        AiVideoTaskAsset first = AiVideoTaskAsset.videoReference("org", "task", "item", 1, "oss://bucket/a.png", "image/png", 1, "PRIMARY", null, true);
        AiVideoTaskAsset second = AiVideoTaskAsset.videoReference("org", "task", "item", 2, "oss://bucket/b.png", "image/png", 1, "SCENE", null, false);
        when(assets.selectList(any())).thenReturn(List.of(second, first));
        when(oss.isOssLocation(anyString())).thenReturn(true);
        when(oss.signedUrl(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = references(service(), task("INDIVIDUAL_FRAMES", true), item(), connection("FAST", "model"));
        assertThat(result).extracting(VideoGenerationProvider.Reference::url).containsExactly("oss://bucket/a.png", "oss://bucket/b.png");
        verify(oss, never()).download(anyString());
    }

    @SuppressWarnings("unchecked") private AiVideoGenerationService service() {
        ObjectProvider<OssStorageService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oss);
        return new AiVideoGenerationService(mock(AiVideoTaskMapper.class), mock(AiVideoTaskItemMapper.class), assets,
                mock(AiVideoGenerationJobMapper.class), mock(VideoScriptMapper.class), mock(ModelConfigService.class),
                mock(VideoProviderRegistry.class), mock(VideoReferenceLinkService.class),
                new VideoReferenceDeliveryService(storage.toString()), provider, new ObjectMapper(), Runnable::run, 500);
    }
    private AiVideoTask task(String mode, boolean direct) {
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.getVideoReferenceMode()).thenReturn(mode);
        when(task.isDirectReferenceVideo()).thenReturn(direct);
        return task;
    }
    private AiVideoTaskItem item() {
        AiVideoTaskItem item = mock(AiVideoTaskItem.class);
        when(item.getId()).thenReturn("item");
        when(item.getSourceFilePath()).thenReturn(storage.resolve("product.png").toString());
        when(item.getSourceContentType()).thenReturn("image/png");
        return item;
    }
    private AiVideoTaskAsset asset(String type, String path) { return new AiVideoTaskAsset("org", "task", "item", type, 1, path, "image/png", 1, null, "image-model"); }
    private RuntimeModelConnection connection(String provider, String model) { return new RuntimeModelConnection("cfg", "https://test.invalid", model, "OPENAI_COMPATIBLE", provider, "fake"); }
    private List<VideoGenerationProvider.Reference> references(AiVideoGenerationService service, AiVideoTask task, AiVideoTaskItem item, RuntimeModelConnection connection) {
        return ReflectionTestUtils.invokeMethod(service, "references", task, item, connection, mock(VideoGenerationProvider.class));
    }
}
