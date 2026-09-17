package com.yingdao.aicommerce.video.application.provider;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DayanReferenceVideoTest {
    @Test void submitsVideoAsVideoAlongsideImageWithMentionPrompt() throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/contents/generations/tasks", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"id\":\"one-task\",\"status\":\"queued\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var provider=new DayanVideoGenerationProvider(new ObjectMapper());
            var connection=new RuntimeModelConnection("test","http://127.0.0.1:"+server.getAddress().getPort(),"minimax-h3","OPENAI","DAYAN","test-key");
            var request=new VideoGenerationProvider.GenerationRequest("让 @图片1 模仿 @视频1",List.of(
                    new VideoGenerationProvider.Reference("https://example.test/image.png","image/png"),
                    new VideoGenerationProvider.Reference("https://example.test/ref.mp4","video/mp4")),5,"9:16","720p",null);
            assertEquals("one-task",provider.submit(connection,request).providerTaskId());
            var json=new ObjectMapper().readTree(captured.get());
            assertEquals("image_url",json.get("content").get(1).get("type").asText());
            assertEquals("video_url",json.get("content").get(2).get("type").asText());
            assertEquals("https://example.test/ref.mp4",json.get("content").get(2).get("video_url").asText());
            assertTrue(json.get("content").get(0).get("text").asText().contains("@视频1"));
        } finally {server.stop(0);}
    }
}
