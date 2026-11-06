package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VideoScriptParallelAnalysisTest {
    @TempDir Path storage;

    private final VideoScriptMapper scripts = mock(VideoScriptMapper.class);
    private final VideoScriptShotMapper shots = mock(VideoScriptShotMapper.class);
    private final VideoScriptAnalysisProcessor processor = mock(VideoScriptAnalysisProcessor.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), VideoScript.class);
    }

    @Test
    void sameOwnerCanUploadAnotherVideoWhileEarlierScriptIsProcessing() throws Exception {
        VideoScript active = script("active", "org-a", "user-a");
        active.markAnalyzing("model", "model-id");
        when(scripts.selectOne(any())).thenReturn(active);
        VideoScriptService service = service();

        VideoScript first = service.createAnalysis(video(), "first", null, null, "MODEL_WEAR", false, principal("org-a", "user-a"));
        VideoScript second = service.createAnalysis(video(), "second", null, null, "MODEL_WEAR", false, principal("org-a", "user-a"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getStatus()).isEqualTo("QUEUED");
        assertThat(second.getStatus()).isEqualTo("QUEUED");
        assertThat(Files.readAllBytes(Path.of(first.getSourceFilePath()))).containsExactly(1, 2, 3);
        assertThat(Files.readAllBytes(Path.of(second.getSourceFilePath()))).containsExactly(1, 2, 3);
        verify(scripts, times(2)).insert(any(VideoScript.class));
        verify(processor).analyze(first.getId());
        verify(processor).analyze(second.getId());
        verify(scripts, never()).selectOne(any());
    }

    @Test
    void reanalysisCanStartWhileAnotherScriptExistsAndClearsOldTerminalState() {
        VideoScript ready = script("ready", "org-a", "user-a");
        ready.complete("summary", "tone", "{}");
        when(scripts.selectById("ready")).thenReturn(ready);
        when(scripts.update(isNull(), any(Wrapper.class))).thenReturn(1);

        VideoScript result = service().reanalyze("ready", principal("org-a", "user-a"));

        assertThat(result.getStatus()).isEqualTo("QUEUED");
        assertThat(result.getVersion()).isEqualTo(2);
        verify(processor).analyze("ready");
        verify(scripts, never()).selectOne(any());
        ArgumentCaptor<Wrapper<VideoScript>> claim = wrapperCaptor();
        verify(scripts).update(isNull(), claim.capture());
        assertThat(claim.getValue().getSqlSegment()).contains("status", "updated_at", "organization_id", "created_by");
        assertThat(((LambdaUpdateWrapper<VideoScript>) claim.getValue()).getSqlSet())
                .contains("completed_at", "error_message");
    }

    @Test
    void staleConcurrentRetryCannotEnqueueSameTaskTwice() {
        VideoScript firstSnapshot = script("failed", "org-a", "user-a");
        firstSnapshot.fail("upstream error");
        VideoScript secondSnapshot = script("failed", "org-a", "user-a");
        secondSnapshot.fail("upstream error");
        when(scripts.selectById("failed")).thenReturn(firstSnapshot, secondSnapshot);
        // The database condition permits only the first update from the observed terminal state.
        when(scripts.update(isNull(), any(Wrapper.class))).thenReturn(1, 0);

        service().retry("failed", principal("org-a", "user-a"));
        assertThatThrownBy(() -> service().retry("failed", principal("org-a", "user-a")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("状态已更新");

        verify(processor, times(1)).analyze("failed");
    }

    @Test
    void aRejectedQueueRetainsUploadAndMakesTaskRetryable() {
        doThrow(new RejectedExecutionException("full")).when(processor).analyze(anyString());

        assertThatThrownBy(() -> service().createAnalysis(
                video(), "queued", null, null, "MODEL_WEAR", false, principal("org-a", "user-a")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("任务和原视频已保存");

        ArgumentCaptor<VideoScript> inserted = ArgumentCaptor.forClass(VideoScript.class);
        verify(scripts).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(Path.of(inserted.getValue().getSourceFilePath())).exists();
        ArgumentCaptor<Wrapper<VideoScript>> update = wrapperCaptor();
        verify(scripts).update(isNull(), update.capture());
        LambdaUpdateWrapper<VideoScript> failure = (LambdaUpdateWrapper<VideoScript>) update.getValue();
        assertThat(failure.getSqlSegment()).contains("id", "status");
        assertThat(failure.getParamNameValuePairs().values()).contains("FAILED", "QUEUED");
    }

    @Test
    void retriesAndReanalysisRetainCompanyAndOwnerChecks() {
        VideoScript existing = script("script", "org-a", "user-a");
        existing.fail("failed");
        when(scripts.selectById("script")).thenReturn(existing);

        assertThatThrownBy(() -> service().retry("script", principal("org-b", "user-a")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service().retry("script", new PlatformPrincipal("user-b","org-a","org-a","13800000001","regular-user","password",false,List.of("USER"),List.of("video:script:view","video:script:manage"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权");
        assertThatThrownBy(() -> service().reanalyze("script", principal("org-b", "user-a")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service().reanalyze("script", new PlatformPrincipal("user-b","org-a","org-a","13800000001","regular-user","password",false,List.of("USER"),List.of("video:script:view","video:script:manage"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权");
        verify(scripts, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(processor);
    }

    @Test
    void duplicateWorkerDoesNotExtractOrCallModelAfterLosingDatabaseClaim() {
        VideoScript queued = script("queued", "org-a", "user-a");
        when(scripts.selectById("queued")).thenReturn(queued);
        when(scripts.update(isNull(), any(Wrapper.class))).thenReturn(0);
        VideoFrameExtractor frames = mock(VideoFrameExtractor.class);
        ModelConfigService models = mock(ModelConfigService.class);
        VideoScriptAnalysisProcessor actual = new VideoScriptAnalysisProcessor(
                scripts, shots, models, frames, mock(VideoCameraMotionAnalyzer.class), mock(DayanAudioTranscriptionService.class), objectMapper,
                mock(TransactionTemplate.class), "http://127.0.0.1:1", "test");

        actual.analyze("queued");

        verifyNoInteractions(frames, models);
        verify(scripts, never()).updateById(any(VideoScript.class));
    }

    private VideoFrameExtractor probe() {
        VideoFrameExtractor probe = mock(VideoFrameExtractor.class);
        org.mockito.Mockito.when(probe.probeDuration(org.mockito.ArgumentMatchers.any())).thenReturn(10.0);
        return probe;
    }

    private VideoScriptService service() {
        return new VideoScriptService(storage.toString(), scripts, shots, processor, probe(), objectMapper);
    }

    private MockMultipartFile video() {
        return new MockMultipartFile("video", "source.mp4", "video/mp4", new byte[]{1, 2, 3});
    }

    private VideoScript script(String id, String organization, String user) {
        return new VideoScript(id, organization, id, "VIDEO_ANALYSIS", "MODEL_WEAR",
                "source.mp4", storage.resolve(id).resolve("source.mp4").toString(), "video/mp4", 3L, null, null, user);
    }

    private PlatformPrincipal principal(String organization, String user) {
        return new PlatformPrincipal(user, organization, organization, "13800000000", "测试用户", "password", true,
                List.of("ADMIN"), List.of("video:script:view", "video:script:manage"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<VideoScript>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) Wrapper.class);
    }
}
