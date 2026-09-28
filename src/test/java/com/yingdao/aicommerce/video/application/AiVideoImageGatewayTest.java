package com.yingdao.aicommerce.video.application;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AiVideoImageGatewayTest {
    @TempDir Path directory;
    @Test void durableRuntimePayloadDecodesWithItsRequestMetadata() throws Exception {
        Class<?> response = Class.forName(AiVideoImageProcessor.class.getName() + "$PiImageResponse");
        assertNotNull(new ObjectMapper().convertValue(Map.of(
                "artifact", Map.of("name", "grid", "mimeType", "image/png", "dataBase64", "saved",
                        "modelId", "gpt-image-2", "mode", "edit", "prompt", "grid", "revisedPrompt", ""),
                "requestMeta", Map.of("requestState", "COMPLETED", "safeRetryCount", 0)), response));
    }
    @Test void lostResultConnectionIsRetriedAsLookupWithoutAnotherGeneration() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger posts = new AtomicInteger();
        AtomicBoolean disconnected = new AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generate", exchange -> {
            exchange.getRequestBody().readAllBytes(); posts.incrementAndGet();
            exchange.sendResponseHeaders(202, -1); exchange.close();
        });
        server.createContext("/v1/images/operations", exchange -> {
            if (disconnected.get()) { exchange.close(); return; }
            byte[] body = mapper.writeValueAsBytes(Map.of("status", "COMPLETED", "active", false, "updatedAt", 2,
                    "response", Map.of("artifact", Map.of("dataBase64", "saved"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v1/images/progress", exchange -> {
            exchange.sendResponseHeaders(404, -1); exchange.close();
        });
        server.start();
        try {
            RestClient client = RestClient.create("http://127.0.0.1:" + server.getAddress().getPort());
            var progress = new ImageRuntimeProgress(mock(AiVideoTaskItemMapper.class), client);
            Path file = directory.resolve("lost-receipt.json");
            var gateway = new AiVideoImageGateway(client, new AiVideoImageAttempts(mapper), 1);
            var error = assertThrows(IllegalStateException.class, () ->
                    gateway.generate(file, "hash", 0, Map.of(), progress, "item", "STORYBOARD_GRID"));
            assertTrue(AiVideoImageProcessor.retryableImageTransientFailure(error.getMessage()));
            disconnected.set(false);
            var recovered = new AiVideoImageGateway(client, new AiVideoImageAttempts(mapper), 1)
                    .generate(file, "hash", 1, Map.of(), progress, "item", "STORYBOARD_GRID");
            assertNotNull(recovered.get("artifact"));
            assertEquals(1, posts.get());
        } finally { server.stop(0); }
    }
    @Test void slowCompletionUsesLookupsAndRestartRetrievesOriginalResultWithoutAnotherPost() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger posts = new AtomicInteger(), polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generate", exchange -> {
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertTrue(body.path("asyncResult").asBoolean());
            assertTrue(body.path("singleProviderAttempt").asBoolean());
            posts.incrementAndGet();
            exchange.sendResponseHeaders(202, -1); exchange.close();
        });
        server.createContext("/v1/images/operations", exchange -> {
            int count = polls.incrementAndGet();
            byte[] body = mapper.writeValueAsBytes(count < 6
                    ? Map.of("status", "RUNNING", "active", true, "updatedAt", 1)
                    : Map.of("status", "COMPLETED", "active", false, "updatedAt", 2,
                        "response", Map.of("artifact", Map.of("dataBase64", "saved-result"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v1/images/progress", exchange -> {
            exchange.sendResponseHeaders(404, -1); exchange.close();
        });
        server.start();
        try {
            RestClient client = RestClient.create("http://127.0.0.1:" + server.getAddress().getPort());
            var progress = new ImageRuntimeProgress(mock(AiVideoTaskItemMapper.class), client);
            Path file = directory.resolve("receipt.json");
            var first = new AiVideoImageGateway(client, new AiVideoImageAttempts(mapper), 1)
                    .generate(file, "hash", 0, Map.of("prompt", "grid"), progress, "item", "STORYBOARD_GRID");
            var recovered = new AiVideoImageGateway(client, new AiVideoImageAttempts(mapper), 1)
                    .generate(file, "hash", 1, Map.of("prompt", "grid"), progress, "item", "STORYBOARD_GRID");
            assertEquals(first, recovered);
            assertEquals(1, posts.get());
            assertTrue(polls.get() >= 7);
        } finally { server.stop(0); }
    }
}
