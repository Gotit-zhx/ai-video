package com.yingdao.aicommerce.product.application;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductListingTitleServiceTest {
    @Test void modelConfigurationFailuresRemainRecoverableCaptureErrors() {
        var models = mock(ModelConfigService.class);
        var images = mock(ProductListingTitleImages.class);
        when(images.captured(any())).thenReturn(List.of(Map.of("role","MAIN","mimeType","image/jpeg","data","fixture")));
        var service = new ProductListingTitleService(models, new ObjectMapper(), images, "http://127.0.0.1:1", "fixture");
        when(models.driverConnection("org-a")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.generate("org-a", Map.of(), "SHOPEE"));
        when(models.driverConnection("org-a")).thenThrow(new IllegalStateException("unusable fixture key"));
        var error = assertThrows(BusinessException.class,
                () -> service.generate("org-a", Map.of(), "SHOPEE"));
        assertFalse(error.getMessage().contains("fixture key"));
    }

    @Test void piRequestUsesTenantSelectedModelOriginalFactsAndValidatedResponse() throws Exception {
        var models = mock(ModelConfigService.class);
        var model = new ModelConfigService.RuntimeModelConnection(
                "model-a", "https://fixture.invalid/v1", "gpt-5.5", "OPENAI_COMPATIBLE", "fixture", "fixture-key");
        when(models.driverConnection("org-a")).thenReturn(Optional.of(model));
        var images = mock(ProductListingTitleImages.class);
        var photos = List.<Map<String,Object>>of(Map.of("role","MAIN","mimeType","image/jpeg","data","fixture"));
        when(images.captured(any())).thenReturn(photos);
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
            var service = new ProductListingTitleService(models, new ObjectMapper(), images,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "internal-fixture-token");
            assertEquals("Women Cotton Short Sleeve Casual T-shirt",
                    service.generate("org-a", Map.of("originalTitle","Misleading phone title","title","Old optimized title",
                            "categoryName","Women > T-shirts","attributes",Map.of("Neckline", "Others"),
                            "description","Cotton knit tee","skus",List.of(Map.of("attributes",Map.of("Color","Black")))), "SHOPEE"));
            assertEquals("internal-fixture-token", token.get());
            var input = (Map<?, ?>) body.get().get("input");
            assertFalse(input.containsKey("originalTitle"));
            assertFalse(input.containsKey("title"));
            assertEquals("Cotton knit tee",input.get("description"));
            assertEquals(List.of(Map.of("Color","Black")),input.get("skuOptions"));
            assertEquals(photos,body.get().get("images"));
            assertEquals(Map.of("Neckline", "Others"), input.get("attributes"));
            assertEquals("gpt-5.5", ((Map<?, ?>) body.get().get("model")).get("modelId"));
            response.set("{\"result\":{\"title\":\"<b>Unsafe title</b>\"}}");
            assertThrows(BusinessException.class,
                    () -> service.generate("org-a", Map.of(), "SHOPEE"));
            response.set("{\"result\":{\"title\":\"Women Cotton Short Sleeve Casual T-shirt\"}}");
            service.generate("org-a", Map.of("listingTemplate",Map.of("enabled",true),"description","Generic bodycon promise",
                    "originalDescription","Original woven shirt description"),"SHOPEE");
            assertEquals("Original woven shirt description",((Map<?,?>)body.get().get("input")).get("description"));
            service.generate("org-a", Map.of("listingTemplate",Map.of("enabled",true),"description","Generic bodycon promise"),"SHOPEE");
            assertEquals("",((Map<?,?>)body.get().get("input")).get("description"));
        } finally { server.stop(0); }
    }
}
