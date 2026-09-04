package com.yingdao.aicommerce.product.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.application.provider.DryRunProductPublishingProvider;
import com.yingdao.aicommerce.product.application.provider.ProductEnrichmentProvider;
import com.yingdao.aicommerce.product.application.provider.ProductEnrichmentRegistry;
import com.yingdao.aicommerce.product.application.provider.ProductPublishingRegistry;
import com.yingdao.aicommerce.product.application.provider.WatermarkRemovalRegistry;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureFieldSource;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import com.yingdao.aicommerce.product.domain.ProductPublishAudit;
import com.yingdao.aicommerce.product.mapper.ProductCaptureFieldSourceMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.product.mapper.ProductImageProcessingJobMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductCaptureServiceTest {
    private ProductCaptureMapper captureMapper;
    private ProductCaptureSkuMapper skuMapper;
    private ProductCaptureImageMapper imageMapper;
    private ProductCaptureFieldSourceMapper fieldSourceMapper;
    private ProductPublishAuditMapper publishAuditMapper;
    private ProductEnrichmentRegistry enrichmentRegistry;
    private ProductPublishingRegistry publishingRegistry;
    private ProductCaptureService service;

    @BeforeEach
    void setUp() {
        captureMapper = mock(ProductCaptureMapper.class);
        skuMapper = mock(ProductCaptureSkuMapper.class);
        imageMapper = mock(ProductCaptureImageMapper.class);
        fieldSourceMapper = mock(ProductCaptureFieldSourceMapper.class);
        publishAuditMapper = mock(ProductPublishAuditMapper.class);
        enrichmentRegistry = mock(ProductEnrichmentRegistry.class);
        publishingRegistry = mock(ProductPublishingRegistry.class);
        when(enrichmentRegistry.enrich(anyString(), any(), any()))
                .thenReturn(ProductEnrichmentProvider.EnrichmentResult.empty());
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(imageMapper.selectList(any())).thenReturn(List.of());
        when(fieldSourceMapper.selectList(any())).thenReturn(List.of());
        when(captureMapper.updateById(any(ProductCapture.class))).thenReturn(1);
        service = new ProductCaptureService(
                captureMapper, skuMapper, imageMapper, fieldSourceMapper,
                mock(ProductImageProcessingJobMapper.class), publishAuditMapper,
                new ProductSourceUrlParser(), new SafeProductImageUrl(), enrichmentRegistry,
                mock(WatermarkRemovalRegistry.class), publishingRegistry, new ObjectMapper()
        );
    }

    @Test
    void listsPagedSummariesWithoutLoadingSkuRawDataOrDetailCollections() {
        ProductCapture capture = capture("org-a");
        Page<ProductCapture> databasePage = new Page<>(1, 2);
        databasePage.setTotal(7);
        databasePage.setRecords(List.of(capture));
        when(captureMapper.selectPage(any(Page.class), any())).thenReturn(databasePage);
        when(captureMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("STATUS", "CAPTURED", "TOTAL", 7L),
                Map.of("STATUS", "APPROVED", "TOTAL", 2L)
        ));
        ProductCaptureImage thumbnail = new ProductCaptureImage(
                "org-a", capture.getId(), "https://cdn.example.com/original.webp",
                "MAIN", null, 0, "image/webp", "{}"
        );
        thumbnail.processed("https://cdn.example.com/processed.webp");
        when(imageMapper.selectFirstByCaptureIds("org-a", List.of(capture.getId())))
                .thenReturn(List.of(thumbnail));

        ProductCaptureService.CapturePage result = service.list(
                "org-a", "DRAFT", "model-42", 0, 2
        );

        assertEquals(0, result.page());
        assertEquals(2, result.size());
        assertEquals(7, result.totalElements());
        assertEquals(4, result.totalPages());
        assertEquals(capture, result.content().getFirst().capture());
        assertEquals(thumbnail, result.content().getFirst().thumbnail());
        assertEquals(7L, result.statusCounts().get("CAPTURED"));
        assertEquals(2L, result.statusCounts().get("APPROVED"));
        assertEquals(0L, result.statusCounts().get("FAILED"));
        verify(skuMapper, never()).selectList(any());
        verify(fieldSourceMapper, never()).selectList(any());
        verify(imageMapper, never()).selectList(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<ProductCapture>> criteria =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(captureMapper).selectPage(any(Page.class), criteria.capture());
        assertTrue(criteria.getValue().getSqlSegment().contains("EXISTS"));
        assertTrue(criteria.getValue().getParamNameValuePairs().containsValue("org-a"));
        assertTrue(criteria.getValue().getParamNameValuePairs().containsValue("CAPTURED"));
        assertTrue(criteria.getValue().getParamNameValuePairs().containsValue("model-42"));
    }

    @Test
    void createsDirectDomDraftWithoutInventingSkuAndSanitizesTrackedUrls() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> request = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456?utm_source=secret",
                "sourcePlatform", "SHOPEE",
                "title", "DOM 商品",
                "price", "PHP 199.00",
                "skus", List.of(),
                "capturedAt", "2026-09-04T00:00:00Z",
                "domData", Map.of(
                        "pageUrl", "https://shopee.ph/a-i.123.456?track=secret",
                        "customVisibleField", "kept"
                )
        );

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-stable-123", principal("org-a")
        );

        assertEquals("DIRECT_DOM", detail.normalizedData().get("captureMode"));
        assertTrue(detail.skus().isEmpty());
        verify(skuMapper, never()).insert(any(ProductCaptureSku.class));
        Map<?, ?> rawRequest = (Map<?, ?>) detail.rawData().get("captureRequest");
        assertEquals("https://shopee.ph/a-i.123.456", rawRequest.get("sourceUrl"));
        Map<?, ?> dom = (Map<?, ?>) rawRequest.get("domData");
        assertEquals("https://shopee.ph/a-i.123.456", dom.get("pageUrl"));
        assertEquals("kept", dom.get("customVisibleField"));
    }

    @Test
    void stableIdempotencyKeyReturnsSameProductDespiteDynamicCaptureFields() {
        AtomicReference<ProductCapture> stored = new AtomicReference<>();
        when(captureMapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(captureMapper.insert(any(ProductCapture.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });

        Map<String, Object> first = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456?utm_source=one",
                "title", "商品", "capturedAt", "first"
        );
        Map<String, Object> second = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456?utm_source=two",
                "title", "变化后的页面标题", "capturedAt", "second"
        );

        var created = service.create(first, "capture-stable-456", principal("org-a"));
        var repeated = service.create(second, "capture-stable-456", principal("org-a"));

        assertSame(created.capture(), repeated.capture());
        verify(captureMapper).insert(any(ProductCapture.class));
    }

    @Test
    void tiktokIdentityIgnoresOptionalShopIdForFingerprintAndDefaultIdempotency() {
        AtomicReference<ProductCapture> stored = storedCapture();
        Map<String, Object> first = Map.of(
                "sourceUrl", "https://shop.tiktok.com/view/product/1736046921727116747?region=PH",
                "sourceShopId", "111",
                "title", "第一次采集"
        );
        Map<String, Object> second = Map.of(
                "sourceUrl", "https://shop.tiktok.com/view/product/1736046921727116747?region=PH",
                "sourceShopId", "222",
                "title", "第二次采集"
        );

        ProductCaptureService.CaptureDetail initial = service.create(
                first, null, principal("org-a")
        );
        ProductCaptureService.CaptureDetail repeated = service.create(
                second, null, principal("org-a")
        );

        assertSame(initial.capture(), repeated.capture());
        assertSame(stored.get(), repeated.capture());
        assertEquals("第二次采集", repeated.capture().getTitle());
        verify(captureMapper).insert(any(ProductCapture.class));
    }

    @Test
    void shopeeFingerprintStillIncludesShopId() {
        AtomicReference<ProductCapture> stored = storedCapture();
        service.create(Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456", "title", "第一次采集"
        ), "capture-same-explicit-key", principal("org-a"));

        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                Map.of("sourceUrl", "https://shopee.ph/a-i.124.456", "title", "另一店铺"),
                "capture-same-explicit-key", principal("org-a")
        ));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals("第一次采集", stored.get().getTitle());
    }

    @Test
    void preservesUnknownStockAsNullAndPatchReplacesSkuCollection() {
        ProductCapture capture = capture("org-a");
        when(captureMapper.selectOne(any())).thenReturn(capture);
        service.patch(capture.getId(), Map.of("version", capture.getVersion(), "skus", List.of(Map.of(
                "id", "", "variationName", "默认规格", "price", "99.00"
        ))), principal("org-a"));

        verify(skuMapper).delete(any());
        ArgumentCaptor<ProductCaptureSku> inserted = ArgumentCaptor.forClass(ProductCaptureSku.class);
        verify(skuMapper).insert(inserted.capture());
        assertNull(inserted.getValue().getStock());
        assertEquals(new BigDecimal("99.00"), inserted.getValue().getPrice());
    }

    @Test
    void patchRequiresCurrentClientVersionBeforeChangingAnything() {
        ProductCapture capture = capture("org-a");
        when(captureMapper.selectOne(any())).thenReturn(capture);

        BusinessException missing = assertThrows(BusinessException.class, () -> service.patch(
                capture.getId(), Map.of("title", "没有版本"), principal("org-a")
        ));
        BusinessException stale = assertThrows(BusinessException.class, () -> service.patch(
                capture.getId(), Map.of("version", capture.getVersion() + 1, "title", "陈旧修改"),
                principal("org-a")
        ));

        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatus());
        assertEquals(HttpStatus.CONFLICT, stale.getStatus());
        assertTrue(stale.getMessage().contains("刷新"));
        assertEquals("商品", capture.getTitle());
        verify(captureMapper, never()).updateById(any(ProductCapture.class));
    }

    @Test
    void patchRejectsProtectedStructuresInsideNormalizedData() {
        ProductCapture capture = capture("org-a");
        when(captureMapper.selectOne(any())).thenReturn(capture);

        BusinessException failure = assertThrows(BusinessException.class, () -> service.patch(
                capture.getId(), Map.of(
                        "version", capture.getVersion(),
                        "normalizedData", Map.of(
                                "imageCompleteness", Map.of("complete", true),
                                "skus", List.of()
                        )
                ), principal("org-a")
        ));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
        assertTrue(failure.getMessage().contains("imageCompleteness"));
        assertTrue(failure.getMessage().contains("skus"));
        verify(captureMapper, never()).updateById(any(ProductCapture.class));
    }

    @Test
    void fractionalSkuStockIsRejectedInsteadOfTruncated() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("sourceUrl", "https://shopee.ph/a-i.123.456");
        request.put("title", "非法库存商品");
        request.put("skus", List.of(Map.of(
                "externalSkuId", "model-1", "price", "10.00", "stock", 1.9
        )));

        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                request, "capture-fractional-stock", principal("org-a")
        ));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
        assertTrue(failure.getMessage().contains("库存"));
        verify(skuMapper, never()).insert(any(ProductCaptureSku.class));
    }

    @Test
    void onlyMarksSkuCompleteWhenCountsAndEverySkuFieldAreVerified() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> completeSku = Map.of(
                "externalSkuId", "model-1",
                "variationName", "红色 / M",
                "price", "99.00",
                "originalPrice", "129.00",
                "stock", 8,
                "imageUrl", "https://cdn.example.com/model-1.webp",
                "attributes", Map.of("颜色", "红色", "尺码", "M")
        );
        Map<String, Object> request = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456",
                "title", "DOM 商品",
                "description", "描述",
                "skus", List.of(completeSku),
                "skuCompleteness", Map.of(
                        "expectedCount", 1,
                        "capturedCount", 1,
                        "hasVariants", true,
                        "variantDimensionCount", 2,
                        "complete", true,
                        "source", "DOM",
                        "issues", List.of()
                )
        );

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-complete-sku", principal("org-a")
        );

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
        assertEquals(true, completeness.get("complete"));
        assertEquals(1, completeness.get("expectedCount"));
        assertEquals(1, completeness.get("capturedCount"));
        ArgumentCaptor<ProductCaptureSku> inserted = ArgumentCaptor.forClass(ProductCaptureSku.class);
        verify(skuMapper).insert(inserted.capture());
        assertEquals(8, inserted.getValue().getStock());
    }

    @Test
    void stockOnlyParserIssuesDoNotMakeSkuIncomplete() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> request = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456",
                "title", "DOM 商品",
                "description", "描述",
                "skus", List.of(Map.of(
                        "externalSkuId", "model-1",
                        "variationName", "默认规格",
                        "price", "99.00",
                        "originalPrice", "129.00",
                        "imageUrl", "https://cdn.example.com/model-1.webp",
                        "attributes", Map.of()
                )),
                "skuCompleteness", Map.of(
                        "expectedCount", 1,
                        "capturedCount", 1,
                        "hasVariants", false,
                        "variantDimensionCount", 0,
                        "complete", false,
                        "source", "SHOPEE_PAGE_RESPONSE",
                        "issues", List.of("SKU_1_STOCK_MISSING")
                )
        );

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-optional-stock", principal("org-a")
        );

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
        assertEquals(true, completeness.get("complete"));
        assertEquals(List.of(), completeness.get("issues"));
        ArgumentCaptor<ProductCaptureSku> inserted = ArgumentCaptor.forClass(ProductCaptureSku.class);
        verify(skuMapper).insert(inserted.capture());
        assertNull(inserted.getValue().getStock());
    }

    @Test
    void collectorCannotClaimUserOrManualSkuCompletenessSource() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        for (String claimedSource : List.of("USER", "MANUAL_REVIEW", "HUMAN_EDITOR")) {
            Map<String, Object> request = captureRequest(
                    List.of(completeSku("model-" + claimedSource, Map.of())),
                    Map.of(
                            "expectedCount", 1, "capturedCount", 1,
                            "hasVariants", false, "variantDimensionCount", 0,
                            "complete", true, "source", claimedSource,
                            "manualConfirmed", true, "issues", List.of()
                    )
            );

            ProductCaptureService.CaptureDetail detail = service.create(
                    request, "capture-source-" + claimedSource.toLowerCase(), principal("org-a")
            );

            Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
            assertEquals("DOM", completeness.get("source"));
            assertEquals(true, completeness.get("complete"));
        }
    }

    @Test
    void identicalSkuPatchCannotDriftAutomaticCompletenessSourceToUser() {
        ProductCapture capture = new ProductCapture(
                "org-a", "user-1", "capture-source-stable", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN,
                "{\"skuCompleteness\":{\"expectedCount\":1,\"capturedCount\":1,\"complete\":true,\"source\":\"SHOPEE_PDP_API\",\"hasVariants\":false,\"variantDimensionCount\":0,\"issues\":[]}}",
                "{}"
        );
        ProductCaptureSku sku = persistedSku(capture, "model-1", BigDecimal.TEN, Map.of());
        when(captureMapper.selectOne(any())).thenReturn(capture);
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));

        ProductCaptureService.CaptureDetail detail = service.patch(capture.getId(), Map.of(
                "version", capture.getVersion(),
                "title", "只修改标题",
                "skus", List.of(completeSkuAtPrice("model-1", BigDecimal.TEN, Map.of())),
                "skuCompleteness", Map.of(
                        "expectedCount", 1, "capturedCount", 1,
                        "hasVariants", false, "variantDimensionCount", 0,
                        "complete", true, "source", "USER", "issues", List.of()
                )
        ), principal("org-a"));

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
        assertEquals("SHOPEE_PDP_API", completeness.get("source"));
        verify(skuMapper, never()).delete(any());
    }

    @Test
    void explicitManualConfirmationMaySetUserCompletenessSource() {
        ProductCapture capture = new ProductCapture(
                "org-a", "user-1", "capture-manual-confirm", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN,
                "{\"skuCompleteness\":{\"expectedCount\":1,\"capturedCount\":1,\"complete\":true,\"source\":\"DOM\",\"hasVariants\":false,\"variantDimensionCount\":0,\"issues\":[]}}",
                "{}"
        );
        ProductCaptureSku sku = persistedSku(capture, "model-1", BigDecimal.TEN, Map.of());
        when(captureMapper.selectOne(any())).thenReturn(capture);
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));

        ProductCaptureService.CaptureDetail detail = service.patch(capture.getId(), Map.of(
                "version", capture.getVersion(),
                "skuCompleteness", Map.of(
                        "expectedCount", 1, "capturedCount", 1,
                        "hasVariants", false, "variantDimensionCount", 0,
                        "complete", true, "source", "USER",
                        "manualConfirmed", true, "issues", List.of()
                )
        ), principal("org-a"));

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
        assertEquals("USER", completeness.get("source"));
        assertEquals(true, completeness.get("complete"));
    }

    @Test
    void singleSkuWithoutVariantsAllowsEmptyAttributesAndInheritsMainImage() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> request = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456",
                "title", "无规格商品",
                "skus", List.of(Map.of(
                        "externalSkuId", "model-1",
                        "price", "99.00",
                        "originalPrice", "99.00",
                        "stock", 8,
                        "attributes", Map.of()
                )),
                "images", List.of("https://cdn.example.com/main.webp"),
                "skuCompleteness", Map.of(
                        "expectedCount", 1,
                        "capturedCount", 1,
                        "hasVariants", false,
                        "variantDimensionCount", 0,
                        "complete", true,
                        "source", "DOM",
                        "issues", List.of()
                )
        );

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-single-no-variant", principal("org-a")
        );

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
        assertEquals(true, completeness.get("complete"));
        assertEquals(false, completeness.get("hasVariants"));
        ArgumentCaptor<ProductCaptureSku> inserted = ArgumentCaptor.forClass(ProductCaptureSku.class);
        verify(skuMapper).insert(inserted.capture());
        assertEquals("https://cdn.example.com/main.webp", inserted.getValue().getImageUrl());
        assertEquals("PRODUCT_MAIN", inserted.getValue().getImageSource());
    }

    @Test
    void clientImageDeclarationCanOnlyMakeServerPlanMoreConservative() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        List<String> images = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            images.add("https://cdn.example.com/image-" + index + ".webp");
        }
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("sourceUrl", "https://shopee.ph/a-i.123.456");
        request.put("title", "图片超限商品");
        request.put("images", images);
        request.put("imageCompleteness", Map.of(
                "expectedCount", 30,
                "capturedCount", 30,
                "requiredSkuImageCount", 0,
                "coveredSkuImageCount", 0,
                "complete", true,
                "issues", List.of()
        ));

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-image-limit", principal("org-a")
        );

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("imageCompleteness");
        assertEquals(31, completeness.get("expectedCount"));
        assertEquals(30, completeness.get("capturedCount"));
        assertEquals(false, completeness.get("complete"));
        assertTrue(((List<?>) completeness.get("issues")).contains("IMAGE_LIMIT_EXCEEDED"));
        verify(imageMapper, org.mockito.Mockito.times(30)).insert(any(ProductCaptureImage.class));
    }

    @Test
    void incompleteClientImageDeclarationCannotBecomeCompleteOnServer() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("sourceUrl", "https://shopee.ph/a-i.123.456");
        request.put("title", "图片缺失商品");
        request.put("images", List.of("https://cdn.example.com/main.webp"));
        request.put("normalizedData", Map.of("imageCompleteness", Map.of(
                "expectedCount", 2,
                "capturedCount", 1,
                "requiredSkuImageCount", 0,
                "coveredSkuImageCount", 0,
                "complete", false,
                "issues", List.of("GALLERY_IMAGE_MISSING")
        )));

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-client-image-incomplete", principal("org-a")
        );

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("imageCompleteness");
        assertEquals(2, completeness.get("expectedCount"));
        assertEquals(1, completeness.get("capturedCount"));
        assertEquals(false, completeness.get("complete"));
        assertEquals(List.of("GALLERY_IMAGE_MISSING"), completeness.get("issues"));
    }

    @Test
    void legacyProductMainPageImageSourceIsPersistedCanonically() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> sku = completeSku("model-legacy-main", Map.of());
        sku = new java.util.LinkedHashMap<>(sku);
        sku.put("imageSource", "PRODUCT_MAIN_PAGE");

        service.create(captureRequest(
                List.of(sku),
                Map.of(
                        "expectedCount", 1, "capturedCount", 1,
                        "hasVariants", false, "variantDimensionCount", 0,
                        "complete", true, "source", "DOM", "issues", List.of()
                )
        ), "capture-canonical-main-image", principal("org-a"));

        ArgumentCaptor<ProductCaptureSku> inserted = ArgumentCaptor.forClass(ProductCaptureSku.class);
        verify(skuMapper).insert(inserted.capture());
        assertEquals("PRODUCT_MAIN", inserted.getValue().getImageSource());
    }

    @Test
    void declaredVariantWithEmptyAttributesRemainsIncomplete() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> request = captureRequest(
                List.of(completeSku("model-1", Map.of())),
                Map.of(
                        "expectedCount", 1, "capturedCount", 1,
                        "hasVariants", true, "variantDimensionCount", 1,
                        "complete", true, "source", "DOM", "issues", List.of()
                )
        );

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-empty-variant", principal("org-a")
        );

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
        assertEquals(false, completeness.get("complete"));
        assertEquals(true, completeness.get("hasVariants"));
    }

    @Test
    void multiVariantSkuWithMissingDimensionRemainsIncomplete() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> request = captureRequest(
                List.of(
                        completeSku("model-1", Map.of("颜色", "红色", "尺码", "M")),
                        completeSku("model-2", Map.of("颜色", "蓝色"))
                ),
                Map.of(
                        "expectedCount", 2, "capturedCount", 2,
                        "variantDimensionCount", 2, "complete", true,
                        "source", "DOM", "issues", List.of()
                )
        );

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-missing-dimension", principal("org-a")
        );

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
        assertEquals(false, completeness.get("complete"));
        assertEquals(2, completeness.get("variantDimensionCount"));
    }

    @Test
    void multipleSkusCannotForgeNoVariantsOrDuplicateAttributeCombination() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> request = captureRequest(
                List.of(
                        completeSku("model-1", Map.of("颜色", "红色")),
                        completeSku("model-2", Map.of("颜色", "红色"))
                ),
                Map.of(
                        "expectedCount", 2, "capturedCount", 2,
                        "hasVariants", false, "variantDimensionCount", 0,
                        "complete", true, "source", "DOM", "issues", List.of()
                )
        );

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-duplicate-combination", principal("org-a")
        );

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
        assertEquals(true, completeness.get("hasVariants"));
        assertEquals(false, completeness.get("complete"));
    }

    @Test
    void parserIssuesArePreservedAndForceSkuIncomplete() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        List<String> issues = List.of("SKU_1_IMAGE_MISSING", "SKU_COUNT_MISMATCH");
        Map<String, Object> request = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456",
                "title", "DOM 商品",
                "description", "完整描述",
                "skus", List.of(completeSku("model-1", Map.of())),
                "domData", Map.of(
                        "variantHints", List.of(),
                        "skuCompleteness", Map.of(
                                "expectedCount", 1, "capturedCount", 1,
                                "variantDimensionCount", 0, "complete", true,
                                "source", "SHOPEE_PDP_API", "issues", issues
                        )
                )
        );

        ProductCaptureService.CaptureDetail detail = service.create(
                request, "capture-parser-issues", principal("org-a")
        );

        Map<?, ?> completeness = (Map<?, ?>) detail.normalizedData().get("skuCompleteness");
        assertEquals(issues, completeness.get("issues"));
        assertEquals(false, completeness.get("complete"));
    }

    @Test
    void recaptureAtomicallyReplacesIncompleteSkusWithCompleteSet() {
        AtomicReference<ProductCapture> storedCapture = new AtomicReference<>();
        List<ProductCaptureSku> storedSkus = new ArrayList<>();
        when(captureMapper.selectOne(any())).thenAnswer(invocation -> storedCapture.get());
        when(captureMapper.insert(any(ProductCapture.class))).thenAnswer(invocation -> {
            storedCapture.set(invocation.getArgument(0));
            return 1;
        });
        when(skuMapper.insert(any(ProductCaptureSku.class))).thenAnswer(invocation -> {
            storedSkus.add(invocation.getArgument(0));
            return 1;
        });
        when(skuMapper.delete(any())).thenAnswer(invocation -> {
            storedSkus.clear();
            return 1;
        });
        when(skuMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedSkus));

        Map<String, Object> first = captureRequest(
                List.of(
                        completeSku("model-1", Map.of("颜色", "红色")),
                        completeSku("model-2", Map.of("颜色", "蓝色"))
                ),
                Map.of(
                        "expectedCount", 3, "capturedCount", 2,
                        "variantDimensionCount", 1, "complete", false,
                        "source", "DOM", "issues", List.of("SKU_COUNT_MISMATCH")
                )
        );
        Map<String, Object> second = captureRequest(
                List.of(
                        completeSku("model-1", Map.of("颜色", "红色")),
                        completeSku("model-2", Map.of("颜色", "蓝色")),
                        completeSku("model-3", Map.of("颜色", "绿色"))
                ),
                Map.of(
                        "expectedCount", 3, "capturedCount", 3,
                        "variantDimensionCount", 1, "complete", true,
                        "source", "DOM", "issues", List.of()
                )
        );

        var initial = service.create(first, "capture-retry-same-product", principal("org-a"));
        var retried = service.create(second, "capture-retry-same-product", principal("org-a"));

        assertSame(initial.capture(), retried.capture());
        assertEquals(initial.capture().getId(), retried.capture().getId());
        assertEquals(3, retried.skus().size());
        Map<?, ?> completeness = (Map<?, ?>) retried.normalizedData().get("skuCompleteness");
        assertEquals(true, completeness.get("complete"));
        assertEquals(List.of(), completeness.get("issues"));
        assertEquals("CAPTURED", retried.capture().getStatus());
    }

    @Test
    void recaptureCannotOverwriteApprovedProduct() {
        AtomicReference<ProductCapture> stored = new AtomicReference<>();
        when(captureMapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(captureMapper.insert(any(ProductCapture.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        Map<String, Object> request = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456",
                "title", "首次采集"
        );
        ProductCapture created = service.create(
                request, "capture-approved-immutable", principal("org-a")
        ).capture();
        created.startImageProcessing();
        created.imagesReady();
        created.approve("reviewer-1");

        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                Map.of("sourceUrl", "https://shopee.ph/a-i.123.456", "title", "恶意覆盖"),
                "capture-approved-immutable", principal("org-a")
        ));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals("首次采集", created.getTitle());
        assertEquals("APPROVED", created.getStatus());
    }

    @Test
    void recaptureCannotOverwriteDraftCreatedByAnotherUser() {
        AtomicReference<ProductCapture> stored = storedCapture();
        Map<String, Object> original = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456", "title", "首次采集"
        );
        service.create(original, "capture-other-owner", principal("org-a", "user-1"));

        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                Map.of("sourceUrl", "https://shopee.ph/a-i.123.456", "title", "越权覆盖"),
                "capture-other-owner", principal("org-a", "user-2")
        ));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals("首次采集", stored.get().getTitle());
    }

    @Test
    void recaptureCannotOverwriteReadyOrFailedDraft() {
        AtomicReference<ProductCapture> stored = storedCapture();
        Map<String, Object> original = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456", "title", "首次采集"
        );
        ProductCapture capture = service.create(
                original, "capture-processed-state", principal("org-a")
        ).capture();
        capture.startImageProcessing();
        capture.imagesReady();

        BusinessException readyFailure = assertThrows(BusinessException.class, () -> service.create(
                original, "capture-processed-state", principal("org-a")
        ));
        assertEquals(HttpStatus.CONFLICT, readyFailure.getStatus());

        capture.updateDraft(
                capture.getTitle(), capture.getDescription(), capture.getCategoryName(),
                capture.getBrandName(), capture.getCurrency(), capture.getPriceMin(),
                capture.getPriceMax(), capture.getNormalizedData()
        );
        capture.fail("processing failed");
        BusinessException failedFailure = assertThrows(BusinessException.class, () -> service.create(
                original, "capture-processed-state", principal("org-a")
        ));

        assertEquals(HttpStatus.CONFLICT, failedFailure.getStatus());
        assertEquals("首次采集", stored.get().getTitle());
    }

    @Test
    void explicitlyConfirmedSkuMetadataBlocksLaterCollectorRecapture() throws Exception {
        AtomicReference<ProductCapture> stored = storedCapture();
        Map<String, Object> original = Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456", "title", "首次采集"
        );
        ProductCapture capture = service.create(
                original, "capture-user-edited", principal("org-a")
        ).capture();
        service.patch(capture.getId(), Map.of(
                "version", capture.getVersion(),
                "skuCompleteness", Map.of(
                        "expectedCount", 0, "capturedCount", 0,
                        "complete", true, "source", "USER",
                        "manualConfirmed", true, "issues", List.of()
                )
        ), principal("org-a"));

        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                Map.of("sourceUrl", "https://shopee.ph/a-i.123.456", "title", "插件覆盖"),
                "capture-user-edited", principal("org-a")
        ));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        Map<?, ?> completeness = (Map<?, ?>) new ObjectMapper().readValue(
                stored.get().getNormalizedData(), Map.class
        ).get("skuCompleteness");
        assertEquals("USER", completeness.get("source"));
    }

    @Test
    void patchingImagesPersistsUserEditSourceMarker() {
        ProductCapture capture = capture("org-a");
        when(captureMapper.selectOne(any())).thenReturn(capture);

        service.patch(capture.getId(), Map.of(
                "version", capture.getVersion(),
                "images", List.of("https://cdn.example.com/reviewer.webp")
        ), principal("org-a"));

        ArgumentCaptor<ProductCaptureFieldSource> sources =
                ArgumentCaptor.forClass(ProductCaptureFieldSource.class);
        verify(fieldSourceMapper, org.mockito.Mockito.atLeastOnce()).insert(sources.capture());
        assertTrue(sources.getAllValues().stream().anyMatch(source ->
                "images".equals(source.getFieldName()) && "USER".equals(source.getSourceType())));
    }

    @Test
    void patchingScalarAliasPersistsUserEditSourceMarker() {
        ProductCapture capture = capture("org-a");
        when(captureMapper.selectOne(any())).thenReturn(capture);

        service.patch(capture.getId(), Map.of(
                "version", capture.getVersion(), "name", "人工修改标题"
        ), principal("org-a"));

        ArgumentCaptor<ProductCaptureFieldSource> sources =
                ArgumentCaptor.forClass(ProductCaptureFieldSource.class);
        verify(fieldSourceMapper, org.mockito.Mockito.atLeastOnce()).insert(sources.capture());
        assertTrue(sources.getAllValues().stream().anyMatch(source ->
                "title".equals(source.getFieldName())
                        && "USER".equals(source.getSourceType())
                        && "$.name".equals(source.getSourcePath())));
    }

    @Test
    void collectorFieldSourcesAreClampedToTrustedAutomaticSource() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("sourceUrl", "https://shopee.ph/a-i.123.456");
        request.put("title", "来源保护商品");
        request.put("fieldSources", List.of(
                Map.of("field", "title", "source", "USER", "path", "$.title"),
                Map.of("field", "priceMin", "source", "REEFAPI", "path", "$.price"),
                Map.of("field", "brandName", "source", "TMAPI", "path", "$.brand")
        ));

        service.create(request, "capture-clamped-sources", principal("org-a"));

        ArgumentCaptor<ProductCaptureFieldSource> sources =
                ArgumentCaptor.forClass(ProductCaptureFieldSource.class);
        verify(fieldSourceMapper, org.mockito.Mockito.atLeast(3)).insert(sources.capture());
        assertTrue(sources.getAllValues().stream()
                .allMatch(source -> "DOM".equals(source.getSourceType())));
    }

    @Test
    void patchFieldSourcesAreAlwaysRecordedAsUserAuthored() {
        ProductCapture capture = capture("org-a");
        when(captureMapper.selectOne(any())).thenReturn(capture);

        service.patch(capture.getId(), Map.of(
                "version", capture.getVersion(),
                "title", "人工标题",
                "fieldSources", List.of(Map.of(
                        "field", "title", "source", "REEFAPI", "path", "$.title"
                ))
        ), principal("org-a"));

        ArgumentCaptor<ProductCaptureFieldSource> sources =
                ArgumentCaptor.forClass(ProductCaptureFieldSource.class);
        verify(fieldSourceMapper, org.mockito.Mockito.atLeastOnce()).insert(sources.capture());
        assertTrue(sources.getAllValues().stream()
                .allMatch(source -> "USER".equals(source.getSourceType())));
    }

    @Test
    void tenantScopedLookupReturnsNotFoundForAnotherOrganization() {
        when(captureMapper.selectOne(any())).thenReturn(null);
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.get("capture-id", principal("org-b")));
        assertEquals(HttpStatus.NOT_FOUND, failure.getStatus());
    }

    @Test
    void publishRequiresConfirmationAndPriorApproval() {
        ProductCapture draft = capture("org-a");
        when(captureMapper.selectOne(any())).thenReturn(draft);
        when(publishAuditMapper.selectOne(any())).thenReturn(null);

        BusinessException notConfirmed = assertThrows(BusinessException.class,
                () -> service.publish(draft.getId(), Map.of("confirmed", false),
                        "publish-key-0001", principal("org-a")));
        assertEquals(HttpStatus.BAD_REQUEST, notConfirmed.getStatus());

        BusinessException notApproved = assertThrows(BusinessException.class,
                () -> service.publish(draft.getId(), Map.of(
                                "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "123"),
                        "publish-key-0002", principal("org-a")));
        assertEquals(HttpStatus.CONFLICT, notApproved.getStatus());
    }

    @Test
    void dryRunPublishStillRequiresTargetShopId() {
        ProductCapture approved = capture("org-a");
        approved.startImageProcessing();
        approved.imagesReady();
        approved.approve("reviewer-1");
        when(captureMapper.selectOne(any())).thenReturn(approved);

        BusinessException failure = assertThrows(BusinessException.class, () -> service.publish(
                approved.getId(), Map.of(
                        "confirmed", true, "destinationPlatform", "SHOPEE"
                ), "publish-missing-shop", principal("org-a")
        ));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
        assertTrue(failure.getMessage().contains("店铺"));
        verify(publishAuditMapper, never()).insert(any(ProductPublishAudit.class));
        verify(publishingRegistry, never()).current();
    }

    @Test
    void approvedDryRunCreatesAuditButDoesNotMarkProductPublished() {
        ProductCapture approved = capture("org-a");
        approved.startImageProcessing();
        approved.imagesReady();
        approved.approve("reviewer-1");
        when(captureMapper.selectOne(any())).thenReturn(approved);
        when(publishAuditMapper.selectOne(any())).thenReturn(null);
        when(publishingRegistry.forPlatform("SHOPEE")).thenReturn(new DryRunProductPublishingProvider());

        ProductCaptureService.PublishResult result = service.publish(
                approved.getId(), Map.of(
                        "confirmed", true,
                        "destinationPlatform", "SHOPEE",
                        "shopId", "123"
                ), "publish-key-0003", principal("org-a")
        );

        ProductPublishAudit audit = result.audit();
        assertTrue(audit.isDryRun());
        assertEquals("DRY_RUN_SUCCEEDED", audit.getStatus());
        assertEquals("APPROVED", approved.getStatus());
        assertNull(approved.getPublishedAt());
    }

    @Test
    void approveAcceptsMissingSkuStockWhenAllRequiredFieldsAreReady() {
        ProductCapture ready = new ProductCapture(
                "org-a", "user-1", "capture-optional-stock-approval", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN,
                "{\"skuCompleteness\":{\"expectedCount\":1,\"capturedCount\":1,"
                        + "\"complete\":false,\"source\":\"SHOPEE_PAGE_RESPONSE\","
                        + "\"hasVariants\":false,\"variantDimensionCount\":0,"
                        + "\"issues\":[\"SKU_1_STOCK_MISSING\"]},"
                        + "\"imageCompleteness\":{\"expectedCount\":1,\"capturedCount\":1,"
                        + "\"requiredSkuImageCount\":1,\"coveredSkuImageCount\":1,"
                        + "\"complete\":true,\"issues\":[]}}",
                "{}"
        );
        ready.startImageProcessing();
        ready.imagesReady();
        ProductCaptureImage image = new ProductCaptureImage(
                "org-a", ready.getId(), "https://cdn.example.com/main.webp",
                "MAIN", null, 0, "image/webp", "{}"
        );
        image.processed(image.getOriginalUrl());
        ProductCaptureSku sku = new ProductCaptureSku(
                "org-a", ready.getId(), "model-1", "model-1", null, "默认规格",
                BigDecimal.TEN, BigDecimal.TEN, null, image.getOriginalUrl(),
                "PRODUCT_MAIN", "{}", "{}", 0
        );
        when(captureMapper.selectOne(any())).thenReturn(ready);
        when(imageMapper.selectList(any())).thenReturn(List.of(image));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));

        ProductCaptureService.CaptureDetail approved = service.approve(
                ready.getId(), principal("org-a")
        );

        assertEquals("APPROVED", approved.capture().getStatus());
        assertNull(approved.skus().getFirst().getStock());
    }

    @Test
    void approveRejectsProductPriceRangeThatDisagreesWithSkuPrices() {
        ProductCapture ready = new ProductCapture(
                "org-a", "user-1", "capture-price-mismatch", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN,
                "{\"skuCompleteness\":{\"expectedCount\":2,\"capturedCount\":2,\"complete\":true,\"source\":\"DOM\",\"hasVariants\":true,\"variantDimensionCount\":1,\"issues\":[]}}",
                "{}"
        );
        ready.startImageProcessing();
        ready.imagesReady();
        ProductCaptureImage image = new ProductCaptureImage(
                "org-a", ready.getId(), "https://cdn.example.com/a.webp",
                "MAIN", null, 0, "image/webp", "{}"
        );
        image.processed(image.getOriginalUrl());
        ProductCaptureSku low = new ProductCaptureSku(
                "org-a", ready.getId(), "model-1", "model-1", null, "红色",
                BigDecimal.TEN, BigDecimal.TEN, 5, "https://cdn.example.com/a.webp",
                "MODEL", "{\"颜色\":\"红色\"}", "{}", 0
        );
        ProductCaptureSku high = new ProductCaptureSku(
                "org-a", ready.getId(), "model-2", "model-2", null, "蓝色",
                new BigDecimal("12.00"), new BigDecimal("12.00"), 5,
                "https://cdn.example.com/a.webp", "MODEL",
                "{\"颜色\":\"蓝色\"}", "{}", 1
        );
        when(captureMapper.selectOne(any())).thenReturn(ready);
        when(imageMapper.selectList(any())).thenReturn(List.of(image));
        when(skuMapper.selectList(any())).thenReturn(List.of(low, high));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.approve(ready.getId(), principal("org-a")));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertTrue(failure.getMessage().contains("商品价格区间与 SKU 售价不一致"));
        assertEquals("READY", ready.getStatus());
    }

    @Test
    void approveRejectsSkuImageThatWasNotPartOfTheProcessedImageSet() {
        ProductCapture ready = new ProductCapture(
                "org-a", "user-1", "capture-unprocessed-sku-image", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN,
                "{\"skuCompleteness\":{\"expectedCount\":1,\"capturedCount\":1,\"complete\":true,\"source\":\"DOM\"},"
                        + "\"imageCompleteness\":{\"expectedCount\":2,\"capturedCount\":2,"
                        + "\"requiredSkuImageCount\":1,\"coveredSkuImageCount\":1,"
                        + "\"complete\":true,\"issues\":[]}}",
                "{}"
        );
        ready.startImageProcessing();
        ready.imagesReady();
        ProductCaptureImage main = new ProductCaptureImage(
                "org-a", ready.getId(), "https://cdn.example.com/main.webp",
                "MAIN", null, 0, "image/webp", "{}"
        );
        main.processed(main.getOriginalUrl());
        ProductCaptureSku sku = new ProductCaptureSku(
                "org-a", ready.getId(), "model-1", "model-1", null, "默认规格",
                BigDecimal.TEN, BigDecimal.TEN, 5, "https://cdn.example.com/model-1.webp",
                "MODEL", "{}", "{}", 0
        );
        when(captureMapper.selectOne(any())).thenReturn(ready);
        when(imageMapper.selectList(any())).thenReturn(List.of(main));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.approve(ready.getId(), principal("org-a")));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertTrue(failure.getMessage().contains("尚未处理完成的 SKU 图片"));
        assertEquals("READY", ready.getStatus());
    }

    @Test
    void approveRejectsDraftWithoutImageCompletenessMetadata() {
        ProductCapture ready = new ProductCapture(
                "org-a", "user-1", "capture-missing-image-metadata", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN,
                "{\"skuCompleteness\":{\"expectedCount\":1,\"capturedCount\":1,"
                        + "\"complete\":true,\"source\":\"DOM\",\"hasVariants\":false,"
                        + "\"variantDimensionCount\":0,\"issues\":[]}}",
                "{}"
        );
        ready.startImageProcessing();
        ready.imagesReady();
        ProductCaptureImage image = new ProductCaptureImage(
                "org-a", ready.getId(), "https://cdn.example.com/main.webp",
                "MAIN", null, 0, "image/webp", "{}"
        );
        image.processed(image.getOriginalUrl());
        ProductCaptureSku sku = new ProductCaptureSku(
                "org-a", ready.getId(), "model-1", "model-1", null, "默认规格",
                BigDecimal.TEN, BigDecimal.TEN, 5, image.getOriginalUrl(),
                "PRODUCT_MAIN", "{}", "{}", 0
        );
        when(captureMapper.selectOne(any())).thenReturn(ready);
        when(imageMapper.selectList(any())).thenReturn(List.of(image));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.approve(ready.getId(), principal("org-a")));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertTrue(failure.getMessage().contains("商品图片完整性声明"));
        assertEquals("READY", ready.getStatus());
    }

    @Test
    void approveAcceptsServerVerifiedCompleteSkuSet() {
        ProductCapture ready = new ProductCapture(
                "org-a", "user-1", "capture-complete-key", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN,
                "{\"skuCompleteness\":{\"expectedCount\":1,\"capturedCount\":1,\"complete\":true,\"source\":\"DOM\"},"
                        + "\"imageCompleteness\":{\"expectedCount\":2,\"capturedCount\":2,"
                        + "\"requiredSkuImageCount\":1,\"coveredSkuImageCount\":1,"
                        + "\"complete\":true,\"issues\":[]}}",
                "{}"
        );
        ready.startImageProcessing();
        ready.imagesReady();
        ProductCaptureImage image = new ProductCaptureImage(
                "org-a", ready.getId(), "https://cdn.example.com/a.webp",
                "MAIN", null, 0, "image/webp", "{}"
        );
        image.processed(image.getOriginalUrl());
        ProductCaptureImage variantImage = new ProductCaptureImage(
                "org-a", ready.getId(), "https://cdn.example.com/model-1.webp",
                "VARIANT", "红色 / M", 1, "image/webp", "{}"
        );
        variantImage.processed(variantImage.getOriginalUrl());
        ProductCaptureSku sku = new ProductCaptureSku(
                "org-a", ready.getId(), "model-1", "model-1", null, "红色 / M",
                BigDecimal.TEN, BigDecimal.TEN, 5, "https://cdn.example.com/model-1.webp",
                "MODEL", "{\"颜色\":\"红色\",\"尺码\":\"M\"}", "{}", 0
        );
        when(captureMapper.selectOne(any())).thenReturn(ready);
        when(imageMapper.selectList(any())).thenReturn(List.of(image, variantImage));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));

        ProductCaptureService.CaptureDetail approved = service.approve(
                ready.getId(), principal("org-a")
        );

        assertEquals("APPROVED", approved.capture().getStatus());
    }

    private Map<String, Object> captureRequest(List<Map<String, Object>> skus,
                                               Map<String, Object> skuCompleteness) {
        return Map.of(
                "sourceUrl", "https://shopee.ph/a-i.123.456",
                "title", "DOM 商品",
                "description", "完整描述",
                "skus", skus,
                "skuCompleteness", skuCompleteness
        );
    }

    private Map<String, Object> completeSku(String id, Map<String, Object> attributes) {
        return Map.of(
                "externalSkuId", id,
                "variationName", id,
                "price", "99.00",
                "originalPrice", "129.00",
                "stock", 8,
                "imageUrl", "https://cdn.example.com/" + id + ".webp",
                "imageSource", "MODEL",
                "attributes", attributes
        );
    }

    private Map<String, Object> completeSkuAtPrice(String id, BigDecimal price,
                                                    Map<String, Object> attributes) {
        return Map.of(
                "externalSkuId", id,
                "variationName", id,
                "price", price,
                "originalPrice", price,
                "stock", 8,
                "imageUrl", "https://cdn.example.com/" + id + ".webp",
                "imageSource", "MODEL",
                "attributes", attributes
        );
    }

    private ProductCaptureSku persistedSku(ProductCapture capture, String id, BigDecimal price,
                                            Map<String, Object> attributes) {
        return new ProductCaptureSku(
                capture.getOrganizationId(), capture.getId(), id, id, null, id,
                price, price, 8, "https://cdn.example.com/" + id + ".webp",
                "MODEL", new ObjectMapper().writeValueAsString(attributes), "{}", 0
        );
    }

    private ProductCapture capture(String organizationId) {
        return new ProductCapture(
                organizationId, "user-1", "capture-test-key", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "商品", "完整描述", null, null, "PHP",
                BigDecimal.TEN, BigDecimal.TEN, "{}", "{}"
        );
    }

    private PlatformPrincipal principal(String organizationId) {
        return principal(organizationId, "user-1");
    }

    private PlatformPrincipal principal(String organizationId, String userId) {
        return new PlatformPrincipal(
                userId, organizationId, "tenant", "13800000000", "测试用户",
                "password", true, List.of("ADMIN"), List.of(
                        "product:collect", "product:view", "product:edit",
                        "product:publish", "asset:watermark"
                )
        );
    }

    private AtomicReference<ProductCapture> storedCapture() {
        AtomicReference<ProductCapture> stored = new AtomicReference<>();
        when(captureMapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(captureMapper.insert(any(ProductCapture.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        return stored;
    }
}
