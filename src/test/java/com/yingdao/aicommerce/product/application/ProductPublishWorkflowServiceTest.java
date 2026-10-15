package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductPublishWorkflow;
import com.yingdao.aicommerce.product.mapper.ProductCaptureMapper;
import com.yingdao.aicommerce.product.mapper.ProductImageProcessingJobMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishAuditMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishWorkflowMapper;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.application.ShopIntegrationSettings;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductPublishWorkflowServiceTest {
    private ProductPublishWorkflowMapper workflowMapper;
    private ProductCaptureMapper captureMapper;
    private ShopMapper shopMapper;
    private ProductCaptureService captureService;
    private ShopIntegrationSettings shopSettings;
    private ProductPublishWorkflowService service;
    private PlatformPrincipal principal;
    private ProductCapture capture;

    @BeforeEach
    void setUp() {
        workflowMapper = mock(ProductPublishWorkflowMapper.class);
        captureMapper = mock(ProductCaptureMapper.class);
        shopMapper = mock(ShopMapper.class);
        captureService = mock(ProductCaptureService.class);
        shopSettings = mock(ShopIntegrationSettings.class);
        service = new ProductPublishWorkflowService(
                workflowMapper, captureMapper, mock(ProductImageProcessingJobMapper.class),
                mock(ProductPublishAuditMapper.class), shopMapper, captureService,
                shopSettings, new ObjectMapper());
        principal = new PlatformPrincipal("user-1", "org-a", "tenant", "user", "User", "", true,
                List.of(), List.of("asset:watermark", "product:edit", "product:publish"));
        capture = capture("org-a", "capture-key-1");
        when(captureService.get(capture.getId(), principal)).thenReturn(detail(capture));
        when(captureMapper.selectOne(any())).thenReturn(capture);
        when(shopSettings.publishingProvider()).thenReturn("DRY_RUN");
        Shop shop = mock(Shop.class);
        when(shop.getStatus()).thenReturn("ACTIVE");
        when(shopMapper.selectOne(any())).thenReturn(shop);
    }

    @Test
    void automaticQuoteIsServerOwnedAndIdempotentReplayDoesNotRefetch() {
        when(shopSettings.publishingProvider()).thenReturn("OFFICIAL");
        when(shopSettings.effectiveLivePublishing()).thenReturn(true);
        var fx = mock(PublishExchangeRates.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "exchangeRates", fx);
        var shop = mock(Shop.class);
        when(shop.getRegion()).thenReturn("MY");
        when(shopMapper.selectById("shop-1")).thenReturn(shop);
        var quote = Map.<String,Object>of("sourceCurrency", "PHP", "targetCurrency", "MYR", "rate", "0.06");
        when(fx.snapshot(any(), any())).thenReturn(quote);
        var request = Map.<String,Object>of("confirmed",true,"destinationPlatform","SHOPEE","shopId","shop-1",
                "autoConvertPrice",true,"exchangeRateSnapshot",Map.of("rate","999"));
        service.create(capture.getId(),request,"fx-workflow-key-123",principal);
        var inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class);
        verify(workflowMapper).insert(inserted.capture());
        assertEquals(quote,service.request(inserted.getValue()).get("exchangeRateSnapshot"));
        when(workflowMapper.findByIdempotency("org-a","fx-workflow-key-123")).thenReturn(inserted.getValue());
        service.create(capture.getId(),request,"fx-workflow-key-123",principal);
        verify(fx,times(1)).snapshot(any(),any());
    }
    @Test
    void templateIsServerOwnedAndReplayKeepsSnapshotWithoutConfigRefetch() {
        var templates=mock(ProductListingTemplateService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"listingTemplates",templates);
        var snapshot=new java.util.LinkedHashMap<String,Object>(Map.of("enabled",true,
                "description","fixed detail","weightGrams","200","packageLengthCm","5","packageWidthCm","25","packageHeightCm","25"));
        when(templates.snapshot(anyString(),anyString(),any())).thenReturn(snapshot);
        var body=Map.<String,Object>of("confirmed",true,"shopId","shop-1","destinationPlatform","SHOPEE",
                "weightGrams","999","listingTemplateSnapshot",Map.of("title","forged"));
        service.create(capture.getId(),body,"template-workflow-123",principal);
        var inserted=ArgumentCaptor.forClass(ProductPublishWorkflow.class);
        verify(workflowMapper).insert(inserted.capture());
        assertEquals("200",service.request(inserted.getValue()).get("weightGrams"));
        assertFalse(ProductListingTemplateService.snapshotFrom(service.request(inserted.getValue())).containsKey("title"));
        when(workflowMapper.findByIdempotency("org-a","template-workflow-123")).thenReturn(inserted.getValue());
        service.create(capture.getId(),body,"template-workflow-123",principal);
        verify(templates,times(1)).snapshot(anyString(),anyString(),any());
    }

    @Test
    void logisticsIsServerOwnedAndReplayIgnoresChangedBrowserChannels() {
        when(shopSettings.publishingProvider()).thenReturn("OFFICIAL");
        when(shopSettings.effectiveLivePublishing()).thenReturn(true);
        var logistics=mock(ProductListingLogisticsService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"listingLogistics",logistics);
        var snapshot=Map.<String,Object>of("organizationId","org-a","shopId","shop-1","mode","SELECTED","channelIds",List.of("200"),"version",1);
        when(logistics.snapshot("org-a","shop-1","CAPTURE")).thenReturn(snapshot);
        var body=new java.util.LinkedHashMap<String,Object>(Map.of("confirmed",true,"shopId","shop-1","destinationPlatform","SHOPEE",
                "listingLogisticsSnapshot",Map.of("mode","forged"),"logisticInfo",List.of(Map.of("logistic_id",999,"enabled",true))));
        service.create(capture.getId(),body,"logistics-workflow-123",principal);
        var inserted=ArgumentCaptor.forClass(ProductPublishWorkflow.class);verify(workflowMapper).insert(inserted.capture());
        assertEquals(snapshot,service.request(inserted.getValue()).get("listingLogisticsSnapshot"));
        assertFalse(service.request(inserted.getValue()).containsKey("logisticInfo"));
        when(workflowMapper.findByIdempotency("org-a","logistics-workflow-123")).thenReturn(inserted.getValue());
        body.put("logisticInfo",List.of(Map.of("logistic_id",888,"enabled",true)));
        service.create(capture.getId(),body,"logistics-workflow-123",principal);
        verify(logistics,times(1)).snapshot(anyString(),anyString(),anyString());
    }

    @Test
    void createsDurableWorkflowBoundToCaptureVersionAndNormalizedTarget() {
        Map<String, Object> request = Map.of(
                "confirmed", true,
                "destinationPlatform", "TIKTOK_SHOP",
                "shopId", "shop-1",
                "categoryId", "123"
        );

        ProductPublishWorkflowService.CreateResult result = service.create(
                capture.getId(), request, "workflow-key-123", principal);

        assertTrue(result.created());
        assertFalse(result.publicationReused());
        assertEquals("TIKTOK", result.workflow().destinationPlatform());
        assertEquals("IMAGES", result.workflow().currentStep());
        ArgumentCaptor<ProductPublishWorkflow> inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class);
        verify(workflowMapper).insert(inserted.capture());
        assertEquals(capture.getVersion(), inserted.getValue().getCaptureVersion());
        assertEquals(64, inserted.getValue().getRequestFingerprint().length());
        assertEquals(64, inserted.getValue().getPublicationTargetKey().length());
    }

    @Test
    void sameIdempotencyKeyWithDifferentCanonicalRequestReturnsConflict() {
        Map<String, Object> request = Map.of(
                "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-1",
                "categoryId", "123");
        service.create(capture.getId(), request, "workflow-key-123", principal);
        ArgumentCaptor<ProductPublishWorkflow> inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class);
        verify(workflowMapper).insert(inserted.capture());
        when(workflowMapper.findByIdempotency("org-a", "workflow-key-123"))
                .thenReturn(inserted.getValue());

        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                capture.getId(), Map.of(
                        "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-1",
                        "categoryId", "999"),
                "workflow-key-123", principal));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
    }

    @Test
    void equivalentPendingTargetIsReusedWhenClientRegeneratesIdempotencyKey() {
        Map<String, Object> request = Map.of(
                "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-1",
                "categoryId", "123");
        service.create(capture.getId(), request, "workflow-key-123", principal);
        ArgumentCaptor<ProductPublishWorkflow> inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class);
        verify(workflowMapper).insert(inserted.capture());
        when(workflowMapper.findByPublicationTarget(inserted.getValue().getPublicationTargetKey()))
                .thenReturn(inserted.getValue());

        ProductPublishWorkflowService.CreateResult retried = service.create(
                capture.getId(), request, "workflow-key-456", principal);

        assertFalse(retried.created());
        assertTrue(retried.publicationReused());
        assertEquals(inserted.getValue().getId(), retried.workflow().id());
        verify(workflowMapper, times(1)).insert(any(ProductPublishWorkflow.class));
    }

    @Test
    void canonicalizesInternalShopIdBeforeBuildingPublicationTarget() {
        service.create(capture.getId(), Map.of(
                        "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "SHOP-1"),
                "workflow-key-123", principal);

        ArgumentCaptor<ProductPublishWorkflow> inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class);
        verify(workflowMapper).insert(inserted.capture());
        assertEquals("shop-1", inserted.getValue().getShopId());
    }

    @Test
    void rejectsShopFromAnotherOrganizationWithoutLeakingIt() {
        when(shopMapper.selectOne(any())).thenReturn(null);

        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                capture.getId(), Map.of(
                        "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "other-shop"),
                "workflow-key-123", principal));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatus());
        verify(workflowMapper, never()).insert(any(ProductPublishWorkflow.class));
    }

    @Test
    void alreadyPublishedSameCaptureAndShopReturnsExistingWorkflowInsteadOfDuplicating() {
        capture.published();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "watermarkProvider", "AI_WATERMARK_V1");
        ProductPublishWorkflow existing = mock(ProductPublishWorkflow.class);
        when(existing.getId()).thenReturn("published-workflow");
        when(existing.getCaptureId()).thenReturn(capture.getId());
        when(existing.getCaptureVersion()).thenReturn(capture.getVersion());
        when(existing.getDestinationPlatform()).thenReturn("SHOPEE");
        when(existing.getShopId()).thenReturn("shop-1");
        when(existing.getStatus()).thenReturn("SUCCEEDED");
        when(existing.getCurrentStep()).thenReturn("COMPLETED");
        when(existing.getRequestJson()).thenReturn("{\"executionMode\":\"LIVE\"}");
        when(workflowMapper.findByPublicationTarget(any())).thenReturn(existing);

        ProductPublishWorkflowService.CreateResult result = service.create(
                capture.getId(), Map.of(
                        "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-1",
                        "categoryId", "different-request-is-ignored-after-real-publication"),
                "another-workflow-key", principal);

        assertFalse(result.created());
        assertTrue(result.publicationReused());
        assertEquals("published-workflow", result.workflow().id());
        verify(workflowMapper, never()).insert(any(ProductPublishWorkflow.class));
    }

    @Test
    void publishedCapturedProductCanSelectAnotherShopWithoutReprocessingApprovedImages() {
        when(shopSettings.publishingProvider()).thenReturn("OFFICIAL");
        when(shopSettings.effectiveLivePublishing()).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "watermarkProvider", "AI_WATERMARK_V1");
        capture.startImageProcessing(); capture.imagesReady(); capture.approve("reviewer-1"); capture.published();
        var publishedAt = capture.getPublishedAt(); var version = capture.getVersion();
        var publishOnly = new PlatformPrincipal("user-1", "org-a", "tenant", "user", "User", "", true,
                List.of(), List.of("product:publish"));
        when(captureService.get(capture.getId(), publishOnly)).thenReturn(detail(capture));

        var result = service.create(capture.getId(), Map.of("confirmed", true, "destinationPlatform", "SHOPEE",
                "shopId", "shop-2", "visionWatermarkCheck", true), "published-second-shop", publishOnly);

        assertTrue(result.created()); assertFalse(result.publicationReused());
        assertEquals("shop-2", result.workflow().shopId()); assertEquals("PUBLISH", result.workflow().currentStep());
        var inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class); verify(workflowMapper).insert(inserted.capture());
        assertEquals(false, service.request(inserted.getValue()).get("visionWatermarkCheck"));
        assertEquals(version, inserted.getValue().getCaptureVersion()); assertEquals("LIVE", result.workflow().executionMode());
        assertEquals("PUBLISHED", capture.getStatus()); assertEquals(publishedAt, capture.getPublishedAt());
        assertEquals(version, capture.getVersion()); verify(captureMapper, never()).updateById(any(ProductCapture.class));
    }

    @Test
    void publishedGeneratedProductStillSelectsAnotherShopWithoutWatermarkProcessing() {
        capture.published();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "watermarkProvider", "AI_WATERMARK_V1");
        var db = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "commerceDb", db);
        when(db.queryForObject("SELECT COUNT(*) FROM commerce_batch_publications WHERE organization_id=? AND capture_id=?",
                Integer.class, "org-a", capture.getId())).thenReturn(1);
        var result = service.create(capture.getId(), Map.of("confirmed", true, "destinationPlatform", "SHOPEE",
                "shopId", "shop-2"), "generated-second-shop", principal);
        assertTrue(result.created()); assertEquals("PUBLISH", result.workflow().currentStep());
        var inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class); verify(workflowMapper).insert(inserted.capture());
        assertEquals(false, service.request(inserted.getValue()).get("visionWatermarkCheck"));
    }

    @Test
    void unpublishedCapturedProductStillRequiresTheConfiguredVisionWatermarkCheck() {
        capture.startImageProcessing(); capture.imagesReady(); capture.approve("reviewer-1");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "watermarkProvider", "AI_WATERMARK_V1");
        var result = service.create(capture.getId(), Map.of("confirmed", true, "destinationPlatform", "SHOPEE",
                "shopId", "shop-2", "visionWatermarkCheck", false), "approved-first-upload", principal);
        assertTrue(result.created()); assertEquals("IMAGES", result.workflow().currentStep());
        var inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class); verify(workflowMapper).insert(inserted.capture());
        assertEquals(true, service.request(inserted.getValue()).get("visionWatermarkCheck"));
    }

    @Test
    void uncertainExternalCreateKeepsTargetReservedAndCannotBeDuplicated() {
        ProductPublishWorkflow existing = mock(ProductPublishWorkflow.class);
        when(existing.getId()).thenReturn("uncertain-workflow");
        when(existing.getCaptureId()).thenReturn(capture.getId());
        when(existing.getCaptureVersion()).thenReturn(capture.getVersion());
        when(existing.getDestinationPlatform()).thenReturn("SHOPEE");
        when(existing.getShopId()).thenReturn("shop-1");
        when(existing.getStatus()).thenReturn("FAILED");
        when(existing.getCurrentStep()).thenReturn("PUBLISH");
        when(existing.isPublicationAttempted()).thenReturn(true);
        when(existing.getRequestJson()).thenReturn("{\"executionMode\":\"LIVE\"}");
        when(workflowMapper.findByPublicationTarget(any())).thenReturn(existing);

        ProductPublishWorkflowService.CreateResult result = service.create(
                capture.getId(), Map.of(
                        "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-1"),
                "replacement-workflow-key", principal);

        assertFalse(result.created());
        assertTrue(result.publicationReused());
        assertEquals("uncertain-workflow", result.workflow().id());
        verify(workflowMapper, never()).insert(any(ProductPublishWorkflow.class));
    }

    @Test
    void dryRunSentinelIsKeptInRequestButStoredAsNullForeignKey() throws Exception {
        ProductPublishWorkflowService.CreateResult result = service.create(
                capture.getId(), Map.of(
                        "confirmed", true,
                        "destinationPlatform", "SHOPEE",
                        "shopId", "dry-run-shop",
                        "executionMode", "LIVE"),
                "workflow-dry-run-1", principal);

        assertTrue(result.created());
        assertEquals("dry-run-shop", result.workflow().shopId());
        ArgumentCaptor<ProductPublishWorkflow> inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class);
        verify(workflowMapper).insert(inserted.capture());
        assertNull(inserted.getValue().getShopId());
        Map<?, ?> storedRequest = new ObjectMapper().readValue(
                inserted.getValue().getRequestJson(), Map.class);
        assertEquals("dry-run-shop", storedRequest.get("shopId"));
        assertEquals("DRY_RUN", storedRequest.get("executionMode"));
        verify(shopMapper, never()).selectOne(any());
    }

    @Test
    void livePublishingRejectsDryRunSentinelBeforeCreatingWorkflow() {
        when(shopSettings.publishingProvider()).thenReturn("OFFICIAL");
        when(shopSettings.effectiveLivePublishing()).thenReturn(true);

        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                capture.getId(), Map.of(
                        "confirmed", true,
                        "destinationPlatform", "SHOPEE",
                        "shopId", "dry-run-shop"),
                "workflow-live-run-1", principal));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertTrue(failure.getMessage().contains("真实发布"));
        verify(workflowMapper, never()).insert(any(ProductPublishWorkflow.class));
    }

    @Test
    void dryRunWorkflowFailsValidationIfDeploymentSwitchesToLivePublishing() {
        ProductPublishWorkflowService.CreateResult created = service.create(
                capture.getId(), Map.of(
                        "confirmed", true,
                        "destinationPlatform", "SHOPEE",
                        "shopId", "dry-run-shop"),
                "workflow-dry-switch", principal);
        ArgumentCaptor<ProductPublishWorkflow> inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class);
        verify(workflowMapper).insert(inserted.capture());
        when(shopSettings.publishingProvider()).thenReturn("OFFICIAL");
        when(shopSettings.effectiveLivePublishing()).thenReturn(true);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.validateExecutionTarget(inserted.getValue()));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertTrue(failure.getMessage().contains("重新选择店铺"));
        assertTrue(created.created());
    }

    @Test
    void dryRunWithRealShopAlsoCannotBecomeLiveAfterConfigurationChange() {
        service.create(capture.getId(), Map.of(
                        "confirmed", true,
                        "destinationPlatform", "SHOPEE",
                        "shopId", "shop-1"),
                "workflow-real-shop-dry", principal);
        ArgumentCaptor<ProductPublishWorkflow> inserted = ArgumentCaptor.forClass(ProductPublishWorkflow.class);
        verify(workflowMapper).insert(inserted.capture());
        assertEquals("shop-1", inserted.getValue().getShopId());
        when(shopSettings.publishingProvider()).thenReturn("OFFICIAL");
        when(shopSettings.effectiveLivePublishing()).thenReturn(true);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.validateExecutionTarget(inserted.getValue()));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertTrue(failure.getMessage().contains("创建于 Dry Run"));
    }

    @Test
    void rejectsHalfEnabledLiveProviderInsteadOfMislabelingItAsDryRun() {
        when(shopSettings.publishingProvider()).thenReturn("OFFICIAL");
        when(shopSettings.effectiveLivePublishing()).thenReturn(false);

        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
                capture.getId(), Map.of(
                        "confirmed", true,
                        "destinationPlatform", "SHOPEE",
                        "shopId", "dry-run-shop"),
                "workflow-disabled-live", principal));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertTrue(failure.getMessage().contains("发布开关尚未启用"));
        verify(captureService, never()).get(any(), any());
        verify(workflowMapper, never()).insert(any(ProductPublishWorkflow.class));
    }

    @Test
    void getIsScopedByOrganizationAndCapture() {
        when(workflowMapper.selectOne(any())).thenReturn(null);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.get(capture.getId(), "workflow-1", principal));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatus());
    }

    private ProductCapture capture(String organizationId, String key) {
        return new ProductCapture(organizationId, "user-1", key, "fingerprint", "SHOPEE",
                "https://shopee.ph/item-i.123.456", "123", "456", "Product", "Description",
                "Category", "Brand", "PHP", BigDecimal.TEN, BigDecimal.TEN, "{}", "{}");
    }

    private ProductCaptureService.CaptureDetail detail(ProductCapture value) {
        return new ProductCaptureService.CaptureDetail(value, List.of(), List.of(), List.of(), Map.of(), Map.of());
    }
}
