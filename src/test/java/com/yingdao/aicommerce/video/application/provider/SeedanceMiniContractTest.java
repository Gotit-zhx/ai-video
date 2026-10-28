package com.yingdao.aicommerce.video.application.provider;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SeedanceMiniContractTest {
    @Test void retryReusesIdempotencyKeyAndPreservesExplicitOptionsAndAudioRole() throws Exception {
        var keys = new ArrayList<String>();
        var calls = new AtomicInteger();
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/contents/generations/tasks", exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean retry = calls.getAndIncrement() == 0;
            byte[] response = (retry ? "{\"message\":\"busy\"}" : "{\"id\":\"task-mini\",\"status\":\"queued\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(retry ? 503 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var provider = new DayanVideoGenerationProvider(new ObjectMapper());
            var request = new VideoGenerationProvider.GenerationRequest("原始用户提示词", List.of(), 15, "9:16", "1080p", "https://example.test/audio.mp3");
            provider.submit(connection(server, "/api/v3"), request);
            assertEquals(2, keys.size());
            assertNotNull(keys.getFirst());
            assertFalse(keys.getFirst().isBlank());
            assertEquals(keys.get(0), keys.get(1));
            var body = new ObjectMapper().readTree(captured.get());
            assertEquals(Set.of("model", "content", "duration", "ratio", "resolution", "generate_audio", "watermark", "return_last_frame"),
                    new ObjectMapper().readValue(captured.get(), Map.class).keySet());
            assertEquals(DayanVideoGenerationProvider.MODEL, body.get("model").asText());
            assertEquals("720p", body.get("resolution").asText());
            assertEquals(-1, body.get("duration").asInt());
            assertEquals("adaptive", body.get("ratio").asText());
            assertFalse(body.get("generate_audio").asBoolean());
            assertFalse(body.get("watermark").asBoolean());
            assertTrue(body.get("return_last_frame").asBoolean());
            assertEquals("原始用户提示词", body.get("content").get(0).get("text").asText());
            assertEquals("reference_audio", body.get("content").get(1).get("role").asText());
            assertEquals("https://example.test/audio.mp3", body.get("content").get(1).get("audio_url").get("url").asText());
        } finally { server.stop(0); }
    }

    @Test void pollsExistingTaskWithoutResubmittingAndKeepsFinalCostAndLastFrame() throws Exception {
        var response = new AtomicReference<>("{\"status\":\"running\",\"cost\":9}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/contents/generations/tasks/old-h3-task", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var provider = new DayanVideoGenerationProvider(new ObjectMapper());
            var connection = connection(server, "/api/v3/contents/generations/tasks");
            assertNull(provider.query(connection, "old-h3-task").cost());
            response.set("{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://test/video\",\"last_frame_url\":\"https://test/frame\"},\"cost\":4.06,\"usage\":{\"total_tokens\":88172}}");
            var result = provider.query(connection, "old-h3-task");
            assertEquals("SUCCEEDED", result.status());
            assertEquals("https://test/video", result.outputUrl());
            assertEquals(new BigDecimal("4.06"), result.cost());
            assertEquals(88172, result.usage().get("total_tokens"));
            assertTrue(result.responseJson().contains("https://test/frame"));
            response.set("{\"status\":\"expired\",\"error\":{\"message\":\"task expired\"}}");
            result = provider.query(connection, "old-h3-task");
            assertEquals("FAILED", result.status());
            assertEquals("task expired", result.errorMessage());
        } finally { server.stop(0); }
    }

    private RuntimeModelConnection connection(HttpServer server, String suffix) {
        return new RuntimeModelConnection("old-config", "http://127.0.0.1:" + server.getAddress().getPort() + suffix,
                "minimax-h3", "OPENAI", "DAYAN", "test");
    }
}
