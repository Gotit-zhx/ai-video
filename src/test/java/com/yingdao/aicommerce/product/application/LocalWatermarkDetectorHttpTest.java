package com.yingdao.aicommerce.product.application;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LocalWatermarkDetectorHttpTest {
    @Test void sendsFixedLengthJsonForLargeImages() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var framing=new AtomicReference<String>();
        byte[] original=new byte[400_000];
        String sha=CommerceImageArchive.sha(original);
        server.createContext("/detect",exchange->{
            byte[] request=exchange.getRequestBody().readAllBytes();
            framing.set(exchange.getRequestHeaders().getFirst("Content-Length")+":"+request.length+":"+exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            byte[] reply=("{\"status\":\"NO_CANDIDATE\",\"detections\":[],\"sourceSha256\":\""+sha+"\",\"modelSha256\":\""+"a".repeat(64)+"\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);
            exchange.getResponseBody().write(reply);exchange.close();
        });
        server.start();
        try {
            var detector=new LocalWatermarkDetector(true,"http://127.0.0.1:"+server.getAddress().getPort(),"unit-test-token-at-least-24");
            assertEquals("NO_CANDIDATE",detector.detect(original).get("status"));
            String[] parts=framing.get().split(":");assertEquals(parts[0],parts[1]);assertEquals("null",parts[2]);
        } finally {server.stop(0);}
    }
}
