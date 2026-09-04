package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageExecutionProgressTest {
    @Test void staleEntityUpdatesCannotOverwriteAtomicCounters() throws Exception {
        TableField field = AiVideoTaskItem.class.getDeclaredField("imageProgressJson").getAnnotation(TableField.class);
        assertEquals(FieldStrategy.NEVER, field.updateStrategy());
        assertEquals(FieldStrategy.NEVER, field.insertStrategy());
    }
    @Test void telemetryFailureNeverTurnsIntoAnImageRetry() {
        AiVideoTaskItemMapper mapper = mock(AiVideoTaskItemMapper.class);
        when(mapper.recordImageStep("item", "MODEL_IMAGE", "GENERATING")).thenThrow(new IllegalStateException("DB unavailable"));
        assertDoesNotThrow(() -> ImageExecutionProgress.record(mapper, "item", "MODEL_IMAGE", "GENERATING"));
        verify(mapper, times(1)).recordImageStep("item", "MODEL_IMAGE", "GENERATING");
    }
    @Test void eachAssetTracksItsOwnStage() {
        AiVideoTaskItemMapper mapper = mock(AiVideoTaskItemMapper.class);
        AiVideoTaskItem item = new AiVideoTaskItem("org", "task", "shirt", "sku", "x.png", "x.png", "image/png", 1);
        ImageExecutionProgress.record(mapper, item, "STORYBOARD_GRID", "VALIDATING");
        assertEquals("STORYBOARD_GRID", item.getActiveImageAssetType());
        verify(mapper).recordImageStep(item.getId(), "STORYBOARD_GRID", "VALIDATING");
    }
    @Test void repeatedRuntimePollsOnlyCountNewHttpAttempts() {
        AiVideoTaskItemMapper mapper = mock(AiVideoTaskItemMapper.class);
        var request = new ImageRuntimeProgress.TrackedRequest("item", "MODEL_IMAGE");
        request.apply(mapper, new ImageRuntimeProgress.Snapshot("GENERATING", 1, 1));
        request.apply(mapper, new ImageRuntimeProgress.Snapshot("GENERATING", 1, 1));
        request.apply(mapper, new ImageRuntimeProgress.Snapshot("RETRY_WAIT", 1, 2));
        request.apply(mapper, new ImageRuntimeProgress.Snapshot("GENERATING", 3, 3));
        request.apply(mapper, new ImageRuntimeProgress.Snapshot("RESPONSE_RECEIVED", 3, 4));
        verify(mapper, times(3)).recordImageStep("item", "MODEL_IMAGE", "GENERATING");
        verify(mapper, times(1)).recordImageStep("item", "MODEL_IMAGE", "RETRY_WAIT");
        verify(mapper, times(1)).recordImageStep("item", "MODEL_IMAGE", "RESPONSE_RECEIVED");
    }
    @Test void finalAuthenticatedProgressFetchIncludesInternalRetries() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/progress", exchange -> {
            assertEquals("test-only", exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body = "{\"stage\":\"RESPONSE_RECEIVED\",\"attempts\":3,\"updatedAt\":123}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AiVideoTaskItemMapper mapper = mock(AiVideoTaskItemMapper.class);
            var client = org.springframework.web.client.RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .defaultHeader("X-Internal-Token", "test-only").build();
            var progress = new ImageRuntimeProgress(mapper, client);
            String id = progress.begin("item", "MODEL_IMAGE");
            progress.finish(id);
            progress.finish(id);
            verify(mapper, times(3)).recordImageStep("item", "MODEL_IMAGE", "GENERATING");
            verify(mapper).recordImageStep("item", "MODEL_IMAGE", "RESPONSE_RECEIVED");
        } finally { server.stop(0); }
    }
}
