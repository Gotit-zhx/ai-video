package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.mapper.AiVideoPendingImageResultMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiVideoTaskRouteIsolationTest {

    @TempDir
    Path storageRoot;

    @Test
    void productTaskApiOperationsHideGeneralTasksBeforeTouchingTaskData() {
        AiVideoTask general = AiVideoTask.general("org-1", "通用任务", "{}", 6,
                "9:16", "720p", "{}", "{}", "user-1");
        Fixture fixture = fixture(general);
        PlatformPrincipal principal = principal();
        String taskId = general.getId();
        String itemId = "item-1";
        String assetId = "asset-1";
        MockMultipartFile file = new MockMultipartFile("file", "asset.png", "image/png", new byte[]{1});

        assertAll("旧商品任务 API 必须隐藏 GENERAL 任务",
                () -> assertProductTaskHidden(() -> fixture.service.detail(taskId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.upload(taskId, List.of(file), null, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.uploadMusic(taskId, file, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.startImages(taskId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.startVideos(taskId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.regenerateVideos(taskId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.regenerateVideo(taskId, itemId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.startGeneration(taskId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.regenerateStoryboard(taskId, itemId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.regenerateFirstFrame(taskId, itemId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.updateVideoPrompt(taskId, itemId, "prompt", principal)),
                () -> assertProductTaskHidden(() -> fixture.service.updateReferences(taskId, itemId, List.of(), principal)),
                () -> assertProductTaskHidden(() -> fixture.service.deleteReference(taskId, itemId, assetId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.delete(taskId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.source(taskId, itemId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.asset(taskId, assetId, principal)),
                () -> assertProductTaskHidden(() -> fixture.service.videoBatch(taskId, principal)));

        verifyNoInteractions(fixture.itemMapper, fixture.assetMapper, fixture.pendingImageResultMapper,
                fixture.processor, fixture.videoGenerationService);
    }

    @Test
    void generalTaskApiOperationsHideProductTasksBeforeTouchingTaskData() {
        AiVideoTask product = new AiVideoTask("org-1", "商品任务", "script-1", 1,
                "商品脚本", "{}", "泰国", "PRODUCT_ONLY", "ORIGINAL",
                "日用品", "室内", "手持", false, "9:16", "720p",
                false, false, "{}", "{}", "user-1");
        Fixture fixture = fixture(product);
        PlatformPrincipal principal = principal();

        assertAll("通用生成 API 只能处理 GENERAL 任务",
                () -> assertGeneralTaskHidden(() -> fixture.service.detailGeneral(product.getId(), principal)),
                () -> assertGeneralTaskHidden(() -> fixture.service.startGeneral(product.getId(), principal)),
                () -> assertGeneralTaskHidden(() -> fixture.service.generalAsset(product.getId(), "asset-1", principal)));

        verifyNoInteractions(fixture.itemMapper, fixture.assetMapper, fixture.pendingImageResultMapper,
                fixture.processor, fixture.videoGenerationService);
    }

    @Test
    void duplicateReferenceIdsReturnBadRequestBeforeAnyReferenceMutation() {
        AiVideoTask task = new AiVideoTask("org-1", "参考任务", "script-1", 1,
                "脚本", "{}", "PH", "MODEL_WEAR", "ORIGINAL", "服装", "室内", "展示",
                false, "9:16", "720p", false, true, "{}", "{}", "user-1");
        Fixture fixture = fixture(task);
        var item = new com.yingdao.aicommerce.video.domain.AiVideoTaskItem("org-1", task.getId(), "服装", "QA", "a.png", "a.png", "image/png", 1);
        when(fixture.itemMapper.selectOne(any())).thenReturn(item);
        var a = com.yingdao.aicommerce.video.domain.AiVideoTaskAsset.videoReference("org-1", task.getId(), item.getId(), 1, "a.png", "image/png", 1, null, null, true);
        var b = com.yingdao.aicommerce.video.domain.AiVideoTaskAsset.videoReference("org-1", task.getId(), item.getId(), 2, "b.png", "image/png", 1, null, null, false);
        when(fixture.assetMapper.selectList(any())).thenReturn(List.of(a,b));
        var error = assertThrows(BusinessException.class, () -> fixture.service.updateReferences(task.getId(), item.getId(), List.of(
                new AiVideoTaskService.ReferenceUpdate(a.getId(),1,null,null,true),
                new AiVideoTaskService.ReferenceUpdate(a.getId(),2,null,null,false)),principal()));
        assertEquals(HttpStatus.BAD_REQUEST,error.getStatus());
        org.mockito.Mockito.verify(fixture.assetMapper,org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.any(com.yingdao.aicommerce.video.domain.AiVideoTaskAsset.class));
    }

    private void assertProductTaskHidden(Executable operation) {
        BusinessException exception = assertThrows(BusinessException.class, operation);
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("AI 视频任务不存在", exception.getMessage());
    }

    private void assertGeneralTaskHidden(Executable operation) {
        BusinessException exception = assertThrows(BusinessException.class, operation);
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("通用生成任务不存在", exception.getMessage());
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(AiVideoTask task) {
        AiVideoTaskMapper taskMapper = mock(AiVideoTaskMapper.class);
        AiVideoTaskItemMapper itemMapper = mock(AiVideoTaskItemMapper.class);
        AiVideoTaskAssetMapper assetMapper = mock(AiVideoTaskAssetMapper.class);
        AiVideoPendingImageResultMapper pendingImageResultMapper = mock(AiVideoPendingImageResultMapper.class);
        AiVideoImageProcessor processor = mock(AiVideoImageProcessor.class);
        AiVideoGenerationService videoGenerationService = mock(AiVideoGenerationService.class);
        when(taskMapper.selectOne(any())).thenReturn(task);
        ObjectProvider<OssStorageService> oss = mock(ObjectProvider.class);
        when(oss.getIfAvailable()).thenReturn(null);
        AiVideoTaskService service = new AiVideoTaskService(taskMapper, itemMapper, assetMapper,
                pendingImageResultMapper, mock(VideoScriptMapper.class), mock(VideoScriptShotMapper.class),
                processor, new ObjectMapper(), mock(ProductFunctionAnalysisService.class), videoGenerationService,
                mock(NarrationLocalizationService.class), oss, storageRoot.toString());
        clearInvocations(itemMapper, assetMapper, pendingImageResultMapper, processor, videoGenerationService);
        return new Fixture(service, itemMapper, assetMapper, pendingImageResultMapper, processor,
                videoGenerationService);
    }

    private PlatformPrincipal principal() {
        return new PlatformPrincipal("user-1", "org-1", "company", "13800000000",
                "测试账号", "encoded", true, List.of("OPERATOR"), List.of("video:task:manage"));
    }

    private record Fixture(AiVideoTaskService service, AiVideoTaskItemMapper itemMapper,
                           AiVideoTaskAssetMapper assetMapper,
                           AiVideoPendingImageResultMapper pendingImageResultMapper,
                           AiVideoImageProcessor processor,
                           AiVideoGenerationService videoGenerationService) {}
}
