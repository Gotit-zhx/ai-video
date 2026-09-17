package com.yingdao.aicommerce.video.application;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VideoFirstFrameAuditServiceTest {
    @TempDir Path storage;
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger extractions = new AtomicInteger();

    @Test
    void definitiveAuditAndScreenshotAreCachedAcrossRestartsAndConcurrentCallers() throws Exception {
        HttpServer server = server(200, "{\"layout\":\"SINGLE_SCENE\",\"confidence\":0.94,\"reason\":\"背景窗格属于同一场景\"}");
        try {
            var service = service(server, this::frame);
            String video = video("one.mp4");
            var first = service.audit(task(), item(), video, "job-one");
            assertThat(first.passed()).isTrue();
            assertThat(first.rejected()).isFalse();
            assertThat(first.framePath()).isRegularFile();
            assertThat(first.frameSizeBytes()).isPositive();
            var restarted = service(server, this::frame);
            try (var executor = Executors.newFixedThreadPool(3)) {
                List<Callable<VideoFirstFrameAuditService.AuditResult>> calls = List.of(
                        () -> restarted.audit(task(), item(), video, "job-one"),
                        () -> restarted.audit(task(), item(), video, "job-one"),
                        () -> restarted.audit(task(), item(), video, "job-one"));
                for (var result : executor.invokeAll(calls)) assertThat(result.get()).isEqualTo(first);
            }
            assertThat(requests.get()).isEqualTo(1);
            assertThat(extractions.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test
    void upstreamFailureRetainsFrameAndDoesNotRepeatPaidRequestOnRestart() throws Exception {
        HttpServer server = server(502, "{\"failureCode\":\"CHAT_TIMEOUT\",\"retryable\":true,\"message\":\"timeout\"}");
        try {
            String video = video("failure.mp4");
            var result = service(server, this::frame).audit(task(), item(), video, "job-failure");
            assertThat(result.layout()).isEqualTo("UNKNOWN");
            assertThat(result.reason()).contains("CHAT_TIMEOUT");
            assertThat(result.passed()).isFalse();
            assertThat(result.rejected()).isFalse();
            assertThat(result.framePath()).isRegularFile();
            assertThat(service(server, this::frame).audit(task(), item(), video, "job-failure")).isEqualTo(result);
            assertThat(requests.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test
    void interruptedAcceptedAuditIsNotResentWithoutPersistedResult() throws Exception {
        HttpServer server = server(200, "{\"layout\":\"GRID\",\"confidence\":0.98,\"reason\":\"九个独立视图\"}");
        try {
            String video = video("interrupted.mp4");
            var first = service(server, this::frame).audit(task(), item(), video, "job-interrupted");
            assertThat(first.rejected()).isTrue();
            Files.delete(first.framePath().getParent().resolve("audit-result.json"));
            var result = service(server, this::frame).audit(task(), item(), video, "job-interrupted");
            assertThat(result.layout()).isEqualTo("UNKNOWN");
            assertThat(result.reason()).contains("可能已受理");
            assertThat(result.framePath()).isEqualTo(first.framePath());
            assertThat(requests.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test
    void malformedHttpJsonRetainsFrameAndCannotPass() throws Exception {
        HttpServer server = server(200, "{\"layout\":\"SINGLE_SCENE\",");
        try {
            var result = service(server, this::frame).audit(task(), item(), video("invalid.mp4"), "invalid");
            assertThat(result.layout()).isEqualTo("UNKNOWN");
            assertThat(result.framePath()).isRegularFile();
            assertThat(result.passed()).isFalse();
            assertThat(result.rejected()).isFalse();
        } finally { server.stop(0); }
    }

    @Test
    void noDecodedFrameOrFfmpegFailurePausesBeforeAnyModelLookup() throws Exception {
        var noFrames = new VideoFirstFrameAuditService(models, null, json, RestClient.create(), storage, (input, output) -> {});
        var result = noFrames.audit(task(), item(), video("no-frames.mp4"), "no-frames");
        assertThat(result.layout()).isEqualTo("UNKNOWN");
        assertThat(result.framePath()).isNull();
        assertThat(result.frameSizeBytes()).isZero();
        var broken = new VideoFirstFrameAuditService(models, null, json, RestClient.create(), storage,
                (input, output) -> { throw new IllegalStateException("ffmpeg failed"); });
        assertThat(broken.audit(task(), item(), video("broken.mp4"), "broken").passed()).isFalse();
        verifyNoInteractions(models);
    }

    @Test
    void unknownAndLowConfidencePauseWhileOnlyHighConfidenceMultiviewIsRejected() {
        for (String layout : List.of("GRID", "SPLIT_SCREEN", "COLLAGE")) {
            assertThat(result(layout, 0.8).rejected()).isTrue();
            assertThat(result(layout, 0.79).rejected()).isFalse();
            assertThat(result(layout, 1).passed()).isFalse();
        }
        assertThat(result("SINGLE_SCENE", 0.8).passed()).isTrue();
        assertThat(result("SINGLE_SCENE", 0.79).passed()).isFalse();
        assertThat(result("UNKNOWN", 1).passed()).isFalse();
        assertThat(result("UNKNOWN", 1).rejected()).isFalse();
        assertThat(result("SINGLE_SCENE", Double.NaN).passed()).isFalse();
    }

    @Test
    void strictSchemaRejectsCoercionUnexpectedFieldsAndMissingEvidence() {
        for (Object raw : List.of(Map.of("layout", "SINGLE_SCENE", "confidence", "1", "reason", "ok"),
                Map.of("layout", "SINGLE_SCENE", "confidence", 1.1, "reason", "ok"),
                Map.of("layout", "SINGLE_SCENE", "confidence", 1, "reason", ""),
                Map.of("layout", "single_scene", "confidence", 1, "reason", "ok"),
                Map.of("layout", "SINGLE_SCENE", "confidence", 1, "reason", "ok", "passed", true))) {
            var result = VideoFirstFrameAuditService.parseResult(raw, null);
            assertThat(result.layout()).isEqualTo("UNKNOWN");
            assertThat(result.passed()).isFalse();
        }
    }

    @Test
    void differentJobsHaveIndependentFramesButReviewKeyCannotSwitchVideo() throws Exception {
        HttpServer server = server(200, "{\"layout\":\"SINGLE_SCENE\",\"confidence\":1,\"reason\":\"单画面\"}");
        try {
            var service = service(server, this::frame);
            String video = video("a.mp4");
            var one = service.audit(task(), item(), video, "job-a");
            var two = service.audit(task(), item(), video, "job-b");
            assertThat(one.framePath()).isNotEqualTo(two.framePath());
            var changed = service.audit(task(), item(), video("b.mp4"), "job-a");
            assertThat(changed.layout()).isEqualTo("UNKNOWN");
            assertThat(one.framePath()).isRegularFile();
            assertThat(requests.get()).isEqualTo(2);
            assertThat(service.audit(task(), item(), video, "../escape").passed()).isFalse();
            assertThat(requests.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @Test
    void missingDriverRetainsExtractedScreenshot() throws Exception {
        when(models.driverConnection("org")).thenReturn(Optional.empty());
        var service = new VideoFirstFrameAuditService(models, null, json, RestClient.create(), storage, this::frame);
        var result = service.audit(task(), item(), video("no-model.mp4"), "no-model");
        assertThat(result.layout()).isEqualTo("UNKNOWN");
        assertThat(result.framePath()).isRegularFile();
        assertThat(result.reason()).contains("没有可用");
    }

    @Test
    void tamperedCachedFrameAndSymlinkedReviewDirectoryCannotAuthorizeAnotherAudit() throws Exception {
        HttpServer server = server(200, "{\"layout\":\"SINGLE_SCENE\",\"confidence\":1,\"reason\":\"单画面\"}");
        try {
            var service = service(server, this::frame);
            String video = video("cache.mp4");
            var first = service.audit(task(), item(), video, "cached");
            BufferedImage different = new BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB);
            different.setRGB(32, 48, java.awt.Color.RED.getRGB());
            ImageIO.write(different, "jpg", first.framePath().toFile());
            assertThat(service.audit(task(), item(), video, "cached").passed()).isFalse();
            assertThat(requests.get()).isEqualTo(1);

            Path outside = Files.createDirectory(storage.resolve("outside"));
            Path link = first.framePath().getParent().getParent().resolve("linked-job");
            Files.createSymbolicLink(link, outside);
            assertThat(service.audit(task(), item(), video, "linked-job").passed()).isFalse();
            try (var files = Files.list(outside)) { assertThat(files).isEmpty(); }
            assertThat(requests.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    private HttpServer server(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/video/frame-audit", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
    private VideoFirstFrameAuditService service(HttpServer server, VideoFirstFrameAuditService.FrameExtractor extractor) {
        when(models.driverConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                "configured", "https://test.invalid/v1", "gpt-5.5", "OPENAI", "AUTO", "test-only")));
        return new VideoFirstFrameAuditService(models, null, json,
                RestClient.create("http://127.0.0.1:" + server.getAddress().getPort()), storage, extractor);
    }
    private void frame(Path input, Path output) throws Exception {
        extractions.incrementAndGet();
        ImageIO.write(new BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB), "jpg", output.toFile());
    }
    private String video(String name) throws Exception { return Files.write(storage.resolve(name), new byte[]{1, 2, 3}).toString(); }
    private AiVideoTask task() {
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.getId()).thenReturn("task");
        when(task.getOrganizationId()).thenReturn("org");
        return task;
    }
    private AiVideoTaskItem item() {
        AiVideoTaskItem item = mock(AiVideoTaskItem.class);
        when(item.getId()).thenReturn("item");
        when(item.getTaskId()).thenReturn("task");
        when(item.getOrganizationId()).thenReturn("org");
        return item;
    }
    private VideoFirstFrameAuditService.AuditResult result(String layout, double confidence) {
        return new VideoFirstFrameAuditService.AuditResult(layout, confidence, "evidence", null, 0);
    }
}
