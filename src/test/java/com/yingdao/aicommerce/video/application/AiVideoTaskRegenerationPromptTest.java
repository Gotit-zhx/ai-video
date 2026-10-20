package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiVideoTaskRegenerationPromptTest {
    @TempDir Path storage;

    @Test
    void singleRegenerationPassesTheSavedCustomPromptToVideoStart() throws Exception {
        Fixture fixture = new Fixture(List.of("先前的提示词"));
        AiVideoTaskItem item = fixture.items.getFirst();
        String custom = "为这款上衣单独定制：每帧只有铺满屏幕的单个人物，按明确动作自然切镜。";
        fixture.service.updateVideoPrompt("task-1", item.getId(), custom, fixture.principal);

        assertSame(fixture.task, fixture.service.regenerateVideo("task-1", item.getId(), fixture.principal));

        assertEquals(custom, fixture.promptsAtStart.get().get(item.getId()));
        assertEquals(custom, item.getVideoPromptText());
        verify(fixture.generation).start(fixture.task);
        verifyNoInteractions(fixture.analysis);
        assertFalse(Files.exists(fixture.videos.getFirst()));
    }

    @Test
    void batchRegenerationPassesEachDistinctSavedPromptWithoutRefreshingLegacyText() throws Exception {
        String first = "第一款独立提示词：单人全画幅，展开上衣再转侧面。";
        String legacy = "提示词版本：PERSON_COMPACT_V13\n已保存的第二款修改：只执行拿取与近景细节。";
        Fixture fixture = new Fixture(List.of(first, legacy));

        fixture.service.regenerateVideos("task-1", fixture.principal);

        assertEquals(first, fixture.promptsAtStart.get().get(fixture.items.get(0).getId()));
        assertEquals(legacy, fixture.promptsAtStart.get().get(fixture.items.get(1).getId()));
        verify(fixture.generation).start(fixture.task);
        verify(fixture.itemMapper, never()).updateById(any(AiVideoTaskItem.class));
        verifyNoInteractions(fixture.analysis);
        assertTrue(fixture.videos.stream().noneMatch(Files::exists));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n "})
    void singleRegenerationPersistsADefaultBeforeStartOnlyWhenPromptIsBlank(String blank) throws Exception {
        Fixture fixture = new Fixture(Arrays.asList(blank));
        AiVideoTaskItem item = fixture.items.getFirst();

        fixture.service.regenerateVideo("task-1", item.getId(), fixture.principal);

        String submitted = fixture.promptsAtStart.get().get(item.getId());
        assertNotNull(submitted);
        assertTrue(submitted.startsWith("提示词版本：V11.9"));
        assertEquals(item.getVideoPromptText(), submitted);
        verify(fixture.itemMapper).updateById(item);
        verify(fixture.generation).start(fixture.task);
    }

    @Test
    void batchRegenerationFillsMissingPromptsAndPreservesTheEditedItemAtStart() throws Exception {
        String custom = "保存好的定制提示词，不能在批量重做时丢失。";
        Fixture fixture = new Fixture(Arrays.asList(custom, "  ", null));

        fixture.service.regenerateVideos("task-1", fixture.principal);

        assertEquals(custom, fixture.promptsAtStart.get().get(fixture.items.getFirst().getId()));
        verify(fixture.itemMapper, never()).updateById(fixture.items.getFirst());
        for (AiVideoTaskItem item : fixture.items.subList(1, fixture.items.size())) {
            String submitted = fixture.promptsAtStart.get().get(item.getId());
            assertNotNull(submitted);
            assertTrue(submitted.startsWith("提示词版本：V11.9"));
            verify(fixture.itemMapper).updateById(item);
        }
        verify(fixture.generation).start(fixture.task);
    }

    @Test void cannotReplaceReferenceFramesWhileProviderIsGenerating() throws Exception {
        Fixture fixture = new Fixture(List.of("saved prompt"));
        when(fixture.task.getStatus()).thenReturn("VIDEO_GENERATING");
        org.junit.jupiter.api.Assertions.assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                () -> fixture.service.regenerateStoryboard("task-1", fixture.items.getFirst().getId(), fixture.principal));
        org.junit.jupiter.api.Assertions.assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                () -> fixture.service.regenerateFirstFrame("task-1", fixture.items.getFirst().getId(), fixture.principal));
        verifyNoInteractions(fixture.analysis, fixture.generation);
        for (Path path : fixture.videos) assertTrue(Files.exists(path));
    }

    private class Fixture {
        final AiVideoTask task = mock(AiVideoTask.class);
        final AiVideoTaskItemMapper itemMapper = mock(AiVideoTaskItemMapper.class);
        final ProductFunctionAnalysisService analysis = mock(ProductFunctionAnalysisService.class);
        final AiVideoGenerationService generation = mock(AiVideoGenerationService.class);
        final List<AiVideoTaskItem> items = new ArrayList<>();
        final List<Path> videos = new ArrayList<>();
        final Map<String, String> persistedPrompts = new LinkedHashMap<>();
        final AtomicReference<Map<String, String>> promptsAtStart = new AtomicReference<>();
        final PlatformPrincipal principal = new PlatformPrincipal("user-1", "org-1", "company", "13800000000",
                "测试账号", "encoded", true, List.of("ADMIN"), List.of());
        final AiVideoTaskService service;

        @SuppressWarnings("unchecked")
        Fixture(List<String> prompts) throws Exception {
            when(task.getId()).thenReturn("task-1");
            when(task.getOrganizationId()).thenReturn("org-1");
            when(task.getScriptId()).thenReturn("script-1");
            when(task.getGenerationMode()).thenReturn("MODEL_WEAR");
            when(task.getStatus()).thenReturn("VIDEO_READY");
            when(task.getAspectRatio()).thenReturn("9:16");
            when(task.getScriptJson()).thenReturn("{\"shots\":[]}");
            AiVideoTaskMapper taskMapper = mock(AiVideoTaskMapper.class);
            when(taskMapper.selectOne(any())).thenReturn(task);
            VideoScript script = mock(VideoScript.class);
            when(script.getOrganizationId()).thenReturn("org-1");
            when(script.getPresentationMode()).thenReturn("MODEL_WEAR");
            VideoScriptMapper scriptMapper = mock(VideoScriptMapper.class);
            when(scriptMapper.selectById("script-1")).thenReturn(script);
            List<AiVideoTaskAsset> assets = new ArrayList<>();
            for (int index = 0; index < prompts.size(); index++) {
                AiVideoTaskItem item = new AiVideoTaskItem("org-1", "task-1", "商品" + index, "sku" + index,
                        "source.png", storage.resolve("source.png").toString(), "image/png", 10);
                item.updateVideoPrompt(prompts.get(index));
                item.videoComplete();
                items.add(item);
                persistedPrompts.put(item.getId(), prompts.get(index));
                Path video = Files.write(storage.resolve("video-" + index + ".mp4"), new byte[]{1, 2, 3});
                videos.add(video);
                assets.add(new AiVideoTaskAsset("org-1", "task-1", item.getId(), "VIDEO_RESULT", 1,
                        video.toString(), "video/mp4", 3, null, "model-1"));
            }
            when(itemMapper.selectOne(any())).thenReturn(items.getFirst());
            when(itemMapper.selectList(any())).thenReturn(items);
            when(itemMapper.updateById(any(AiVideoTaskItem.class))).thenAnswer(call -> {
                AiVideoTaskItem item = call.getArgument(0);
                persistedPrompts.put(item.getId(), item.getVideoPromptText());
                return 1;
            });
            AiVideoTaskAssetMapper assetMapper = mock(AiVideoTaskAssetMapper.class);
            when(assetMapper.selectList(any())).thenReturn(assets);
            when(analysis.applyToVideoPrompt(any(), anyString(), nullable(String.class)))
                    .thenAnswer(call -> call.getArgument(1));
            when(generation.start(task)).thenAnswer(call -> {
                // Snapshot separately persisted values at the real service's start boundary.
                promptsAtStart.set(new LinkedHashMap<>(persistedPrompts));
                return task;
            });
            ObjectProvider<OssStorageService> oss = mock(ObjectProvider.class);
            service = new AiVideoTaskService(taskMapper, itemMapper, assetMapper, mock(com.yingdao.aicommerce.video.mapper.AiVideoPendingImageResultMapper.class), scriptMapper,
                    mock(VideoScriptShotMapper.class), mock(AiVideoImageProcessor.class), new ObjectMapper(),
                    analysis, generation, mock(NarrationLocalizationService.class), oss, storage.toString());
        }
    }
}
