package com.yingdao.aicommerce.product.application;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CommerceDesignImageGatewayHttpTest {
    private static final ModelConfigService.RuntimeModelConnection MODEL = new ModelConfigService.RuntimeModelConnection(
            "model", "http://unused.invalid", "test-model", "OPENAI_COMPATIBLE", "CUSTOM", "unit-only");
    private static final String RESULT = "{\"artifact\":{\"dataBase64\":\"aGVsbG8=\",\"mimeType\":\"image/png\",\"modelId\":\"test-model\",\"mode\":\"edit\",\"revisedPrompt\":\"\"}}";

    @Test void surfaceMockupKeepsAllFourReferenceRolesInOrder() throws Exception {
        var body = new AtomicReference<String>();
        try (var server = new Loopback(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, RESULT);
        }); var gateway = new CommerceDesignImageGateway(server.url(), "unit-only")) {
            var references = List.of("source", "blank", "position", "variant").stream()
                    .map(role -> new CommerceDesignImageGateway.Reference("image/png",
                            java.util.Base64.getEncoder().encodeToString(role.getBytes(StandardCharsets.UTF_8)))).toList();
            gateway.generateArtwork("surface mockup", "surface_attempt", MODEL, references);
            var sent = new tools.jackson.databind.ObjectMapper().readTree(body.get()).path("references");
            assertEquals(4, sent.size());
            for (int i = 0; i < references.size(); i++)
                assertEquals(references.get(i).dataBase64(), sent.get(i).path("dataBase64").asText());
            assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                    () -> gateway.generateArtwork("too many", "surface_overflow", MODEL,
                            java.util.Collections.nCopies(5, references.getFirst())));
        }
    }

    @Test void dedicatedPoolAdmitsTwelveSimultaneousPostsAndMatchesFiveHundredWorkerLimit() throws Exception {
        int concurrent = 12;
        var entered = new CountDownLatch(concurrent); var release = new CountDownLatch(1);
        try (var server = new Loopback(exchange -> {
            exchange.getRequestBody().readAllBytes(); entered.countDown(); await(release); reply(exchange, 200, RESULT);
        }); var transport = new CommerceImageHttpTransport();
             var gateway = new CommerceDesignImageGateway(transport, server.url(), "unit-only");
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertTrue(transport.manager.getMaxTotal() >= ProductWatermarkQueue.MAX_CONCURRENT);
            assertTrue(transport.manager.getDefaultMaxPerRoute() >= ProductWatermarkQueue.MAX_CONCURRENT);
            assertEquals(Timeout.ofMinutes(60), transport.requestConfig.getResponseTimeout());
            assertEquals(Timeout.ofSeconds(60), transport.requestConfig.getConnectionRequestTimeout());
            var futures = java.util.stream.IntStream.range(0, concurrent)
                    .mapToObj(i -> executor.submit(() -> generate(gateway, "concurrent_" + i))).toList();
            try { assertTrue(entered.await(5, TimeUnit.SECONDS), "More than the old five requests must enter the server concurrently"); }
            finally { release.countDown(); }
            for (var future : futures) assertNotNull(future.get(5, TimeUnit.SECONDS).artifact());
        }
    }

    @Test void productPreviewRequestsLowOutputQualityWithoutReducingReferenceFidelity() throws Exception {
        var body = new AtomicReference<String>();
        try (var server = new Loopback(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, RESULT);
        }); var gateway = new CommerceDesignImageGateway(server.url(), "unit-only")) {
            var refs = List.of("source", "blank", "position").stream().map(role ->
                    new CommerceDesignImageGateway.Reference("image/png", java.util.Base64.getEncoder()
                            .encodeToString(role.getBytes(StandardCharsets.UTF_8)))).toList();
            gateway.generateProductPreview("surface preview", "low_preview_attempt", MODEL, refs);
            var sent = new tools.jackson.databind.ObjectMapper().readTree(body.get());
            assertEquals("low", sent.path("quality").asText());
            assertFalse(sent.has("productMockup"));
            assertFalse(sent.has("background"));
            assertEquals("high", sent.path("inputFidelity").asText());
            assertEquals("1024x1024", sent.path("size").asText());
            assertEquals(3, sent.path("references").size());
            for(int i=0;i<refs.size();i++)assertEquals(refs.get(i).dataBase64(),sent.path("references").get(i).path("dataBase64").asText());
            gateway.generateProductPreview("final commerce mockup", "final_mockup_attempt", MODEL, refs,true);
            var productMockup=new tools.jackson.databind.ObjectMapper().readTree(body.get());
            assertTrue(productMockup.path("productMockup").asBoolean());
            assertEquals("low",productMockup.path("quality").asText());
            assertEquals("1024x1024",productMockup.path("size").asText());
            assertFalse(productMockup.has("detailGrid"));
            assertFalse(productMockup.has("background"));
            gateway.generateArtwork("extract original print", "unchanged_artwork", MODEL, refs);
            assertFalse(new tools.jackson.databind.ObjectMapper().readTree(body.get()).has("quality"));
            assertFalse(new tools.jackson.databind.ObjectMapper().readTree(body.get()).has("productMockup"));
        }
    }

    @Test void exhaustedLeaseIsProvenUnsentAndDoesNotReachServer() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var requests = new AtomicInteger();
        try (var server = new Loopback(exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet(); entered.countDown(); await(release); reply(exchange, 200, RESULT);
        }); var gateway = new CommerceDesignImageGateway(new CommerceImageHttpTransport(1, Timeout.ofMilliseconds(100), Timeout.ofSeconds(5)), server.url(), "unit-only");
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> generate(gateway, "first_attempt"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var failure = assertThrows(ImageGenerationNotSentException.class, () -> generate(gateway, "queued_attempt"));
                assertTrue(failure.getMessage().contains("请求尚未发送"));
                assertEquals(1, requests.get());
            } finally { release.countDown(); }
            assertNotNull(first.get(5, TimeUnit.SECONDS).artifact());
            assertEquals(1, requests.get());
        }
    }

    @Test void responseTimeoutReattachesToSameRuntimeOperationOnlyOnce() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var requests = new AtomicInteger();
        try (var server = new Loopback(exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet(); entered.countDown(); await(release); exchange.close();
        }); var gateway = new CommerceDesignImageGateway(new CommerceImageHttpTransport(2, Timeout.ofSeconds(1), Timeout.ofMilliseconds(150)), server.url(), "unit-only")) {
            try {
                assertThrows(ResourceAccessException.class, () -> generate(gateway, "timeout_attempt"));
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertEquals(2, requests.get());
            } finally { release.countDown(); }
        }
    }

    @Test void retryableHttpStatusDoesNotAutomaticallyReplayPaidPost() throws Exception {
        var requests = new AtomicInteger();
        try (var server = new Loopback(exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Retry-After", "0"); reply(exchange, 503, "{\"error\":\"busy\"}");
        }); var gateway = new CommerceDesignImageGateway(server.url(), "unit-only")) {
            assertThrows(RestClientResponseException.class, () -> generate(gateway, "status_attempt"));
            assertEquals(1, requests.get());
        }
    }

    @Test void confirmedProviderRejectionPreservesClassificationWithoutLeakingRawErrorOrRepeatingPost() throws Exception {
        var requests=new AtomicInteger();
        try(var server=new Loopback(exchange->{
            requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            reply(exchange,502,"{\"message\":\"sensitive-provider-detail\",\"requestState\":\"REJECTED\",\"failureKind\":\"POLICY\",\"providerStatus\":400,\"networkRetryCount\":0,\"imageModelFallback\":{\"reason\":\"COPYRIGHT\",\"fromModelId\":\"gpt-image-2\",\"toModelId\":\"grok-imagine-image-2.0\"}}");
        });var gateway=new CommerceDesignImageGateway(server.url(),"unit-only")){
            var error=assertThrows(CommerceImageRejectedException.class,()->generate(gateway,"rejected_attempt"));
            assertEquals("POLICY",error.failureKind());assertEquals(400,error.providerStatus());
            assertTrue(error.getMessage().contains("GPT Image 2因第三方内容限制"));assertTrue(error.getMessage().contains("Grok：图片内容未通过服务商审核"));
            assertFalse(error.getMessage().contains("sensitive-provider-detail"));assertEquals(1,requests.get());
        }
    }

    @Test void legacyRuntimeRejectionDoesNotMistakeInternal502ForProviderStatus() throws Exception {
        try(var server=new Loopback(exchange->{exchange.getRequestBody().readAllBytes();
            reply(exchange,502,"{\"requestState\":\"REJECTED\",\"failureKind\":\"COPYRIGHT\"}");
        });var gateway=new CommerceDesignImageGateway(server.url(),"unit-only")){
            var error=assertThrows(CommerceImageRejectedException.class,()->generate(gateway,"legacy_rejection"));
            assertNull(error.providerStatus());assertEquals("COPYRIGHT",error.failureKind());
        }
    }

    @Test void transparentOnlyRejectionExplainsSkippedFallbackAndKeepsProviderReason() throws Exception {
        try(var server=new Loopback(exchange->{exchange.getRequestBody().readAllBytes();
            reply(exchange,502,"{\"requestState\":\"REJECTED\",\"failureKind\":\"COPYRIGHT\",\"providerStatus\":400,\"imageOutputRequirement\":{\"code\":\"NATIVE_TRANSPARENT_PNG_REQUIRED\",\"fallbackSkipped\":true}}");
        });var gateway=new CommerceDesignImageGateway(server.url(),"unit-only")){
            var error=assertThrows(CommerceImageRejectedException.class,()->generate(gateway,"native_required"));
            assertEquals("COPYRIGHT",error.failureKind());assertEquals(400,error.providerStatus());
            assertTrue(error.getMessage().contains("第三方内容限制"));assertTrue(error.getMessage().contains("未调用不支持透明输出的备用模型"));
        }
    }

    @Test void unsupportedTransparentRouteDoesNotInventAnUpstreamRejection() throws Exception {
        try(var server=new Loopback(exchange->{exchange.getRequestBody().readAllBytes();
            reply(exchange,422,"{\"requestState\":\"REJECTED\",\"failureKind\":\"PROVIDER\",\"failureCode\":\"NATIVE_TRANSPARENT_PNG_UNSUPPORTED\"}");
        });var gateway=new CommerceDesignImageGateway(server.url(),"unit-only")){
            var error=assertThrows(CommerceImageRejectedException.class,()->generate(gateway,"native_unsupported"));
            assertNull(error.providerStatus());assertTrue(error.getMessage().contains("不支持原生透明 PNG，未调用生图"));
        }
    }

    @Test void backupRejectionCannotHideAnUncertainPrimaryOrEarlierNetworkAttempt() throws Exception {
        for(String metadata:List.of("\"networkRetryCount\":1","\"imageModelFallback\":{\"reason\":\"NETWORK\"}")){
            var requests=new AtomicInteger();
            try(var server=new Loopback(exchange->{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
                reply(exchange,502,"{\"requestState\":\"REJECTED\",\"failureKind\":\"POLICY\","+metadata+"}");
            });var gateway=new CommerceDesignImageGateway(server.url(),"unit-only")){
                assertThrows(RestClientResponseException.class,()->generate(gateway,"uncertain_rejected"));
                assertEquals(1,requests.get());
            }
        }
    }

    @Test void disconnectedRuntimeResponseReconnectsOnlyOnce() throws Exception {
        var requests = new AtomicInteger();
        try (var server = new Loopback(exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.close();
        }); var gateway = new CommerceDesignImageGateway(server.url(), "unit-only")) {
            assertThrows(ResourceAccessException.class, () -> generate(gateway, "dropped_attempt"));
            assertEquals(2, requests.get());
        }
    }

    @Test void droppedRuntimeResponseRecoversItsSavedResultWithoutAnotherModelCall() throws Exception {
        var runtimeRequests=new AtomicInteger();var modelCalls=new AtomicInteger();
        var bodies=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var operations=new ConcurrentHashMap<String,String>();
        try(var server=new Loopback(exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            bodies.add(body);
            var payload=new tools.jackson.databind.ObjectMapper().readTree(body);
            String operationId=payload.path("progressRequestId").asText();
            String savedResult=operations.computeIfAbsent(operationId,key->{modelCalls.incrementAndGet();return RESULT;});
            if(runtimeRequests.incrementAndGet()==1){exchange.close();return;}
            reply(exchange,200,savedResult);
        });var gateway=new CommerceDesignImageGateway(server.url(),"unit-only")){
            var response=generate(gateway,"cached_operation");
            assertEquals("aGVsbG8=",response.artifact().dataBase64());
            assertEquals(2,runtimeRequests.get());
            assertEquals(1,modelCalls.get(),"Reconnection must reuse the completed internal operation");
            assertEquals(List.of("cached_operation"),List.copyOf(operations.keySet()));
            assertEquals(bodies.get(0),bodies.get(1),"Every model argument and the operation ID must remain unchanged");
        }
    }

    @Test void reconnectLeaseTimeoutDoesNotMisclassifyTheOriginalSubmissionAsUnsent(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new CommerceDesignImageGateway(builder,"http://runtime","unit-only");
        server.expect(requestTo("http://runtime/v1/images/generate"))
                .andExpect(jsonPath("$.progressRequestId").value("uncertain_operation"))
                .andRespond(withException(new IOException("runtime response disconnected")));
        server.expect(requestTo("http://runtime/v1/images/generate"))
                .andExpect(jsonPath("$.progressRequestId").value("uncertain_operation"))
                .andRespond(withException(new ConnectionRequestTimeoutException("reconnect lease expired")));
        var failure=assertThrows(ResourceAccessException.class,()->generate(gateway,"uncertain_operation"));
        assertTrue(failure.getMessage().contains("runtime response disconnected"));
        assertEquals(1,failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("reconnect lease expired"));
        server.verify();
    }

    @Test void fixedRuntimeEndpointNeverFollowsRedirects() throws Exception {
        var redirected = new AtomicInteger();
        try (var server = new Loopback(exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.getResponseHeaders().set("Location", "/redirected"); reply(exchange, 302, "{}");
        }); var gateway = new CommerceDesignImageGateway(server.url(), "unit-only")) {
            server.http.createContext("/redirected", exchange -> { redirected.incrementAndGet(); reply(exchange, 200, RESULT); });
            generate(gateway, "redirect_attempt");
            assertEquals(0, redirected.get());
        }
    }

    @Test void runtimeCookiesAreNotPersistedBetweenImagePosts() throws Exception {
        var cookie = new AtomicReference<String>(); var requests = new AtomicInteger();
        try (var server = new Loopback(exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet(); cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().set("Set-Cookie", "image-session=private; Path=/"); reply(exchange, 200, RESULT);
        }); var gateway = new CommerceDesignImageGateway(server.url(), "unit-only")) {
            generate(gateway, "cookie_attempt_1"); generate(gateway, "cookie_attempt_2");
            assertEquals(2, requests.get()); assertNull(cookie.get());
        }
    }

    @Test void operationLookupReadsCompletedFailedAndMissingResultsWithoutGenerating() throws Exception {
        var posts=new AtomicInteger();var reads=new AtomicInteger();
        var replyBody=new AtomicReference<>("{\"status\":\"COMPLETED\",\"active\":false,\"updatedAt\":1234,\"response\":"+RESULT+",\"error\":null}");
        var code=new AtomicInteger(200);
        try(var server=new Loopback(exchange->{posts.incrementAndGet();reply(exchange,500,"{}");});
            var gateway=new CommerceDesignImageGateway(server.url(),"operation-auth")){
            server.http.createContext("/v1/images/operations",exchange->{
                assertEquals("GET",exchange.getRequestMethod());assertEquals("operation-auth",exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                assertEquals("requestId=detail_grid_1",exchange.getRequestURI().getRawQuery());
                reads.incrementAndGet();reply(exchange,code.get(),replyBody.get());
            });
            var completed=gateway.operation("detail_grid_1");
            assertEquals("COMPLETED",completed.status());assertFalse(completed.active());assertEquals(1234,completed.updatedAt());
            assertEquals("aGVsbG8=",completed.response().artifact().dataBase64());assertNull(completed.error());
            replyBody.set("{\"status\":\"FAILED\",\"active\":false,\"updatedAt\":2345,\"response\":null,\"error\":{\"requestState\":\"ACCEPTED_OR_UNKNOWN\",\"networkRetryCount\":2,\"retryAuthorized\":true}}");
            var failed=gateway.operation("detail_grid_1");assertEquals("FAILED",failed.status());assertNull(failed.response());
            assertEquals("ACCEPTED_OR_UNKNOWN",failed.error().get("requestState"));assertEquals(2,failed.error().get("networkRetryCount"));
            code.set(404);replyBody.set("{\"status\":\"NOT_FOUND\",\"active\":false,\"updatedAt\":0,\"response\":null,\"error\":null}");
            assertNull(gateway.operation("detail_grid_1"));
            for(String invalid:List.of("", "short", "../detail_grid"))assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->gateway.operation(invalid));
            assertEquals(3,reads.get());assertEquals(0,posts.get());
        }
    }

    @Test void operationLookupDoesNotWaitForTheLongGenerationConnectionPool() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var server=new Loopback(exchange->{exchange.getRequestBody().readAllBytes();entered.countDown();await(release);reply(exchange,200,RESULT);});
            var gateway=new CommerceDesignImageGateway(new CommerceImageHttpTransport(1,Timeout.ofSeconds(8),Timeout.ofSeconds(15)),server.url(),"unit-only");
            var executor=Executors.newVirtualThreadPerTaskExecutor()){
            server.http.createContext("/v1/images/operations",exchange->reply(exchange,200,"{\"status\":\"RUNNING\",\"active\":true,\"updatedAt\":4567,\"response\":null,\"error\":null}"));
            var generating=executor.submit(()->generate(gateway,"long_operation"));
            try{
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                var operation=assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->gateway.operation("long_operation"));
                assertTrue(operation.active());assertEquals("RUNNING",operation.status());assertNull(operation.response());
            }finally{release.countDown();}
            assertNotNull(generating.get(5,TimeUnit.SECONDS).artifact());
        }
    }

    @Test void operationReadTimeoutIsUnderTenSecondsAndDoesNotRetryOrGenerate() throws Exception {
        var requests=new AtomicInteger();var posts=new AtomicInteger();var release=new CountDownLatch(1);
        try(var server=new Loopback(exchange->{posts.incrementAndGet();reply(exchange,200,RESULT);});
            var gateway=new CommerceDesignImageGateway(server.url(),"unit-only")){
            server.http.createContext("/v1/images/operations",exchange->{requests.incrementAndGet();await(release);exchange.close();});
            try{
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),()->assertThrows(ResourceAccessException.class,()->gateway.operation("slow_operation")));
                assertEquals(1,requests.get());assertEquals(0,posts.get());
            }finally{release.countDown();}
        }
    }

    @Test void operationLookupNeverFollowsRedirects() throws Exception {
        var redirected=new AtomicInteger();
        try(var server=new Loopback(exchange->reply(exchange,500,"{}"));var gateway=new CommerceDesignImageGateway(server.url(),"unit-only")){
            server.http.createContext("/v1/images/operations",exchange->{exchange.getResponseHeaders().set("Location","/operation-redirect");reply(exchange,302,"{}");});
            server.http.createContext("/operation-redirect",exchange->{redirected.incrementAndGet();reply(exchange,200,"{}");});
            assertThrows(org.springframework.web.client.RestClientException.class,()->gateway.operation("redirect_operation"));
            assertEquals(0,redirected.get());
        }
    }

    private static CommerceDesignImageGateway.Response generate(CommerceDesignImageGateway gateway, String attempt) {
        return gateway.generateArtwork("unit-only", attempt, MODEL, List.of());
    }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("test latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }
    private static class Loopback implements AutoCloseable {
        final HttpServer http; final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Loopback(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); http.setExecutor(executor);
            http.createContext("/v1/images/generate", handler); http.start();
        }
        String url() { return "http://127.0.0.1:" + http.getAddress().getPort(); }
        @Override public void close() { http.stop(0); executor.shutdownNow(); }
    }
}
