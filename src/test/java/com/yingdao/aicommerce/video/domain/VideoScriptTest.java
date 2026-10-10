package com.yingdao.aicommerce.video.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class VideoScriptTest {

    @Test
    void outputSurvivesCompletionIsBoundedAndRejectsOldRunsAndSnapshots() {
        VideoScript script = new VideoScript("stream", "org", "输出", "VIDEO_ANALYSIS", "PRODUCT_ONLY",
                "source.mp4", "/tmp/source.mp4", "video/mp4", 10L, null, null, "user");
        script.markAnalyzing("model", "driver");
        String runId = script.getAnalysisRunId();
        Instant first = Instant.parse("2026-09-17T00:00:00Z");
        assertThat(script.receiveAnalysisOutput("old-run", "AI_ANALYZING", "旧任务文字", 5L, first)).isFalse();
        String response = "真实输出".repeat(10_000);
        assertThat(script.receiveAnalysisOutput(runId, "AI_ANALYZING", response, 40_000L, first)).isTrue();
        assertThat(script.getAnalysisOutput()).hasSize(32_000).isEqualTo(response.substring(8_000));
        assertThat(script.getAnalysisOutputCharacters()).isEqualTo(40_000L);
        assertThat(script.receiveAnalysisOutput(runId, "AI_ANALYZING", "旧快照", 3L, first.minusSeconds(1))).isFalse();
        script.complete("摘要", "视觉", "{}");
        assertThat(script.getAnalysisOutput()).hasSize(32_000);
        assertThat(script.receiveAnalysisOutput(runId, "AI_ANALYZING", "迟到文字", 4L, first.plusSeconds(1))).isFalse();
        script.prepareRetry();
        assertThat(script.getAnalysisOutput()).isEmpty();
        assertThat(script.getAnalysisOutputCharacters()).isZero();
        assertThat(script.getAnalysisOutputUpdatedAt()).isNull();
        script.markAnalyzing("model", "driver");
        assertThat(script.getAnalysisRunId()).isNotEqualTo(runId);
    }

    @Test
    void completingAnalysisKeepsTheNameEnteredByTheUser() {
        VideoScript script = new VideoScript(
                "script-1",
                "org-1",
                "用户填写的脚本名称",
                "VIDEO_ANALYSIS",
                "MODEL_WEAR",
                "reference.mp4",
                "C:/tmp/reference.mp4",
                "video/mp4",
                1024L,
                "中国",
                null,
                "user-1"
        );

        script.complete("模型分析摘要", "暖色室内", "{\"title\":\"模型自动生成标题\"}");

        assertThat(script.getName()).isEqualTo("用户填写的脚本名称");
        assertThat(script.getScriptJson()).contains("模型自动生成标题");
        assertThat(script.getStatus()).isEqualTo("READY");
    }

    @Test
    void analysisProgressIsMonotonicAndStopsBeforeCompletion() {
        VideoScript script = new VideoScript(
                "script-2", "org-1", "测试进度", "VIDEO_ANALYSIS", "PRODUCT_ONLY", "reference.mp4",
                "C:/tmp/reference.mp4", "video/mp4", 1024L, "中国", null, "user-1"
        );
        script.markAnalyzing("model-config-1", "driver-model");

        assertThat(script.advanceAnalysisProgress(72)).isTrue();
        assertThat(script.getProgress()).isEqualTo(72);
        assertThat(script.advanceAnalysisProgress(68)).isFalse();
        assertThat(script.getProgress()).isEqualTo(72);
        assertThat(script.advanceAnalysisProgress(100)).isTrue();
        assertThat(script.getProgress()).isEqualTo(95);

        script.complete("摘要", "视觉基调", "{}");
        assertThat(script.advanceAnalysisProgress(80)).isFalse();
        assertThat(script.getProgress()).isEqualTo(100);
    }
}
