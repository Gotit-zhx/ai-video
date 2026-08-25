package com.yingdao.aicommerce.video.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoScriptTest {

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
