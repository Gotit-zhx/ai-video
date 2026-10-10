package com.yingdao.aicommerce.product.application;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductListingTitleServiceTest {
    @Test void modelConfigurationFailuresRemainRecoverableCaptureErrors() {
        var models = mock(ModelConfigService.class);
        var service = new ProductListingTitleService(models, new ObjectMapper(), "http://127.0.0.1:1", "fixture");
        when(models.driverConnection("org-a")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.generate("org-a", "Original title", null, null, "SHOPEE"));
        when(models.driverConnection("org-a")).thenThrow(new IllegalStateException("unusable fixture key"));
        var error = assertThrows(BusinessException.class,
                () -> service.generate("org-a", "Original title", null, null, "SHOPEE"));
        assertFalse(error.getMessage().contains("fixture key"));
    }

    @Test void piRequestUsesTenantSelectedModelOriginalFactsAndValidatedResponse() throws Exception {
        var models = mock(ModelConfigService.class);
        var model = new ModelConfigService.RuntimeModelConnection(
                "model-a", "https://fixture.invalid/v1", "gpt-5.5", "OPENAI_COMPATIBLE", "fixture", "fixture-key");
        when(models.driverConnection("org-a")).thenReturn(Optional.of(model));
        var body = new AtomicReference<Map>();
        var token = new AtomicReference<String>();
        var response = new AtomicReference<>("{\"result\":{\"title\":\"Women Cotton Short Sleeve Casual T-shirt\"}}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/product-listing/title", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            body.set(new ObjectMapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var service = new ProductListingTitleService(models, new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "internal-fixture-token");
            assertEquals("Women Cotton Short Sleeve Casual T-shirt",
                    service.generate("org-a", "COD Women Cotton Tee", "Women > T-shirts",
                            Map.of("Neckline", "Others"), "SHOPEE"));
            assertEquals("internal-fixture-token", token.get());
            var input = (Map<?, ?>) body.get().get("input");
            assertEquals("COD Women Cotton Tee", input.get("originalTitle"));
            assertEquals(Map.of("Neckline", "Others"), input.get("attributes"));
            assertEquals("gpt-5.5", ((Map<?, ?>) body.get().get("model")).get("modelId"));
            response.set("{\"result\":{\"title\":\"<b>Unsafe title</b>\"}}");
            assertThrows(BusinessException.class,
                    () -> service.generate("org-a", "Original title", null, null, "SHOPEE"));
        } finally { server.stop(0); }
    }
}
