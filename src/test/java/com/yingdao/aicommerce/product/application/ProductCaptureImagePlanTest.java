package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCaptureImagePlanTest {
    private final SafeProductImageUrl safeImageUrl = new SafeProductImageUrl();

    @Test
    void mergesSkuImagesAndKeepsPublishingRolePriority() {
        List<Map<String, Object>> images = List.of(
                image("main", "MAIN"),
                image("gallery", "GALLERY"),
                image("detail", "DETAIL")
        );
        List<Map<String, Object>> skus = List.of(
                sku("red", "https://cdn.example.com/red.webp", "VARIANT_OPTION", false),
                sku("red-m", "https://cdn.example.com/red.webp", "VARIANT_OPTION", false),
                sku("default", "https://cdn.example.com/main.webp", "PRODUCT_MAIN", true)
        );

        ProductCaptureImagePlan.Plan plan = ProductCaptureImagePlan.prepare(
                images, skus, safeImageUrl
        );

        assertEquals(List.of("MAIN", "VARIANT", "GALLERY", "DETAIL"),
                plan.images().stream().map(image -> image.get("role")).toList());
        assertEquals(4, plan.images().size());
        assertEquals(true, plan.completeness().get("complete"));
        assertEquals(2, plan.completeness().get("requiredSkuImageCount"));
        assertEquals(2L, plan.completeness().get("coveredSkuImageCount"));
    }

    @Test
    void overThirtyImagesRetainsMaximumVariantCoverageAndBlocksReview() {
        List<Map<String, Object>> images = List.of(
                image("main", "MAIN"),
                image("gallery", "GALLERY"),
                image("detail", "DETAIL")
        );
        List<Map<String, Object>> skus = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            skus.add(sku("color-" + index,
                    "https://cdn.example.com/color-" + index + ".webp", "MODEL", false));
        }

        ProductCaptureImagePlan.Plan plan = ProductCaptureImagePlan.prepare(
                images, skus, safeImageUrl
        );

        assertEquals(30, plan.images().size());
        assertEquals("MAIN", plan.images().getFirst().get("role"));
        assertEquals(29, plan.images().stream().filter(image -> "VARIANT".equals(image.get("role"))).count());
        assertFalse((Boolean) plan.completeness().get("complete"));
        @SuppressWarnings("unchecked")
        List<String> issues = (List<String>) plan.completeness().get("issues");
        assertTrue(issues.contains("IMAGE_LIMIT_EXCEEDED"));
        assertTrue(issues.contains("VARIANT_IMAGE_COVERAGE_TRUNCATED"));
        assertTrue(issues.contains("GALLERY_IMAGE_COVERAGE_TRUNCATED"));
        assertTrue(issues.contains("DETAIL_IMAGE_COVERAGE_TRUNCATED"));
        assertEquals(31, plan.completeness().get("requiredSkuImageCount"));
        assertEquals(29L, plan.completeness().get("coveredSkuImageCount"));
    }

    private Map<String, Object> image(String name, String role) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("url", "https://cdn.example.com/" + name + ".webp");
        value.put("role", role);
        return value;
    }

    private Map<String, Object> sku(String name, String imageUrl, String source, boolean inherited) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("variationName", name);
        value.put("imageUrl", imageUrl);
        value.put("imageSource", source);
        value.put("imageInherited", inherited);
        return value;
    }
}
