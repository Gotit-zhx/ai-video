package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.video.domain.AiVideoGenerationJob;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoGenerationJobMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VideoReviewFinalizerTest {
    private final AiVideoGenerationJobMapper jobs = mock(AiVideoGenerationJobMapper.class);
    private final AiVideoTaskAssetMapper assets = mock(AiVideoTaskAssetMapper.class);
    private final AiVideoTaskItemMapper items = mock(AiVideoTaskItemMapper.class);
    private final VideoReviewFinalizer finalizer = new VideoReviewFinalizer(jobs, assets, items);
    private final AiVideoTaskItem item = new AiVideoTaskItem("org", "task", "item", "sku", "source.png",
            "/source.png", "image/png", 100);
    private final AiVideoGenerationJob job = new AiVideoGenerationJob("run", "org", "task", item.getId(), 1,
            "model-config", "provider", "model", 10, "9:16", "720P");

    @BeforeAll
    static void initializeMetadata() {
        for (Class<?> type : List.of(AiVideoGenerationJob.class, AiVideoTaskAsset.class, AiVideoTaskItem.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "review-test"), type);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void staleOrRepeatedJobDoesNotTouchAssetsOrItem() {
        job.succeeded("output", BigDecimal.ONE, "{}");
        when(jobs.update(eq(job), any())).thenReturn(0);
        assertThat(finalizer.finish(job, item, asset("VIDEO_REVIEW_CANDIDATE", null), true)).isFalse();
        verifyNoInteractions(assets, items);
        ArgumentCaptor<LambdaUpdateWrapper> guard = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(jobs).update(eq(job), guard.capture());
        assertThat(guard.getValue().getSqlSegment()).contains("id =", "status =");
        assertThat(guard.getValue().getParamNameValuePairs().values()).contains(job.getId(), "REVIEWING");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void approvedCandidateReplacesSlotWhileKeepingPreviousFileMetadata() {
        AiVideoTaskAsset previous = asset("VIDEO_RESULT", 1);
        AiVideoTaskAsset candidate = asset("VIDEO_REVIEW_CANDIDATE", null);
        ready(List.of(previous));
        job.succeeded("output", BigDecimal.ONE, "{}");
        assertThat(finalizer.finish(job, item, candidate, true)).isTrue();
        assertThat(item.getStatus()).isEqualTo("VIDEO_READY");
        ArgumentCaptor<LambdaUpdateWrapper> updates = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(assets, org.mockito.Mockito.times(2)).update(isNull(), updates.capture());
        var archive = updates.getAllValues().get(0);
        var promote = updates.getAllValues().get(1);
        archive.getSqlSegment();
        promote.getSqlSegment();
        assertThat(archive.getParamNameValuePairs().values()).contains("VIDEO_PREVIOUS_RESULT", previous.getId());
        assertThat(promote.getParamNameValuePairs().values()).contains(candidate.getId(), item.getId(),
                "VIDEO_REVIEW_CANDIDATE", "VIDEO_RESULT", 1);
        for (var update : updates.getAllValues()) {
            assertThat(update.getSqlSet()).contains("asset_type=", "shot_sequence=")
                    .doesNotContain("file_path", "size_bytes", "prompt_text");
        }
        verify(items).updateById(item);
    }

    @Test
    void failedAuditKeepsCandidateAndPreviousResultUnchanged() {
        ready(List.of());
        job.failed("ACCEPTED", "review rejected");
        assertThat(finalizer.finish(job, item, null, false)).isTrue();
        assertThat(item.getStatus()).isEqualTo("VIDEO_FAILED");
        assertThat(item.getErrorMessage()).isEqualTo("review rejected");
        verifyNoInteractions(assets);
        verify(items).updateById(item);
    }

    @Test
    void candidateRaceThrowsBeforeCompletingItem() {
        ready(List.of(asset("VIDEO_RESULT", 1)));
        when(assets.update(isNull(), any())).thenReturn(1, 0);
        job.succeeded("output", BigDecimal.ONE, "{}");
        assertThatThrownBy(() -> finalizer.finish(job, item, asset("VIDEO_REVIEW_CANDIDATE", null), true))
                .isInstanceOf(IllegalStateException.class);
        verify(items, never()).updateById(any(AiVideoTaskItem.class));
    }

    @Test
    void missingItemCannotCommitSuccessfulPublication() {
        ready(List.of());
        when(items.updateById(item)).thenReturn(0);
        job.succeeded("output", BigDecimal.ONE, "{}");
        assertThatThrownBy(() -> finalizer.finish(job, item, asset("VIDEO_REVIEW_CANDIDATE", null), true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void previousResultRaceStopsBeforePublishingCandidate() {
        ready(List.of(asset("VIDEO_RESULT", 1)));
        when(assets.update(isNull(), any())).thenReturn(0);
        job.succeeded("output", BigDecimal.ONE, "{}");
        assertThatThrownBy(() -> finalizer.finish(job, item, asset("VIDEO_REVIEW_CANDIDATE", null), true))
                .isInstanceOf(IllegalStateException.class);
        verify(assets).update(isNull(), any());
        verify(items, never()).updateById(any(AiVideoTaskItem.class));
    }

    private void ready(List<AiVideoTaskAsset> previous) {
        when(jobs.update(eq(job), any())).thenReturn(1);
        when(assets.selectList(any())).thenReturn(previous);
        when(assets.update(isNull(), any())).thenReturn(1);
        when(items.updateById(item)).thenReturn(1);
    }

    private AiVideoTaskAsset asset(String type, Integer shot) {
        return new AiVideoTaskAsset("org", "task", item.getId(), type, shot, "/retained-" + type + ".mp4",
                "video/mp4", 1024, "{\"jobId\":\"" + job.getId() + "\"}", "model");
    }
}
