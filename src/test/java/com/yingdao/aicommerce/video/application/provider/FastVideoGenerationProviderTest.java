package com.yingdao.aicommerce.video.application.provider;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FastVideoGenerationProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void submitsPublicImagesAsReferencesWithoutResolution() throws Exception {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/cpa-file/stream/video", exchange -> {
            captured.set(objectMapper.readValue(exchange.getRequestBody(), new TypeReference<>() {}));
            byte[] response = "data: {\"type\":\"queued\",\"status\":\"queued\",\"task_id\":\"task-fast-1\"}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        FastVideoGenerationProvider provider = new FastVideoGenerationProvider(objectMapper);
        RuntimeModelConnection connection = connection();
        List<VideoGenerationProvider.Reference> references = List.of(
                image("https://assets.example/1.jpg"), image("https://assets.example/2.jpg"),
                image("https://assets.example/3.jpg"), image("https://assets.example/4.jpg"),
                image("https://assets.example/5.jpg"));
        VideoGenerationProvider.SubmitResult result = provider.submit(connection,
                new VideoGenerationProvider.GenerationRequest("prompt", references, 8, "9:16", "4K", null));

        assertThat(result.providerTaskId()).isEqualTo("task-fast-1");
        assertThat(captured.get()).doesNotContainKeys("image", "resolution");
        assertThat(captured.get().get("image_urls")).asList().containsExactly(
                "https://assets.example/1.jpg", "https://assets.example/2.jpg",
                "https://assets.example/3.jpg", "https://assets.example/4.jpg");
        assertThat(captured.get().get("metadata")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("aspect_ratio", "9:16").doesNotContainKey("resolution");
    }

    private RuntimeModelConnection connection() {
        return new RuntimeModelConnection("id", "http://localhost:" + server.getAddress().getPort(),
                "MiniMax H3", "OPENAI_COMPATIBLE", "FAST", "secret");
    }

    private VideoGenerationProvider.Reference image(String url) {
        return new VideoGenerationProvider.Reference(url, "image/jpeg");
    }
}
