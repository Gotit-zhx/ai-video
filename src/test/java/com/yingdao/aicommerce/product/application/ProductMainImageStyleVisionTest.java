package com.yingdao.aicommerce.product.application;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductMainImageStyleVisionTest {
    @Test void streamsWhitelistedProgressThenReturnsResult() throws Exception {
        var progress = new ArrayList<Map<String,Object>>();
        var json = new ObjectMapper();
        var frames = List.of(Map.of("type", "progress", "stage", "MODEL_STREAMING", "outputCharacters", 123, "elapsedSeconds", 9,
                        "privateReasoning", "must-not-persist"),
                Map.of("type", "result", "result", ProductMainImageStylePolicyTest.analysis()));
        var result = run(frames.stream().map(json::writeValueAsString).reduce("", (a,b) -> a + b + "\n"), progress);
        assertEquals(true, result.get("complete"));
        assertEquals(List.of(Map.of("stage", "MODEL_STREAMING", "outputCharacters", 123, "elapsedSeconds", 9)), progress);
    }

    @Test void incompleteStreamAndRuntimeFailuresNeverBecomeSuccessfulAnalysis() throws Exception {
        assertThrows(BusinessException.class, () -> run("{\"type\":\"progress\",\"stage\":\"MODEL_WAITING\"}\n", new ArrayList<>()));
        var failure = assertThrows(BusinessException.class, () -> run(
                "{\"type\":\"error\",\"failureCode\":\"POSTER_ANALYSIS_TIMEOUT\",\"message\":\"private upstream information\"}\n", new ArrayList<>()));
        assertTrue(failure.getMessage().contains("超时"));
        assertFalse(failure.getMessage().contains("private"));
    }

    private Map<String,Object> run(String content, List<Map<String,Object>> progress) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/product/analyze", exchange -> {
            assertEquals("application/x-ndjson", exchange.getRequestHeaders().getFirst("Accept"));
            assertEquals("test-token", exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(content.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            var models = mock(ModelConfigService.class);
            when(models.driverConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                    "model", "http://fixture", "fixture", "OPENAI_COMPATIBLE", "fixture", "test-key")));
            var vision = new ProductMainImageStyleVision(models, new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
            return vision.analyze("org", "PRODUCT_MAIN_IMAGE_STYLE_V1",
                    List.of(new CommerceDesignImageGateway.Reference("image/png", "fixture")), Map.of(), progress::add);
        } finally { server.stop(0); }
    }
}
