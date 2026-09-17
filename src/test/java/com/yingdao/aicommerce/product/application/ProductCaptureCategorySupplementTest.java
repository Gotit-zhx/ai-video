package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.application.provider.ProductEnrichmentProvider;
import com.yingdao.aicommerce.product.application.provider.ProductEnrichmentRegistry;
import com.yingdao.aicommerce.product.application.provider.ProductPublishingRegistry;
import com.yingdao.aicommerce.product.application.provider.WatermarkRemovalRegistry;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureFieldSource;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import com.yingdao.aicommerce.product.mapper.ProductCaptureFieldSourceMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.product.mapper.ProductImageProcessingJobMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductCaptureCategorySupplementTest {
    private static final String SOURCE_URL = "https://shop.tiktok.com/view/product/1730135047201523374?region=MY";
    private static final String IDEMPOTENCY_KEY = "capture-category-supplement";
    private static final String CATEGORY = "Automotive & Motorcycle > Motorcycle Spare Parts > Exhaust Systems";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProductCaptureMapper captureMapper;
    private ProductCaptureSkuMapper skuMapper;
    private ProductCaptureImageMapper imageMapper;
    private ProductCaptureFieldSourceMapper fieldSourceMapper;
    private ProductEnrichmentRegistry enrichmentRegistry;
    private ProductCaptureService service;
    private ProductCapture capture;
    private List<ProductCaptureSku> preservedSkus;
    private ProductCaptureImage preservedImage;
    private Map<String, Object> preservedNormalized;

    @BeforeEach
    void setUp() {
        captureMapper = mock(ProductCaptureMapper.class);
        skuMapper = mock(ProductCaptureSkuMapper.class);
        imageMapper = mock(ProductCaptureImageMapper.class);
        fieldSourceMapper = mock(ProductCaptureFieldSourceMapper.class);
        enrichmentRegistry = mock(ProductEnrichmentRegistry.class);
        when(enrichmentRegistry.enrich(anyString(), any(), any()))
                .thenReturn(ProductEnrichmentProvider.EnrichmentResult.empty());
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(imageMapper.selectList(any())).thenReturn(List.of());
        when(fieldSourceMapper.selectList(any())).thenReturn(List.of());
        when(captureMapper.updateById(any(ProductCapture.class))).thenReturn(1);
        service = new ProductCaptureService(captureMapper, skuMapper, imageMapper, fieldSourceMapper,
                mock(ProductImageProcessingJobMapper.class), mock(ProductPublishAuditMapper.class),
                new ProductSourceUrlParser(), new SafeProductImageUrl(), enrichmentRegistry,
                mock(WatermarkRemovalRegistry.class), mock(ProductPublishingRegistry.class), objectMapper);
        capture = service.create(Map.of("sourceUrl", SOURCE_URL, "title", "原始标题"),
                IDEMPOTENCY_KEY, principal("org-a", "user-1")).capture();
        preservedNormalized = new LinkedHashMap<>(Map.of(
                "title", "人工标题", "description", "人工详情", "customField", "保留编辑",
                "skuCompleteness", Map.of("source", "USER", "complete", true, "expectedCount", 71),
                "images", List.of(Map.of("url", "https://cdn.example.com/edited.webp")),
                "skus", List.of(Map.of("sellerSku", "manually-edited"))
        ));
        capture.updateDraft("人工标题", "人工详情", null, "人工品牌", "MYR",
                new BigDecimal("29.90"), new BigDecimal("89.90"),
                objectMapper.writeValueAsString(preservedNormalized));
        preservedSkus = IntStream.range(0, 71).mapToObj(index -> new ProductCaptureSku(
                "org-a", capture.getId(), "sku-" + index, "seller-" + index, null, "规格 " + index,
                new BigDecimal("29.90"), new BigDecimal("29.90"), 999,
                "https://cdn.example.com/sku-" + index + ".webp", "MODEL", "{}", "{}", index
        )).toList();
        preservedImage = new ProductCaptureImage("org-a", capture.getId(),
                "https://cdn.example.com/original.webp", "MAIN", null, 0, "image/webp", "{}");
        preservedImage.processed("https://cdn.example.com/processed.webp");
        when(captureMapper.selectOne(any())).thenReturn(capture);
        when(skuMapper.selectList(any())).thenReturn(preservedSkus);
        when(imageMapper.selectList(any())).thenReturn(List.of(preservedImage));
        when(fieldSourceMapper.selectList(any())).thenReturn(List.of(new ProductCaptureFieldSource(
                "org-a", capture.getId(), "title", "USER", "$.title", "\"人工标题\"", BigDecimal.ONE)));
        clearInvocations(captureMapper, skuMapper, imageMapper, fieldSourceMapper, enrichmentRegistry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAPTURED", "READY", "FAILED"})
    void supplementsOnlyMissingCategoryAndPreservesEditedProductAndAll71Skus(String status) {
        setStatus(status);
        String rawData = capture.getRawData();
        Instant createdAt = capture.getCreatedAt();
        long version = capture.getVersion();
        Map<String, Object> request = new LinkedHashMap<>(categoryRequest());
        request.put("title", "插件新标题不应覆盖");
        request.put("description", "插件新详情不应覆盖");
        request.put("price", "1.00");
        request.put("skus", List.of());
        request.put("images", List.of());
        request.put("normalizedData", Map.of("customField", "不应覆盖"));

        ProductCaptureService.CaptureDetail result = service.create(request,
                IDEMPOTENCY_KEY, principal("org-a", "user-1"));

        assertSame(capture, result.capture());
        assertEquals(CATEGORY, capture.getCategoryName());
        assertEquals("人工标题", capture.getTitle());
        assertEquals("人工详情", capture.getDescription());
        assertEquals("人工品牌", capture.getBrandName());
        assertEquals("MYR", capture.getCurrency());
        assertEquals(new BigDecimal("29.90"), capture.getPriceMin());
        assertEquals(new BigDecimal("89.90"), capture.getPriceMax());
        assertEquals(status, capture.getStatus());
        assertEquals(rawData, capture.getRawData());
        assertEquals(createdAt, capture.getCreatedAt());
        assertEquals(version, capture.getVersion());
        assertNull(capture.getApprovedBy());
        assertNull(capture.getApprovedAt());
        assertNull(capture.getPublishedAt());
        Map<String, Object> expectedNormalized = new LinkedHashMap<>(preservedNormalized);
        expectedNormalized.put("categoryName", CATEGORY);
        assertEquals(expectedNormalized, result.normalizedData());
        assertEquals(71, result.skus().size());
        assertSame(preservedSkus.get(70), result.skus().get(70));
        assertSame(preservedImage, result.images().getFirst());
        verify(captureMapper).updateById(capture);
        verifyUnchangedCollections();
        verify(enrichmentRegistry, never()).enrich(anyString(), any(), any());
        ArgumentCaptor<ProductCaptureFieldSource> provenance = ArgumentCaptor.forClass(ProductCaptureFieldSource.class);
        verify(fieldSourceMapper).insert(provenance.capture());
        assertEquals("categoryName", provenance.getValue().getFieldName());
        assertEquals("DOM", provenance.getValue().getSourceType());
        assertEquals(objectMapper.writeValueAsString(CATEGORY), provenance.getValue().getSourceValue());
    }

    @Test
    void acceptsTopLevelCategoryWithoutRequiringTitleOrSkuReplacement() {
        service.create(Map.of("sourceUrl", SOURCE_URL, "categoryName", CATEGORY),
                IDEMPOTENCY_KEY, principal("org-a", "user-1"));
        assertEquals(CATEGORY, capture.getCategoryName());
        verifyUnchangedCollections();
    }

    @Test
    void untouchedCapturedProductStillRefreshesCompleteCaptureInsteadOfOnlyCategory() {
        capture.updateDraft(capture.getTitle(), capture.getDescription(), null, capture.getBrandName(),
                capture.getCurrency(), capture.getPriceMin(), capture.getPriceMax(), "{}");
        when(fieldSourceMapper.selectList(any())).thenReturn(List.of());
        when(imageMapper.selectList(any())).thenReturn(List.of());
        service.create(Map.of("sourceUrl", SOURCE_URL, "title", "更新后的商品标题", "categoryName", CATEGORY,
                "description", "补齐的详情", "skus", List.of(Map.of("variationName", "新增规格", "price", "59.90"))),
                IDEMPOTENCY_KEY, principal("org-a", "user-1"));
        assertEquals(CATEGORY, capture.getCategoryName());
        assertEquals("更新后的商品标题", capture.getTitle());
        assertEquals("补齐的详情", capture.getDescription());
        verify(enrichmentRegistry).enrich(anyString(), any(), any());
        verify(skuMapper).delete(any());
        verify(skuMapper).insert(any(ProductCaptureSku.class));
        verify(imageMapper).delete(any());
        verify(fieldSourceMapper).delete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCRIPT", "NETWORK", "USER"})
    void retainsIncomingCategoryEvidenceWithoutAllowingCollectorToForgeUserEdits(String sourceType) {
        Map<String, Object> request = new LinkedHashMap<>(categoryRequest());
        request.put("fieldSources", List.of(Map.of("field", "categoryName", "source", sourceType,
                "path", "product.category_model", "value", "不能冒用这个类目", "confidence", 0.95)));
        service.create(request, IDEMPOTENCY_KEY, principal("org-a", "user-1"));
        ArgumentCaptor<ProductCaptureFieldSource> provenance = ArgumentCaptor.forClass(ProductCaptureFieldSource.class);
        verify(fieldSourceMapper).insert(provenance.capture());
        assertEquals("DERIVED", provenance.getValue().getSourceType());
        assertEquals("product.category_model", provenance.getValue().getSourcePath());
        assertEquals(new BigDecimal("0.9500"), provenance.getValue().getConfidence());
        assertEquals(objectMapper.writeValueAsString(CATEGORY), provenance.getValue().getSourceValue());
    }

    @Test
    void rejectsCategorySupplementFromAnotherUser() {
        assertRejected(categoryRequest(), principal("org-a", "user-2"));
    }

    @Test
    void rejectsCategorySupplementFromAnotherOrganization() {
        assertRejected(categoryRequest(), principal("org-b", "user-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "PUBLISHED", "IMAGE_PROCESSING"})
    void rejectsCategorySupplementAfterApprovalOrDuringProcessing(String status) {
        setStatus(status);
        assertRejected(categoryRequest(), principal("org-a", "user-1"));
        assertEquals(status, capture.getStatus());
    }

    @Test
    void rejectsPreviouslyApprovedProductEvenWhenItsStatusIsFailed() {
        capture.approve("reviewer");
        capture.fail("发布失败");
        assertRejected(categoryRequest(), principal("org-a", "user-1"));
        assertEquals("reviewer", capture.getApprovedBy());
    }

    @Test
    void rejectsPreviouslyPublishedProductEvenWhenEditedBackToCaptured() {
        capture.published();
        capture.updateDraft(capture.getTitle(), capture.getDescription(), null, capture.getBrandName(),
                capture.getCurrency(), capture.getPriceMin(), capture.getPriceMax(), capture.getNormalizedData());
        assertRejected(categoryRequest(), principal("org-a", "user-1"));
        assertEquals("CAPTURED", capture.getStatus());
    }

    @Test
    void rejectsMismatchedSourceFingerprintBeforeSupplementingCategory() {
        assertRejected(Map.of("sourceUrl", "https://shop.tiktok.com/view/product/1730135047201523999?region=MY",
                "categoryName", CATEGORY), principal("org-a", "user-1"));
    }

    @Test
    void preservesExistingCategoryOnEditedDraft() {
        capture.supplementCategory("用户已有类目", capture.getNormalizedData());
        assertRejected(categoryRequest(), principal("org-a", "user-1"));
        assertEquals("用户已有类目", capture.getCategoryName());
    }

    @Test
    void respectsCategoryIntentionallyClearedByUser() {
        when(fieldSourceMapper.selectCount(any())).thenReturn(1L);
        when(fieldSourceMapper.selectList(any())).thenReturn(List.of(new ProductCaptureFieldSource(
                "org-a", capture.getId(), "categoryName", "USER", "$.categoryName", null, BigDecimal.ONE)));
        assertRejected(categoryRequest(), principal("org-a", "user-1"));
        assertNull(capture.getCategoryName());
    }

    @Test
    void blankIncomingCategoryKeepsOriginalRecaptureGuard() {
        assertRejected(Map.of("sourceUrl", SOURCE_URL, "domData", Map.of("categoryName", "  ")),
                principal("org-a", "user-1"));
    }

    @Test
    void optimisticLockConflictDoesNotAppendCategoryProvenance() {
        when(captureMapper.updateById(any(ProductCapture.class))).thenReturn(0);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                categoryRequest(), IDEMPOTENCY_KEY, principal("org-a", "user-1")));
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        verify(fieldSourceMapper, never()).insert(any(ProductCaptureFieldSource.class));
        verifyUnchangedCollections();
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "FAILED", "CAPTURED"})
    void appendsEightDetailImagesWithoutReplacing27ProcessedImagesOr71Skus(String status) {
        List<ProductCaptureImage> images = detailFixture(status);
        List<String> originalIds = images.stream().map(ProductCaptureImage::getId).toList();
        String rawBefore = capture.getRawData();
        var result = service.create(detailRequest(), IDEMPOTENCY_KEY, principal("org-a", "user-1"));

        assertEquals(35, images.size());
        assertEquals(originalIds, images.subList(0, 27).stream().map(ProductCaptureImage::getId).toList());
        for (int index = 0; index < 27; index++) {
            ProductCaptureImage image = images.get(index);
            assertEquals("PROCESSED", image.getStatus());
            assertEquals("https://cdn.example.com/processed-" + index + ".webp", image.getProcessedUrl());
            assertEquals(index, image.getSortOrder());
        }
        for (int index = 27; index < 35; index++) {
            assertEquals("PENDING", images.get(index).getStatus());
            assertEquals("DETAIL", images.get(index).getRoleName());
            assertEquals(index, images.get(index).getSortOrder());
            assertNull(images.get(index).getProcessedUrl());
        }
        assertEquals("FAILED".equals(status) ? "FAILED" : "CAPTURED", capture.getStatus());
        assertEquals(CATEGORY, capture.getCategoryName());
        assertEquals(rawBefore, capture.getRawData());
        assertEquals("人工标题", capture.getTitle());
        assertEquals("保留编辑", result.normalizedData().get("customField"));
        assertEquals(71, result.skus().size());
        assertSame(preservedSkus.get(70), result.skus().get(70));
        assertEquals(35, ((List<?>) result.normalizedData().get("images")).size());
        Map<?, ?> completeness = (Map<?, ?>) result.normalizedData().get("imageCompleteness");
        assertEquals(35, completeness.get("capturedCount"));
        assertEquals(35, completeness.get("expectedCount"));
        assertEquals(true, completeness.get("complete"));
        assertEquals(8, ((Map<?, ?>) completeness.get("selectedRoleCounts")).get("DETAIL"));
        verify(skuMapper, never()).insert(any(ProductCaptureSku.class));
        verify(skuMapper, never()).delete(any());
        verify(imageMapper, never()).delete(any());
        verify(imageMapper, never()).updateById(any(ProductCaptureImage.class));
        verify(fieldSourceMapper, never()).delete(any());
    }

    @Test
    void repeatedDetailSupplementAndCdnAliasesDoNotCreateDuplicatesOrOverwriteAnExistingCategory() {
        List<ProductCaptureImage> images = detailFixture("READY");
        capture.supplementCategory("已有类目", capture.getNormalizedData());
        service.create(detailRequest(), IDEMPOTENCY_KEY, principal("org-a", "user-1"));
        List<String> idsAfterSupplement = images.stream().map(ProductCaptureImage::getId).toList();
        String normalizedAfterSupplement = capture.getNormalizedData();
        clearInvocations(captureMapper, skuMapper, imageMapper, fieldSourceMapper, enrichmentRegistry);

        Map<String, Object> request = new LinkedHashMap<>(detailRequest());
        request.put("images", IntStream.range(0, 8).mapToObj(index -> Map.of("role", "DETAIL", "url",
                detailUrl(index).replace("p16-", "p19-").replace("~tplv-large.image?signature=one", "~tplv-small.webp?signature=two"))).toList());
        service.create(request, IDEMPOTENCY_KEY, principal("org-a", "user-1"));

        assertEquals(idsAfterSupplement, images.stream().map(ProductCaptureImage::getId).toList());
        assertEquals(normalizedAfterSupplement, capture.getNormalizedData());
        assertEquals("已有类目", capture.getCategoryName());
        assertEquals("CAPTURED", capture.getStatus());
        verify(captureMapper, never()).updateById(any(ProductCapture.class));
        verifyUnchangedCollections();
        verify(fieldSourceMapper, never()).insert(any(ProductCaptureFieldSource.class));
        verify(enrichmentRegistry, never()).enrich(anyString(), any(), any());
    }

    @Test
    void categorySupplementStillWorksButDoesNotAddImagesWhenAnyUserEditsExist() {
        capture.imagesReady();
        service.create(detailRequest(), IDEMPOTENCY_KEY, principal("org-a", "user-1"));
        assertEquals(CATEGORY, capture.getCategoryName());
        assertEquals("READY", capture.getStatus());
        verifyUnchangedCollections();
    }

    @Test
    void imageOnlySupplementRejectsUserEditedImages() {
        capture.imagesReady();
        when(fieldSourceMapper.selectCount(any())).thenReturn(1L);
        assertRejected(Map.of("sourceUrl", SOURCE_URL, "images", detailRequest().get("images")),
                principal("org-a", "user-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "PUBLISHED", "IMAGE_PROCESSING"})
    void detailImagesCannotBypassWorkflowGuard(String status) {
        detailFixture(status);
        assertRejected(detailRequest(), principal("org-a", "user-1"));
    }

    @Test
    void detailImagesCannotBypassCreatorOrTenantGuard() {
        detailFixture("READY");
        assertRejected(detailRequest(), principal("org-a", "another-user"));
        assertRejected(detailRequest(), principal("another-org", "user-1"));
    }

    @Test
    void unsafeDetailUrlFailsBeforeCategoryOrImagesArePersisted() {
        detailFixture("READY");
        Map<String, Object> request = new LinkedHashMap<>(detailRequest());
        request.put("images", List.of(Map.of("role", "DETAIL", "url", "https://127.0.0.1/private.png")));
        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                request, IDEMPOTENCY_KEY, principal("org-a", "user-1")));
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
        assertNull(capture.getCategoryName());
        assertEquals("READY", capture.getStatus());
        verify(captureMapper, never()).updateById(any(ProductCapture.class));
        verifyUnchangedCollections();
    }

    @Test
    void detailAppendUsesOptimisticLockBeforeAnyImageWrites() {
        List<ProductCaptureImage> images = detailFixture("READY");
        when(captureMapper.updateById(any(ProductCapture.class))).thenReturn(0);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                detailRequest(), IDEMPOTENCY_KEY, principal("org-a", "user-1")));
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals(27, images.size());
        verifyUnchangedCollections();
        verify(fieldSourceMapper, never()).insert(any(ProductCaptureFieldSource.class));
    }

    @Test
    void laterRecaptureWithoutDetailsCannotEraseEarlierProcessingResults() {
        detailFixture("READY");
        service.create(detailRequest(), IDEMPOTENCY_KEY, principal("org-a", "user-1"));
        clearInvocations(captureMapper, skuMapper, imageMapper, fieldSourceMapper);
        assertRejected(Map.of("sourceUrl", SOURCE_URL, "title", "不能覆盖已处理记录"),
                principal("org-a", "user-1"));
    }

    @Test
    void repairedImageCompletenessClearsOldDeficitsWhileKeepingTheKnownExpectedCount() {
        detailFixture("READY");
        Map<String, Object> normalized = new LinkedHashMap<>(service.get(capture.getId(), principal("org-a", "user-1")).normalizedData());
        normalized.put("imageCompleteness", Map.of("expectedCount", 35, "capturedCount", 27,
                "complete", false, "issues", List.of("DETAIL_IMAGE_COVERAGE_TRUNCATED")));
        capture.supplementCategory(null, objectMapper.writeValueAsString(normalized));
        var result = service.create(detailRequest(), IDEMPOTENCY_KEY, principal("org-a", "user-1"));
        Map<?, ?> completeness = (Map<?, ?>) result.normalizedData().get("imageCompleteness");
        assertEquals(35, completeness.get("capturedCount"));
        assertEquals(35, completeness.get("expectedCount"));
        assertEquals(true, completeness.get("complete"));
        assertEquals(List.of(), completeness.get("issues"));
    }

    @Test
    void stillMissingImagesRemainIncompleteAfterPartialSupplement() {
        detailFixture("READY");
        Map<String, Object> normalized = new LinkedHashMap<>(service.get(capture.getId(), principal("org-a", "user-1")).normalizedData());
        normalized.put("imageCompleteness", Map.of("expectedCount", 40, "capturedCount", 27, "complete", false));
        capture.supplementCategory(null, objectMapper.writeValueAsString(normalized));
        var result = service.create(detailRequest(), IDEMPOTENCY_KEY, principal("org-a", "user-1"));
        Map<?, ?> completeness = (Map<?, ?>) result.normalizedData().get("imageCompleteness");
        assertEquals(35, completeness.get("capturedCount"));
        assertEquals(40, completeness.get("expectedCount"));
        assertEquals(false, completeness.get("complete"));
        assertTrue(((List<?>) completeness.get("issues")).contains("IMAGE_CAPTURE_INCOMPLETE"));
    }

    @Test
    void respectsFreshCollectorWarningsAfterSupplementInsteadOfClaimingCompleteness() {
        detailFixture("READY");
        Map<String, Object> request = new LinkedHashMap<>(detailRequest());
        request.put("imageCompleteness", Map.of("expectedCount", 35, "capturedCount", 35,
                "complete", false, "issues", List.of("DETAIL_IMAGE_LOAD_FAILED")));
        var result = service.create(request, IDEMPOTENCY_KEY, principal("org-a", "user-1"));
        Map<?, ?> completeness = (Map<?, ?>) result.normalizedData().get("imageCompleteness");
        assertEquals(35, completeness.get("capturedCount"));
        assertEquals(false, completeness.get("complete"));
        assertTrue(((List<?>) completeness.get("issues")).contains("DETAIL_IMAGE_LOAD_FAILED"));
    }

    private List<ProductCaptureImage> detailFixture(String status) {
        Map<String, Object> normalized = new LinkedHashMap<>(preservedNormalized);
        normalized.put("skuCompleteness", Map.of("source", "DOM", "complete", true, "expectedCount", 71));
        normalized.put("imageCompleteness", Map.of("expectedCount", 27, "capturedCount", 27, "complete", true));
        capture.updateDraft(capture.getTitle(), capture.getDescription(), null, capture.getBrandName(),
                capture.getCurrency(), capture.getPriceMin(), capture.getPriceMax(), objectMapper.writeValueAsString(normalized));
        setStatus(status);
        List<ProductCaptureImage> images = new ArrayList<>();
        for (int index = 0; index < 27; index++) {
            ProductCaptureImage image = new ProductCaptureImage("org-a", capture.getId(),
                    "https://cdn.example.com/original-" + index + ".webp", index == 0 ? "MAIN" : "VARIANT",
                    null, index, "image/webp", "{}");
            image.processed("https://cdn.example.com/processed-" + index + ".webp");
            images.add(image);
        }
        preservedSkus = IntStream.range(0, 71).mapToObj(index -> new ProductCaptureSku("org-a", capture.getId(),
                "sku-" + index, "seller-" + index, null, "规格 " + index, new BigDecimal("29.90"), new BigDecimal("29.90"),
                999, images.get(index % 27).getOriginalUrl(), "MODEL", "{}", "{}", index)).toList();
        when(skuMapper.selectList(any())).thenReturn(preservedSkus);
        when(imageMapper.selectList(any())).thenAnswer(ignored -> List.copyOf(images));
        when(imageMapper.insert(any(ProductCaptureImage.class))).thenAnswer(invocation -> {
            images.add(invocation.getArgument(0));
            return 1;
        });
        when(fieldSourceMapper.selectList(any())).thenReturn(List.of());
        return images;
    }

    private Map<String, Object> detailRequest() {
        return Map.of("sourceUrl", SOURCE_URL, "categoryName", CATEGORY, "title", "不要覆盖标题",
                "images", IntStream.range(0, 8).mapToObj(index -> Map.of("role", "DETAIL", "url", detailUrl(index))).toList());
    }

    private String detailUrl(int index) {
        return "https://p16-oec-va.ibyteimg.com/tos-maliva-i-o3syd03w52/abcdef0123456789detail0" + index
                + "~tplv-large.image?signature=one";
    }

    private void assertRejected(Map<String, Object> request, PlatformPrincipal principal) {
        String normalizedBefore = capture.getNormalizedData();
        String rawBefore = capture.getRawData();
        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                request, IDEMPOTENCY_KEY, principal));
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals(normalizedBefore, capture.getNormalizedData());
        assertEquals(rawBefore, capture.getRawData());
        verify(captureMapper, never()).updateById(any(ProductCapture.class));
        verify(fieldSourceMapper, never()).insert(any(ProductCaptureFieldSource.class));
        verifyUnchangedCollections();
    }

    private void verifyUnchangedCollections() {
        verify(skuMapper, never()).delete(any());
        verify(skuMapper, never()).insert(any(ProductCaptureSku.class));
        verify(imageMapper, never()).delete(any());
        verify(imageMapper, never()).insert(any(ProductCaptureImage.class));
        verify(fieldSourceMapper, never()).delete(any());
        verify(captureMapper, never()).insert(any(ProductCapture.class));
    }

    private void setStatus(String status) {
        switch (status) {
            case "READY" -> capture.imagesReady();
            case "FAILED" -> capture.fail("图片处理失败");
            case "IMAGE_PROCESSING" -> capture.startImageProcessing();
            case "APPROVED" -> capture.approve("reviewer");
            case "PUBLISHED" -> { capture.approve("reviewer"); capture.published(); }
            default -> assertEquals("CAPTURED", status);
        }
    }

    private Map<String, Object> categoryRequest() {
        return Map.of("sourceUrl", SOURCE_URL, "domData", Map.of("categoryName", CATEGORY));
    }

    private PlatformPrincipal principal(String organizationId, String userId) {
        return new PlatformPrincipal(userId, organizationId, "tenant", "13800000000", "测试用户",
                "password", true, List.of("ADMIN"), List.of("product:collect", "product:view"));
    }
}
