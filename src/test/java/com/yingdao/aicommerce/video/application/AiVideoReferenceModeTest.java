package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import com.yingdao.aicommerce.video.web.AiVideoTaskController.UpdateVideoReferenceModeRequest;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiVideoReferenceModeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PlatformPrincipal principal = new PlatformPrincipal("user-1", "org-1", "company", "13800000000",
            "测试账号", "encoded", true, List.of("ADMIN"), List.of());

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "reference-mode-test"),
                AiVideoTask.class);
    }

    @Test
    void absentLegacyOrMalformedModeDefaultsToGrid() {
        for (String rules : Arrays.asList(null, "", "{}", "{\"videoRoute\":\"SPEED\"}", "not-json",
                "{\"videoReferenceMode\":\"unknown\"}")) {
            assertEquals("GRID", task(rules, false).getVideoReferenceMode());
        }
        assertEquals("INDIVIDUAL_FRAMES", task("{ \"videoReferenceMode\" : \"INDIVIDUAL_FRAMES\" }", false).getVideoReferenceMode());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void modePatchPreservesOtherRulesAndUpdatesOnlyThisIdleTaskRules() throws Exception {
        AiVideoTask task = task("{\"videoRoute\":\"SPEED\",\"useScriptReferenceVideo\":true,\"custom\":{\"keep\":[1,2]}}", false);
        AiVideoTaskMapper mapper = mapper(task, 1);
        AiVideoTask updated = service(mapper).updateVideoReferenceMode(task.getId(), "INDIVIDUAL_FRAMES", principal);

        assertEquals("INDIVIDUAL_FRAMES", updated.getVideoReferenceMode());
        var rules = JSON.readTree(updated.getGenerationRulesJson());
        assertEquals("SPEED", rules.path("videoRoute").asText());
        assertTrue(rules.path("useScriptReferenceVideo").asBoolean());
        assertEquals(JSON.readTree("{\"keep\":[1,2]}"), rules.path("custom"));
        assertEquals("DRAFT", updated.getStatus());
        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapper.capture());
        String conditions = wrapper.getValue().getSqlSegment();
        assertTrue(conditions.contains("organization_id"));
        assertTrue(conditions.contains("updated_at"));
        assertTrue(conditions.contains("status NOT IN"));
        String changes = wrapper.getValue().getSqlSet();
        assertTrue(changes.contains("generation_rules_json="));
        assertTrue(changes.contains("updated_at="));
        assertFalse(changes.contains("status="));
        verify(mapper, never()).updateById(any(AiVideoTask.class));

        assertEquals("GRID", service(mapper).updateVideoReferenceMode(task.getId(), "GRID", principal).getVideoReferenceMode());
        assertEquals("SPEED", JSON.readTree(task.getGenerationRulesJson()).path("videoRoute").asText());
    }

    @Test
    void invalidModeAndHttpRequestEnumAreRejected() {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            for (String mode : Arrays.asList(null, "", "grid", "OTHER", "INDIVIDUAL_FRAMES ")) {
                AiVideoTaskMapper mapper = mapper(task("{}", false), 1);
                assertThrows(BusinessException.class, () -> service(mapper).updateVideoReferenceMode("task-1", mode, principal));
                verify(mapper, never()).update(isNull(), any());
                assertFalse(validation.getValidator().validate(new UpdateVideoReferenceModeRequest(mode)).isEmpty());
            }
            assertTrue(validation.getValidator().validate(new UpdateVideoReferenceModeRequest("GRID")).isEmpty());
            assertTrue(validation.getValidator().validate(new UpdateVideoReferenceModeRequest("INDIVIDUAL_FRAMES")).isEmpty());
        }
    }

    @Test
    void imageAndVideoGenerationBlockReferenceModeEdits() {
        for (boolean video : List.of(false, true)) {
            AiVideoTask task = task("{}", false);
            if (video) task.startVideos("model", "model"); else task.start("model", "model");
            AiVideoTaskMapper mapper = mapper(task, 1);
            assertThrows(BusinessException.class, () -> service(mapper).updateVideoReferenceMode(task.getId(), "INDIVIDUAL_FRAMES", principal));
            assertEquals("{}", task.getGenerationRulesJson());
            verify(mapper, never()).update(isNull(), any());
        }
    }

    @Test
    void concurrentTaskChangeFailsInsteadOfOverwritingRunningState() {
        AiVideoTask task = task("{}", false);
        AiVideoTaskMapper mapper = mapper(task, 0);
        assertThrows(BusinessException.class, () -> service(mapper).updateVideoReferenceMode(task.getId(), "INDIVIDUAL_FRAMES", principal));
        verify(mapper, never()).updateById(any(AiVideoTask.class));
    }

    @Test
    void missingTaskMalformedRulesAndDirectReferenceModeAreRejected() {
        AiVideoTaskMapper missing = mapper(null, 1);
        assertThrows(BusinessException.class, () -> service(missing).updateVideoReferenceMode("missing", "GRID", principal));
        verify(missing, never()).update(isNull(), any());
        for (AiVideoTask task : List.of(task("not-json", false), task("{}", true))) {
            AiVideoTaskMapper mapper = mapper(task, 1);
            assertThrows(BusinessException.class, () -> service(mapper).updateVideoReferenceMode(task.getId(), "INDIVIDUAL_FRAMES", principal));
            verify(mapper, never()).update(isNull(), any());
        }
    }

    private AiVideoTask task(String rules, boolean direct) {
        return new AiVideoTask("org-1", "仅此任务", "script-1", 1, "脚本", "{\"shots\":[]}", "US", "MODEL_WEAR",
                "SCRIPT", "服装", "按脚本", "按脚本", false, "9:16", "768P", false, direct,
                rules, "{}", "user-1");
    }

    private AiVideoTaskMapper mapper(AiVideoTask task, int changes) {
        AiVideoTaskMapper mapper = mock(AiVideoTaskMapper.class);
        when(mapper.selectOne(any())).thenReturn(task);
        when(mapper.update(isNull(), any())).thenReturn(changes);
        return mapper;
    }

    @SuppressWarnings("unchecked")
    private AiVideoTaskService service(AiVideoTaskMapper mapper) {
        ObjectProvider<OssStorageService> oss = mock(ObjectProvider.class);
        return new AiVideoTaskService(mapper, mock(AiVideoTaskItemMapper.class), mock(AiVideoTaskAssetMapper.class),
                mock(VideoScriptMapper.class), mock(VideoScriptShotMapper.class), mock(AiVideoImageProcessor.class),
                JSON, mock(ProductFunctionAnalysisService.class), mock(AiVideoGenerationService.class), oss, "/tmp/reference-mode-test");
    }
}
