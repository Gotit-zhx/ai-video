package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.product.application.ProductCaptureService;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCaptureResponseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listAndDetailExposeTheSameAuthoritativeOriginAtTopLevel() {
        ProductCapture capture = new ProductCapture(
                "org-a", "user-1", "capture-origin-response", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "描述", null, null, "PHP", BigDecimal.TEN, BigDecimal.TEN, "{}", "{}"
        );
        for (String origin : List.of("CAPTURED", "COMMERCE_GENERATED")) {
            var detail = ProductCaptureResponse.from(new ProductCaptureService.CaptureDetail(
                    capture, List.of(), List.of(), List.of(), Map.of(), Map.of(), origin), objectMapper);
            var page = ProductCapturePageResponse.from(new ProductCaptureService.CapturePage(
                    List.of(new ProductCaptureService.CaptureSummary(capture, null, origin)),
                    0, 30, 1, 1, Map.of()));

            assertEquals(origin, detail.originType());
            assertEquals(origin, page.content().getFirst().originType());
            assertEquals(origin, objectMapper.valueToTree(detail).path("originType").asText());
            assertEquals(origin, objectMapper.valueToTree(page).path("content").get(0).path("originType").asText());
            assertFalse(objectMapper.valueToTree(page).path("content").get(0).has("rawData"));
        }
        assertEquals("CAPTURED", new ProductCaptureService.CaptureDetail(
                capture, List.of(), List.of(), List.of(), Map.of(), Map.of()).originType());
        assertEquals("CAPTURED", new ProductCaptureService.CaptureSummary(capture, null).originType());
    }

    @Test
    void inconsistentSkuCountsRemainIncompleteInStableResponse() {
        ProductCaptureResponse response = response(2, 1, true);

        assertFalse(response.skuCompleteness().complete());
        assertEquals("NEEDS_ENRICHMENT", response.completeness());
        assertTrue(response.missingFields().contains("SKU_INCOMPLETE"));
        assertTrue(response.normalizedData().containsKey("skuCompleteness"));
    }

    @Test
    void fullyVerifiedSkuSetCanBeComplete() {
        ProductCaptureResponse response = response(1, 1, true);

        assertTrue(response.skuCompleteness().complete());
        assertEquals("COMPLETE", response.completeness());
        assertFalse(response.missingFields().contains("SKU_INCOMPLETE"));
        assertTrue(response.skuCompleteness().hasVariants());
        assertEquals(2, response.skuCompleteness().variantDimensionCount());
        assertEquals(List.of(), response.skuCompleteness().issues());
        assertEquals("https://cdn.example.com/model-1.webp", response.skus().getFirst().imageUrl());
        assertEquals("MODEL", response.skus().getFirst().imageSource());
        assertNull(response.skus().getFirst().stock());
        assertFalse(response.missingFields().contains("STOCK"));
    }

    @Test
    void stockOnlyLegacyIncompleteDeclarationIsReportedComplete() {
        ProductCaptureResponse response = response(
                1, 1, false, List.of("SKU_1_STOCK_MISSING")
        );

        assertTrue(response.skuCompleteness().complete());
        assertEquals(List.of(), response.skuCompleteness().issues());
        assertEquals("COMPLETE", response.completeness());
        assertFalse(response.missingFields().contains("STOCK"));
        assertFalse(response.missingFields().contains("SKU_INCOMPLETE"));
    }

    @Test
    void missingImageCompletenessMetadataIsNeverReportedComplete() {
        ProductCapture capture = new ProductCapture(
                "org-a", "user-1", "capture-response-missing-image-meta", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN,
                "{\"skuCompleteness\":{\"expectedCount\":1,\"capturedCount\":1,"
                        + "\"complete\":true,\"source\":\"DOM\",\"hasVariants\":false,"
                        + "\"variantDimensionCount\":0,\"issues\":[]}}",
                "{}"
        );
        ProductCaptureSku sku = new ProductCaptureSku(
                "org-a", capture.getId(), "model-1", "model-1", null, "默认规格",
                BigDecimal.TEN, BigDecimal.TEN, 5, "https://cdn.example.com/main.webp",
                "MODEL", "{}", "{}", 0
        );
        ProductCaptureImage image = new ProductCaptureImage(
                "org-a", capture.getId(), "https://cdn.example.com/main.webp",
                "MAIN", null, 0, "image/webp", "{}"
        );

        ProductCaptureResponse response = ProductCaptureResponse.from(
                new ProductCaptureService.CaptureDetail(
                        capture, List.of(sku), List.of(image), List.of(),
                        Map.of("skuCompleteness", Map.of(
                                "expectedCount", 1, "capturedCount", 1, "complete", true,
                                "source", "DOM", "hasVariants", false,
                                "variantDimensionCount", 0, "issues", List.of()
                        )), Map.of()
                ), objectMapper
        );

        assertEquals("NEEDS_ENRICHMENT", response.completeness());
        assertTrue(response.missingFields().contains("IMAGE_INCOMPLETE"));
    }

    private ProductCaptureResponse response(int expected, int captured, boolean claimedComplete) {
        return response(expected, captured, claimedComplete, List.of());
    }

    private ProductCaptureResponse response(int expected, int captured, boolean claimedComplete,
                                            List<String> issues) {
        String normalized = "{\"skuCompleteness\":{\"expectedCount\":" + expected
                + ",\"capturedCount\":" + captured + ",\"complete\":" + claimedComplete
                + ",\"source\":\"DOM\",\"hasVariants\":true,\"variantDimensionCount\":2,\"issues\":"
                + objectMapper.writeValueAsString(issues) + "},"
                + "\"imageCompleteness\":{\"expectedCount\":2,\"capturedCount\":2,"
                + "\"requiredSkuImageCount\":1,\"coveredSkuImageCount\":1,"
                + "\"complete\":true,\"issues\":[]}}";
        ProductCapture capture = new ProductCapture(
                "org-a", "user-1", "capture-response-key", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN, normalized, "{}"
        );
        ProductCaptureSku sku = new ProductCaptureSku(
                "org-a", capture.getId(), "model-1", "model-1", null, "红色 / M",
                BigDecimal.TEN, BigDecimal.TEN, null, "https://cdn.example.com/model-1.webp",
                "MODEL", "{\"颜色\":\"红色\",\"尺码\":\"M\"}", "{}", 0
        );
        ProductCaptureImage image = new ProductCaptureImage(
                "org-a", capture.getId(), "https://cdn.example.com/a.webp",
                "MAIN", null, 0, "image/webp", "{}"
        );
        ProductCaptureImage variantImage = new ProductCaptureImage(
                "org-a", capture.getId(), "https://cdn.example.com/model-1.webp",
                "VARIANT", "红色 / M", 1, "image/webp", "{}"
        );
        Map<String, Object> normalizedMap;
        try {
            normalizedMap = objectMapper.readValue(normalized,
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return ProductCaptureResponse.from(new ProductCaptureService.CaptureDetail(
                capture, List.of(sku), List.of(image, variantImage), List.of(), normalizedMap, Map.of()
        ), objectMapper);
    }
}
