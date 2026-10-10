package com.yingdao.aicommerce.video.application;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StructureAnalysisFailureTest {
    @TempDir Path storage;

    @Test
    void nonRetryableGatewayResponseStopsInitialAnalysisAndValidationAfterOneRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/product/analyze", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] response = "{\"message\":\"所选模型不可用；timeout\",\"failureCode\":\"CHAT_MODEL_UNAVAILABLE\",\"retryable\":false,\"upstreamStatus\":500}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(502, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiVideoTaskItem item = itemWithImage();
            ModelConfigService models = mock(ModelConfigService.class);
            when(models.driverConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                    "selected", "https://example.com/v1", "selected-user-model", "OPENAI", "AUTO", "test")));
            AiVideoTaskItemMapper items = mock(AiVideoTaskItemMapper.class);
            ProductFunctionAnalysisService service = new ProductFunctionAnalysisService(items, models, new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test");

            assertThatThrownBy(() -> service.ensureAnalysis(task(), item)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STRUCTURE_ANALYSIS_PAUSED")
                    .hasMessageContaining("selected-user-model")
                    .hasMessageContaining("CHAT_MODEL_UNAVAILABLE");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(item.getProductFunctionAnalysisJson()).isNull();
            org.mockito.Mockito.verify(items, org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.any(AiVideoTaskItem.class));

            item.updateProductFunctionAnalysis("{\"version\":\"GARMENT_TOPOLOGY_V2\"}");
            assertThatThrownBy(() -> service.validateGenerated(task(), item, "MODEL_IMAGE",
                    Files.readAllBytes(Path.of(item.getSourceFilePath())), "image/png"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("selected-user-model")
                    .hasMessageContaining("CHAT_MODEL_UNAVAILABLE");
            assertThat(calls.get()).isEqualTo(2);
            assertThat(item.getProductFunctionAnalysisJson()).isEqualTo("{\"version\":\"GARMENT_TOPOLOGY_V2\"}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void existingStructureIsReusedWithoutCheckingModelOrUploadingAgain() {
        ModelConfigService models = mock(ModelConfigService.class);
        AiVideoTaskItemMapper items = mock(AiVideoTaskItemMapper.class);
        ProductFunctionAnalysisService service = new ProductFunctionAnalysisService(items, models, new ObjectMapper(),
                "http://127.0.0.1:1", "test");
        AiVideoTaskItem item = new AiVideoTaskItem("org", "task", "item", "sku", "source.png",
                "/does-not-exist.png", "image/png", 1);
        String contract = "{\"version\":\"GARMENT_TOPOLOGY_V2\",\"existing\":true}";
        item.updateProductFunctionAnalysis(contract);

        assertThat(service.ensureAnalysis(task(), item)).isEqualTo(contract);
        verifyNoInteractions(models, items);
    }

    @Test
    void pausedSchemaFailureCannotReenterMalformedOrSchedulerRetries() {
        AtomicInteger attempts = new AtomicInteger();
        String message = "[STRUCTURE_ANALYSIS_PAUSED] 服装结构识别结果缺少必填字段；timeout 502";
        assertThatThrownBy(() -> AiVideoImageProcessor.runWithImageRecovery(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException(message);
        }, ignored -> {}, () -> {})).isInstanceOf(IllegalStateException.class);
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(AiVideoImageProcessor.retryableImageTransientFailure(message)).isFalse();
        assertThat(AiVideoImageProcessor.retryablePipelineImageFailure("FAILED", message)).isFalse();
        assertThat(AiVideoImageProcessor.terminalTaskFailureWithoutFailedItem("FAILED", message, false)).isTrue();
        assertThat(AiVideoImageProcessor.retryableImageTransientFailure("模型已生成图片，结果地址已保存；下载失败")).isTrue();
    }

    @Test
    void temporaryStructureFailuresReturnToSchedulerAfterCooldownWithoutHotLoop() {
        for (String code : java.util.List.of("CHAT_RATE_LIMITED", "CHAT_INCOMPLETE_RESPONSE",
                "CHAT_TIMEOUT", "CHAT_NETWORK_INTERRUPTED", "STRUCTURE_ANALYSIS_RETRYABLE")) {
            String message = "[STRUCTURE_ANALYSIS_PAUSED] [" + code + "]";
            assertThat(AiVideoImageProcessor.retryableImageTransientFailure(message)).isTrue();
            assertThat(AiVideoImageProcessor.retryablePipelineImageFailure("VIDEO_FAILED", "图片生成失败：" + message)).isTrue();
            java.time.Instant failedAt = java.time.Instant.now();
            assertThat(AiVideoImageProcessor.imageRetryDue(message, failedAt, failedAt.plusSeconds(59))).isFalse();
            assertThat(AiVideoImageProcessor.imageRetryDue(message, failedAt, failedAt.plusSeconds(60))).isTrue();
            AtomicInteger calls = new AtomicInteger();
            assertThatThrownBy(() -> AiVideoImageProcessor.runWithImageRecovery(() -> {
                calls.incrementAndGet();
                throw new IllegalStateException(message);
            }, ignored -> {}, () -> {})).isInstanceOf(IllegalStateException.class);
            assertThat(calls.get()).isEqualTo(1);
        }
        assertThat(AiVideoImageProcessor.retryableImageTransientFailure(
                "[STRUCTURE_ANALYSIS_PAUSED] [CHAT_MODEL_UNAVAILABLE] timeout")).isFalse();
        assertThat(AiVideoImageProcessor.imageRetryDue("模型已生成图片，结果地址已保存；下载失败",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH)).isTrue();
    }

    private AiVideoTaskItem itemWithImage() throws Exception {
        Path file = storage.resolve("source.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return new AiVideoTaskItem("org", "task", "item", "sku", "source.png", file.toString(), "image/png", Files.size(file));
    }

    private AiVideoTask task() {
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.getOrganizationId()).thenReturn("org");
        when(task.getGenerationMode()).thenReturn("MODEL_WEAR");
        return task;
    }
}
