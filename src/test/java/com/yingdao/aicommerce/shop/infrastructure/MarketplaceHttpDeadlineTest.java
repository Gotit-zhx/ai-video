package com.yingdao.aicommerce.shop.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

class MarketplaceHttpDeadlineTest {
    @ParameterizedTest @ValueSource(strings = {"GET", "POST"})
    void deadlineIncludesStalledBodyAfterHeadersAndCancelsWithoutReplay(String method) throws Exception {
        var headers = new CountDownLatch(1); var release = new CountDownLatch(1); var requests = new AtomicInteger();
        var pending = new AtomicReference<CompletableFuture<HttpResponse<String>>>();
        try (var server = new Loopback(exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write("{\"response\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush(); headers.countDown(); await(release); exchange.close();
        }); var client = HttpClient.newHttpClient()) {
            MarketplaceHttp transport = recordingTransport(client, pending);
            var request = HttpRequest.newBuilder(server.uri()).timeout(Duration.ofMillis(400))
                    .method(method, "GET".equals(method) ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("unit-only"))
                    .build();
            long started = System.nanoTime();
            try {
                var error = assertThrows(MarketplaceApiException.class, () -> transport.sendJson(request));
                assertTrue(headers.await(1, TimeUnit.SECONDS), "The timeout must happen after response headers arrived");
                assertInstanceOf(HttpTimeoutException.class, error.getCause());
                assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_000, "Body must not wait for the server to finish");
                assertCancelledExchange(pending.get());
                assertEquals("店铺官方接口完整响应超时，请稍后重试", error.getMessage());
                assertEquals(1, requests.get(), "The common transport must never replay a possibly accepted POST");
                assertFalse(error.getMessage().contains("http:"));
            } finally { release.countDown(); }
        }
    }

    @Test void completeResponseAndIoFailureKeepTheirExistingSemantics() throws Exception {
        var calls = new AtomicInteger();
        try (var server = new Loopback(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) reply(exchange, 200, "{\"response\":{\"ok\":true}}");
            else exchange.close();
        }); var client = HttpClient.newHttpClient()) {
            var transport = new MarketplaceHttp(new ObjectMapper(), client);
            assertEquals(Map.of("response", Map.of("ok", true)), transport.sendJson(request(server.uri(), 2_000)));
            var failure = assertThrows(MarketplaceApiException.class, () -> transport.sendJson(HttpRequest.newBuilder(server.uri())
                    .timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString("unit-only")).build()));
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals("店铺官方接口请求失败", failure.getMessage());
            assertEquals(2, calls.get());
        }
    }

    @Test void interruptCancelsBodyDownloadAndRestoresThreadInterruptFlag() throws Exception {
        var headers = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pending = new AtomicReference<CompletableFuture<HttpResponse<String>>>();
        var error = new AtomicReference<MarketplaceApiException>(); var interrupted = new AtomicBoolean();
        try (var server = new Loopback(exchange -> {
            exchange.sendResponseHeaders(200, 100); exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush(); headers.countDown(); await(release); exchange.close();
        }); var client = HttpClient.newHttpClient()) {
            var transport = recordingTransport(client, pending);
            Thread worker = Thread.ofPlatform().daemon().unstarted(() -> {
                try { transport.sendJson(request(server.uri(), 30_000)); }
                catch (MarketplaceApiException failure) { error.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            worker.start();
            try {
                assertTrue(headers.await(3, TimeUnit.SECONDS)); worker.interrupt(); worker.join(2_000);
                assertFalse(worker.isAlive()); assertTrue(interrupted.get());
                assertNotNull(error.get()); assertInstanceOf(InterruptedException.class, error.get().getCause());
                assertEquals("店铺官方接口请求被中断", error.get().getMessage());
                assertCancelledExchange(pending.get());
            } finally { release.countDown(); worker.interrupt(); worker.join(2_000); }
        }
    }

    @Test void publicOperationsKeepGetNinetyPostSixtyAndMultipartNinetySecondBudgets() {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn("{}");
        var timeouts = new java.util.ArrayList<Duration>();
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(call -> { HttpRequest request = call.getArgument(0); timeouts.add(request.timeout().orElseThrow()); return CompletableFuture.completedFuture(response); });
        var transport = new MarketplaceHttp(new ObjectMapper(), client);
        var uri = URI.create("https://mock.invalid/api");
        transport.get(uri, Map.of()); transport.postJson(uri, Map.of(), "{}");
        transport.postMultipart(uri, Map.of(), "image", "image.png", "image/png", new byte[]{1}, Map.of());
        assertEquals(List.of(Duration.ofSeconds(90), Duration.ofSeconds(60), Duration.ofSeconds(90)), timeouts);
        verify(client, times(3)).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    private MarketplaceHttp recordingTransport(HttpClient actual, AtomicReference<CompletableFuture<HttpResponse<String>>> pending) {
        HttpClient facade = mock(HttpClient.class);
        when(facade.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(call -> {
                    CompletableFuture<HttpResponse<String>> future = actual.sendAsync(call.getArgument(0), call.getArgument(1));
                    @SuppressWarnings("unchecked") CompletableFuture<HttpResponse<String>> recorded = mock(CompletableFuture.class, delegatesTo(future));
                    pending.set(recorded); return recorded;
                });
        return new MarketplaceHttp(new ObjectMapper(), facade);
    }
    private static void assertCancelledExchange(CompletableFuture<HttpResponse<String>> future) {
        // JDK cancellation may finish the HTTP future with a nested cancellation exception
        // before CompletableFuture.cancel changes its flag, so isCancelled alone is unreliable.
        // The recorder forwards this exact cancel call to the actual HTTP exchange.
        verify(future).cancel(true);
        assertTrue(future.isCompletedExceptionally(), "The underlying HTTP exchange must have terminated");
    }
    private static HttpRequest request(URI uri, long timeoutMillis) { return HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMillis)).GET().build(); }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test gate expired"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
    }
    private static class Loopback implements AutoCloseable {
        final HttpServer server; final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        Loopback(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
            server.createContext("/", handler); server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
        @Override public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
