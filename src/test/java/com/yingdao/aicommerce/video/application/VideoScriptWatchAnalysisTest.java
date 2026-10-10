package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.domain.VideoScriptShot;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoScriptWatchAnalysisTest {
    @TempDir Path storage;

    @BeforeAll
    static void initializeTableMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, VideoScript.class);
        TableInfoHelper.initTableInfo(assistant, VideoScriptShot.class);
    }

    @Test
    void uploadedVideoGoesDirectlyToWatchWithoutJavaAnalysisFramesOrCameraOverrides() throws Exception {
        try (var fixture = new Fixture(9)) {
            fixture.processor.analyze("watch-script");

            Map<String, Object> request = fixture.request.get();
            assertThat(request).containsEntry("sourceVideoPath", fixture.source.toAbsolutePath().normalize().toString())
                    .containsEntry("progressId", fixture.script.getAnalysisRunId()).doesNotContainKey("frames");
            Map<?, ?> context = (Map<?, ?>) request.get("context");
            assertThat(context.get("analysisMode")).isEqualTo("PI_WATCH_SKILL");
            assertThat(context.get("sourceDurationSeconds")).isEqualTo(42.0);
            assertThat(context.get("durationSeconds")).isEqualTo(15.0);
            assertThat(context.get("desiredShotCount")).isEqualTo(9);
            for (String omitted : List.of("cameraMotionEvidence", "frameCount", "semanticSamplingFps")) {
                assertThat(context.containsKey(omitted)).isFalse();
            }
            verify(fixture.frames).probeDuration(fixture.source.toAbsolutePath().normalize());
            verify(fixture.frames, never()).extract(any(), any());
            verify(fixture.frames, never()).extractMotionFrames(any(), any(), anyDouble());
            verifyNoInteractions(fixture.camera, fixture.audio);
            assertThat(fixture.script.getStatus()).isEqualTo("READY");
            assertThat(fixture.script.getAnalysisOutput()).isEqualTo("watch 模型实际返回文字");
            assertThat(fixture.script.getAnalysisOutputCharacters()).isEqualTo(200);
            assertThat(fixture.script.getAnalysisOutputUpdatedAt()).isNotNull();

            var persisted = ArgumentCaptor.forClass(VideoScriptShot.class);
            verify(fixture.shots, times(9)).insert(persisted.capture());
            assertThat(persisted.getAllValues()).allSatisfy(shot -> {
                assertThat(shot.getCameraMovement()).isEqualTo("watch 判断的运镜");
                assertThat(shot.getMotionDescription()).isEqualTo("watch 判断的动作");
            });
            assertThat(persisted.getAllValues().getFirst().getStartSecond()).isEqualByComparingTo("0");
            assertThat(persisted.getAllValues().getLast().getEndSecond()).isEqualByComparingTo("15");
            verify(fixture.frames).extractShotPreviews(eq(fixture.source.toAbsolutePath().normalize()),
                    eq(fixture.source.toAbsolutePath().normalize().getParent().resolve("shot-previews")),
                    argThat(times -> times.size() == 9 && times.values().stream().allMatch(time -> time == 3.25)));
        }
    }

    @Test
    void optionalTranscriptIsEvidenceForWatchAndDoesNotOverrideItsNarration() throws Exception {
        try (var fixture = new Fixture(9)) {
            fixture.script.requestAudioExtraction(true);
            when(fixture.audio.transcribe(fixture.script)).thenReturn(new DayanAudioTranscriptionService.Transcript(
                    "可选的原始口播", List.of(new DayanAudioTranscriptionService.Segment(
                    BigDecimal.ZERO, new BigDecimal("42"), null, "可选的原始口播"))));
            fixture.processor.analyze("watch-script");

            Map<?, ?> context = (Map<?, ?>) fixture.request.get().get("context");
            assertThat(context.get("hasReliableAudioTranscript")).isEqualTo(true);
            assertThat(context.get("audioExtractionStatus")).isEqualTo("TRANSCRIBED");
            assertThat(((Map<?, ?>) context.get("audioTranscript")).get("fullText")).isEqualTo("可选的原始口播");
            var persisted = ArgumentCaptor.forClass(VideoScriptShot.class);
            verify(fixture.shots, times(9)).insert(persisted.capture());
            assertThat(persisted.getAllValues()).allSatisfy(shot -> assertThat(shot.getNarration()).isNull());
        }
    }

    @Test
    void invalidShotCountDoesNotGeneratePreviewsOrSavePartialShots() throws Exception {
        try (var fixture = new Fixture(8)) {
            fixture.processor.analyze("watch-script");
            assertThat(fixture.script.getStatus()).isEqualTo("FAILED");
            assertThat(fixture.script.getErrorMessage()).contains("8 个分镜");
            assertThat(fixture.script.getAnalysisOutput()).isEqualTo("watch 模型实际返回文字");
            verify(fixture.frames, never()).extractShotPreviews(any(), any(), anyMap());
            verify(fixture.shots, never()).insert(any(VideoScriptShot.class));
        }
    }

    @Test
    void unavailablePreviewGenerationDoesNotDiscardCompletedWatchOutput() throws Exception {
        try (var fixture = new Fixture(9)) {
            doThrow(new IllegalStateException("preview unavailable"))
                    .when(fixture.frames).extractShotPreviews(any(), any(), anyMap());
            fixture.processor.analyze("watch-script");
            assertThat(fixture.script.getStatus()).isEqualTo("READY");
            assertThat(fixture.script.getAnalysisOutput()).isEqualTo("watch 模型实际返回文字");
            verify(fixture.shots, times(9)).insert(any(VideoScriptShot.class));
        }
    }

    @Test
    void previewsUseValidEvidenceTimesAndScaleOnlyFallbackMidpointsToTheSource() {
        List<VideoScriptAnalysisResult.Shot> shots = new ArrayList<>();
        Object[] evidence = {3.25, 3.25, "5.5", -1, 42, "NaN", "invalid", Double.POSITIVE_INFINITY, 0};
        for (int index = 0; index < 9; index++) {
            BigDecimal start = BigDecimal.valueOf(index * 15.0 / 9);
            BigDecimal end = BigDecimal.valueOf((index + 1) * 15.0 / 9);
            shots.add(new VideoScriptAnalysisResult.Shot(index + 1, "CONTENT", "镜头", start, end,
                    end.subtract(start), "场景", "动作", "微幅", "", "中景", "运镜", "切换", "自然",
                    Map.of("sourceTimestampSeconds", evidence[index])));
        }
        var result = new VideoScriptAnalysisResult("标题", "摘要", "基调", new BigDecimal("15"), shots);
        Map<Integer, Double> timestamps = VideoScriptAnalysisProcessor.shotPreviewTimestamps(result, 42);
        assertThat(timestamps).containsEntry(1, 3.25).containsEntry(2, 3.25).containsEntry(3, 5.5).containsEntry(9, 0.0);
        assertThat(timestamps.get(4)).isCloseTo(16.333333, org.assertj.core.data.Offset.offset(0.00001));
        assertThat(timestamps.get(5)).isCloseTo(21.0, org.assertj.core.data.Offset.offset(0.00001));
        assertThat(timestamps.values()).allSatisfy(time -> assertThat(time).isGreaterThanOrEqualTo(0).isLessThan(42));
    }

    private class Fixture implements AutoCloseable {
        final VideoScriptMapper scripts = mock(VideoScriptMapper.class);
        final VideoScriptShotMapper shots = mock(VideoScriptShotMapper.class);
        final VideoFrameExtractor frames = mock(VideoFrameExtractor.class);
        final VideoCameraMotionAnalyzer camera = mock(VideoCameraMotionAnalyzer.class);
        final DayanAudioTranscriptionService audio = mock(DayanAudioTranscriptionService.class);
        final AtomicReference<Map<String, Object>> request = new AtomicReference<>();
        final HttpServer server;
        final Path source;
        final VideoScript script;
        final VideoScriptAnalysisProcessor processor;

        Fixture(int shotCount) throws Exception {
            Files.createDirectories(storage.resolve("nested"));
            source = storage.resolve("nested/../source.mp4");
            Files.write(source, new byte[]{1, 2, 3});
            script = new VideoScript("watch-script", "org", "watch 测试", "VIDEO_ANALYSIS", "MODEL_WEAR",
                    "source.mp4", source.toString(), "video/mp4", 3L, null, null, "user");
            when(scripts.selectById("watch-script")).thenReturn(script);
            when(scripts.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(frames.probeDuration(any())).thenReturn(42.0);
            doAnswer(invocation -> {
                assertThat(request.get()).as("preview extraction follows the model response").isNotNull();
                return null;
            }).when(frames).extractShotPreviews(any(), any(), anyMap());
            var model = mock(ModelConfigService.class);
            when(model.driverConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                    "model-config", "https://model.invalid/v1", "driver", "openai-chat", "test", "test-key")));
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
            var json = new ObjectMapper();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/video-script/analyze", exchange -> {
                request.set(json.readValue(exchange.getRequestBody().readAllBytes(), Map.class));
                List<Map<String, Object>> returnedShots = new ArrayList<>();
                for (int index = 0; index < shotCount; index++) {
                    var shot = new java.util.LinkedHashMap<String, Object>();
                    shot.put("sequence", index + 1); shot.put("stage", "CONTENT"); shot.put("title", "镜头");
                    shot.put("startSecond", index * 42.0 / shotCount); shot.put("endSecond", (index + 1) * 42.0 / shotCount);
                    shot.put("durationSeconds", 42.0 / shotCount); shot.put("sceneDescription", "watch 判断的画面");
                    shot.put("motionDescription", "watch 判断的动作"); shot.put("motionIntensity", "微幅");
                    shot.put("narration", ""); shot.put("cameraMovement", "watch 判断的运镜");
                    shot.put("imagePrompt", Map.of("sourceTimestampSeconds", 3.25));
                    returnedShots.add(shot);
                }
                byte[] body = json.writeValueAsString(Map.of(
                        "result", Map.of("title", "标题", "summary", "摘要", "globalVisualTone", "基调", "durationSeconds", 42, "shots", returnedShots),
                        "analysisOutput", "watch 模型实际返回文字", "analysisOutputCharacters", 200,
                        "analysisOutputUpdatedAt", 1789600000000L)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/v1/video-script/progress", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
            server.start();
            processor = new VideoScriptAnalysisProcessor(scripts, shots, model, frames, camera, audio, json,
                    new TransactionTemplate(manager), "http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
        }

        @Override public void close() { server.stop(0); }
    }
}
