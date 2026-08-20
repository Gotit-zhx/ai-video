package com.yingdao.aicommerce.video.application.provider;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class H3MultimodalVideoGenerationProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsNestedJobIdAndPollsExactJobEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/reference.jpg", exchange -> respond(exchange, "image/jpeg", "image"));
        server.createContext("/api/videos", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, "application/json", "{\"job\":{\"id\":\"job-nested-1\",\"status\":\"running\"}}");
                return;
            }
            respond(exchange, "application/json", "{\"error\":\"unexpected endpoint\"}", 404);
        });
        server.createContext("/api/videos/job-nested-1", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret");
            respond(exchange, "application/json", "{\"job\":{\"id\":\"job-nested-1\",\"status\":\"completed\",\"progress\":100}}");
        });
        server.start();

        H3MultimodalVideoGenerationProvider provider = new H3MultimodalVideoGenerationProvider(new ObjectMapper());
        RuntimeModelConnection connection = new RuntimeModelConnection("id", baseUrl(), "MiniMax H3",
                "OPENAI_COMPATIBLE", "H3_MULTI", "secret");
        VideoGenerationProvider.SubmitResult submitted = provider.submit(connection,
                new VideoGenerationProvider.GenerationRequest("prompt",
                        List.of(new VideoGenerationProvider.Reference(baseUrl() + "/reference.jpg", "image/jpeg")),
                        5, "16:9", "768P", null));

        assertThat(submitted.providerTaskId()).isEqualTo("job-nested-1");
        assertThat(submitted.status()).isEqualTo("RUNNING");

        VideoGenerationProvider.QueryResult queried = provider.query(connection, submitted.providerTaskId());
        assertThat(queried.status()).isEqualTo("SUCCEEDED");
        assertThat(queried.usage()).containsEntry("progress", 100);
        assertThat(queried.outputUrl()).isEqualTo(baseUrl() + "/api/videos/job-nested-1/content");
        assertThat(queried.requestJson()).contains(baseUrl() + "/api/videos/job-nested-1");
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String type, String body) throws IOException {
        respond(exchange, type, body, 200);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String type, String body, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
