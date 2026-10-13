package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCaptureDetailImagePlanTest {
    private final SafeProductImageUrl validator = new SafeProductImageUrl();
    private static final String ASSET = "tos-maliva-i-abcdef/1234567890abcdefABCDEF";

    @Test
    void keepsPersistedImagesUnchangedAndAppendsAfterLargestSortOrder() {
        ProductCaptureImage detail = stored("https://cdn.example.com/detail.webp", "DETAIL", 9);
        detail.failed("existing failure");
        ProductCaptureImage main = stored("https://cdn.example.com/main.webp", "MAIN", 2);
        main.processed("https://cdn.example.com/processed.webp");
        var detailUpdatedAt = detail.getUpdatedAt();
        var mainUpdatedAt = main.getUpdatedAt();

        var plan = ProductCaptureImagePlan.supplementalDetails(List.of(detail, main), List.of(
                Map.of("url", "https://cdn.example.com/new.webp", "role", " DESCRIPTION ", "position", 0),
                Map.of("src", "https://cdn.example.com/newer.webp", "type", "detail", "sortOrder", -1)
        ), List.of(), validator);

        assertEquals(List.of(9, 2, 10, 11), plan.images().stream().map(image -> image.get("position")).toList());
        assertEquals(List.of("DETAIL", "MAIN", "DETAIL", "DETAIL"),
                plan.images().stream().map(image -> image.get("role")).toList());
        assertEquals(List.of(10, 11), plan.additions().stream().map(image -> image.get("sortOrder")).toList());
        assertEquals(main.getOriginalUrl(), plan.images().get(1).get("url"));
        assertEquals(main.getOriginalUrl(), plan.images().get(1).get("originalUrl"));
        assertEquals("saved alt", plan.images().get(1).get("alt"));
        assertEquals("image/webp", plan.images().get(1).get("mimeType"));
        assertEquals("FAILED", detail.getStatus());
        assertEquals("existing failure", detail.getErrorMessage());
        assertEquals(detailUpdatedAt, detail.getUpdatedAt());
        assertEquals("PROCESSED", main.getStatus());
        assertEquals("https://cdn.example.com/processed.webp", main.getProcessedUrl());
        assertEquals(mainUpdatedAt, main.getUpdatedAt());
        assertEquals("{\"preserved\":true}", main.getSourceJson());
        assertTrue(plan.requestedDetails());
        assertEquals(4, plan.completeness().get("expectedCount"));
        assertEquals(Map.of("MAIN", 1, "GALLERY", 0, "VARIANT", 0, "DETAIL", 3, "SIZE_CHART", 0),
                plan.completeness().get("roleCounts"));
    }

    @Test
    void deduplicatesByteDanceDeliveryAliasesAgainstStoredImagesAndWithinRequest() {
        String original = "https://p16-oec-va.ibyteimg.com/" + ASSET + "~tplv-origin.jpeg?x=1";
        String alias = "https://p19-oec-va.byteimg.com/" + ASSET + "~tplv-resize.webp?x=2";
        var plan = ProductCaptureImagePlan.supplementalDetails(
                List.of(stored(original, "GALLERY", 5)), List.of(
                        Map.of("url", alias, "role", "DETAIL"),
                        Map.of("url", "https://p21-oec-va.ibyteimg.com/" + ASSET + "?download=1", "role", "DETAIL")
                ), List.of(Map.of("imageUrl", alias)), validator);

        assertTrue(plan.requestedDetails());
        assertTrue(plan.additions().isEmpty());
        assertEquals(original, plan.images().getFirst().get("url"));
        assertEquals("GALLERY", plan.images().getFirst().get("role"));
        assertEquals(1L, plan.completeness().get("coveredSkuImageCount"));
        assertEquals(true, plan.completeness().get("complete"));

        var newPlan = ProductCaptureImagePlan.supplementalDetails(List.of(), List.of(
                Map.of("url", original, "role", "DETAIL"), Map.of("url", alias, "role", "DETAIL")
        ), List.of(), validator);
        assertEquals(1, newPlan.additions().size());
        assertEquals("DETAIL", newPlan.images().getFirst().get("role"));
    }

    @Test
    void ordinaryHostsQueriesAndUnrecognizedByteDancePathsRemainDistinct() {
        List<String> urls = List.of(
                "https://cdn.example.com/" + ASSET + "?x=1",
                "https://cdn.example.com/" + ASSET + "?x=2",
                "https://other.example.com/" + ASSET + "?x=1",
                "https://ibyteimg.com.example.com/" + ASSET + "?x=1",
                "https://p16.ibyteimg.com/short.jpg?x=1",
                "https://p16.ibyteimg.com/short.jpg?x=2"
        );
        var plan = ProductCaptureImagePlan.supplementalDetails(List.of(),
                urls.stream().map(url -> Map.of("url", url, "role", "DETAIL")).toList(), List.of(), validator);

        assertEquals(urls, plan.images().stream().map(image -> image.get("url")).toList());
        assertEquals(6, plan.additions().size());
        assertEquals("invalid URL", ProductCaptureImagePlan.assetKey("invalid URL"));
    }

    @Test
    void ignoresNonDetailMapsStringsAndOtherInputValuesWithoutAddingSkuImages() {
        List<Object> supplied = Arrays.asList(
                "https://cdn.example.com/string.webp", null, 42,
                Map.of("url", "https://localhost/ignored", "role", "MAIN"),
                Map.of("url", "https://localhost/ignored", "role", "VARIANT"),
                Map.of("url", "https://localhost/ignored", "role", "GALLERY"),
                Map.of("url", "https://localhost/ignored"));
        var plan = ProductCaptureImagePlan.supplementalDetails(
                List.of(stored("https://cdn.example.com/main.webp", "MAIN", 0)), supplied,
                List.of(Map.of("imageUrl", "https://cdn.example.com/sku.webp")), validator);

        assertFalse(plan.requestedDetails());
        assertTrue(plan.additions().isEmpty());
        assertEquals(1, plan.images().size());
        assertEquals(1, plan.completeness().get("capturedCount"));
        assertEquals(1, plan.completeness().get("expectedCount"));
        assertEquals(1, plan.completeness().get("requiredSkuImageCount"));
        assertEquals(0L, plan.completeness().get("coveredSkuImageCount"));
        assertEquals(List.of("VARIANT_IMAGE_COVERAGE_TRUNCATED"), plan.completeness().get("issues"));
        assertEquals(Map.of("MAIN", 1, "GALLERY", 0, "VARIANT", 0, "DETAIL", 0, "SIZE_CHART", 0),
                plan.completeness().get("selectedRoleCounts"));
    }

    @Test
    void validatesDetailImageUrlsEvenWhenNoOtherAssetWouldChange() {
        for (String url : List.of("https://127.0.0.1/image", "http://cdn.example.com/image", "file:///tmp/image")) {
            assertThrows(BusinessException.class, () -> ProductCaptureImagePlan.supplementalDetails(
                    List.of(), List.of(Map.of("url", url, "role", "DETAIL")), List.of(), validator));
        }
        assertThrows(BusinessException.class, () -> ProductCaptureImagePlan.supplementalDetails(
                List.of(), List.of(Map.of("role", "DETAIL")), List.of(), validator));
        assertThrows(BusinessException.class, () -> ProductCaptureImagePlan.supplementalDetails(
                List.of(), Map.of("url", "https://cdn.example.com/image"), List.of(), validator));
    }

    @Test
    void refusesOverflowWithoutDroppingPersistedOrRequestedImages() {
        List<ProductCaptureImage> existing = new ArrayList<>();
        for (int i = 0; i < ProductCaptureImagePlan.MAX_IMAGES; i++) {
            existing.add(stored("https://cdn.example.com/" + i + ".webp", i == 0 ? "MAIN" : "DETAIL", i));
        }
        var duplicateOnly = ProductCaptureImagePlan.supplementalDetails(existing,
                List.of(Map.of("url", existing.getFirst().getOriginalUrl(), "role", "DETAIL")), List.of(), validator);
        assertTrue(duplicateOnly.additions().isEmpty());
        assertEquals(120, duplicateOnly.images().size());

        BusinessException overflow = assertThrows(BusinessException.class, () -> ProductCaptureImagePlan.supplementalDetails(existing,
                List.of(Map.of("url", "https://cdn.example.com/overflow.webp", "role", "DETAIL")), List.of(), validator));
        assertEquals(HttpStatus.BAD_REQUEST, overflow.getStatus());
        assertEquals(120, existing.size());
        assertTrue(existing.stream().allMatch(image -> "PENDING".equals(image.getStatus())));
    }

    @Test
    void preservesExistingDuplicateRowsAndRejectsOversizedExistingCollections() {
        String url = "https://cdn.example.com/existing.webp";
        var plan = ProductCaptureImagePlan.supplementalDetails(
                List.of(stored(url, "MAIN", 0), stored(url, "DETAIL", 7)),
                List.of(Map.of("url", url, "role", "DETAIL")), List.of(), validator);
        assertEquals(2, plan.images().size());
        assertEquals(List.of(0, 7), plan.images().stream().map(image -> image.get("position")).toList());
        assertTrue(plan.additions().isEmpty());

        List<ProductCaptureImage> oversized = new ArrayList<>();
        for (int i = 0; i <= ProductCaptureImagePlan.MAX_IMAGES; i++) oversized.add(stored(url, "DETAIL", i));
        BusinessException overflow = assertThrows(BusinessException.class,
                () -> ProductCaptureImagePlan.supplementalDetails(oversized, null, List.of(), validator));
        assertEquals(HttpStatus.BAD_REQUEST, overflow.getStatus());
    }

    @Test
    void rejectsSortOrderOverflowInsteadOfWrappingToNegativePositions() {
        BusinessException overflow = assertThrows(BusinessException.class,
                () -> ProductCaptureImagePlan.supplementalDetails(
                        List.of(stored("https://cdn.example.com/main.webp", "MAIN", Integer.MAX_VALUE)),
                        List.of(Map.of("url", "https://cdn.example.com/detail.webp", "role", "DETAIL")),
                        List.of(), validator));
        assertEquals(HttpStatus.BAD_REQUEST, overflow.getStatus());
    }

    @Test
    void countsSkuAssetsOnceAcrossByteDanceAliasesWithoutInflatingCapturedAssets() {
        String url = "https://p16.ibyteimg.com/" + ASSET + "~tplv-original.webp";
        String alias = "https://p19.byteimg.com/" + ASSET + "~tplv-crop.webp?x=1";
        var plan = ProductCaptureImagePlan.supplementalDetails(List.of(stored(url, "VARIANT", 4)), List.of(),
                List.of(Map.of("imageUrl", alias), Map.of("imageUrl", url),
                        Map.of("imageUrl", "https://cdn.example.com/missing.webp")), validator);

        assertEquals(2, plan.completeness().get("requiredSkuImageCount"));
        assertEquals(1L, plan.completeness().get("coveredSkuImageCount"));
        assertEquals(1, plan.completeness().get("capturedCount"));
        assertEquals(1, plan.completeness().get("expectedCount"));
        assertEquals(false, plan.completeness().get("complete"));
    }

    @Test
    void reportsMissingImagesAndDoesNotPromoteFirstDetailToMain() {
        var empty = ProductCaptureImagePlan.supplementalDetails(List.of(), null, List.of(), validator);
        assertEquals(List.of("IMAGES_MISSING"), empty.completeness().get("issues"));
        assertEquals(false, empty.completeness().get("complete"));

        var detailOnly = ProductCaptureImagePlan.supplementalDetails(List.of(),
                List.of(Map.of("url", "https://cdn.example.com/detail.webp", "role", "DETAIL")), List.of(), validator);
        assertEquals("DETAIL", detailOnly.images().getFirst().get("role"));
        assertEquals(0, detailOnly.images().getFirst().get("position"));
        assertEquals(true, detailOnly.completeness().get("complete"));
    }

    private ProductCaptureImage stored(String url, String role, int position) {
        return new ProductCaptureImage("org", "capture", url, role, "saved alt", position,
                "image/webp", "{\"preserved\":true}");
    }
}
