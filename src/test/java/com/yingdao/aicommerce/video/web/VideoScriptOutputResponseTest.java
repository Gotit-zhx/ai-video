package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.video.domain.VideoScript;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class VideoScriptOutputResponseTest {
    @Test
    void listOmitsTextWhileStatusAndDetailIncludeItWithTheSameRun() {
        var script = new VideoScript("stream", "org", "输出", "VIDEO_ANALYSIS", "PRODUCT_ONLY",
                "source.mp4", "/tmp/source.mp4", "video/mp4", 10L, null, null, "user");
        script.markAnalyzing("model", "driver");
        script.receiveAnalysisOutput(script.getAnalysisRunId(), "AI_ANALYZING", "模型文字", 4L, Instant.now());
        var list = VideoScriptController.VideoScriptSummaryResponse.from(script);
        var detail = VideoScriptController.VideoScriptSummaryResponse.from(script, true);
        var status = VideoScriptController.VideoScriptStatusResponse.from(script);
        assertThat(list.analysisOutput()).isNull();
        assertThat(list.analysisOutputCharacters()).isEqualTo(4);
        assertThat(detail.analysisOutput()).isEqualTo("模型文字");
        assertThat(status.analysisOutput()).isEqualTo(detail.analysisOutput());
        assertThat(status.analysisRunId()).isEqualTo(detail.analysisRunId()).isEqualTo(list.analysisRunId());
    }
}
