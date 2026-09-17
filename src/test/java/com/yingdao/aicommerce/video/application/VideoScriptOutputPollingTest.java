package com.yingdao.aicommerce.video.application;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VideoScriptOutputPollingTest {
    @Test
    void pollsActualOutputEvenWhenPercentStaysConstantAndIgnoresAnOldRun() throws Exception {
        var mapper = mock(VideoScriptMapper.class);
        var script = new VideoScript("stream", "org", "输出", "VIDEO_ANALYSIS", "PRODUCT_ONLY",
                "source.mp4", "/tmp/source.mp4", "video/mp4", 10L, null, null, "user");
        script.markAnalyzing("model", "driver");
        script.advanceAnalysisProgress(80);
        when(mapper.selectById("stream")).thenReturn(script);
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/video-script/progress", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Token")).isEqualTo("test-token");
            byte[] body = json.writeValueAsString(Map.of(
                    "progress", 70, "stage", "AI_ANALYZING", "output", "模型实际返回的文字",
                    "outputCharacters", 10, "outputUpdatedAt", 1789600000000L, "done", false
            )).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var processor = new VideoScriptAnalysisProcessor(mapper, mock(VideoScriptShotMapper.class),
                    mock(ModelConfigService.class), mock(VideoFrameExtractor.class), mock(VideoCameraMotionAnalyzer.class),
                    mock(DayanAudioTranscriptionService.class), json, mock(TransactionTemplate.class),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
            ReflectionTestUtils.invokeMethod(processor, "pollAnalysisProgress", "stream", script.getAnalysisRunId());
            assertThat(script.getProgress()).isEqualTo(80);
            assertThat(script.getAnalysisOutput()).isEqualTo("模型实际返回的文字");
            assertThat(script.getAnalysisOutputCharacters()).isEqualTo(10);
            assertThat(script.getAnalysisOutputUpdatedAt()).isNotNull();
            verify(mapper).updateById(script);
            clearInvocations(mapper);
            ReflectionTestUtils.invokeMethod(processor, "pollAnalysisProgress", "stream", "old-run");
            verify(mapper, never()).updateById(any(VideoScript.class));
        } finally {
            server.stop(0);
        }
    }
}
