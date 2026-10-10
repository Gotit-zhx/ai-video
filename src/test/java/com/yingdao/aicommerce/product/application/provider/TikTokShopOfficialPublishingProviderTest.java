package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TikTokShopOfficialPublishingProviderTest {

    @Test
    void localSeaShopChecksTheExactProductPayloadBeforeCreateAndConfirmsByGetProduct() {
        verifyConfirmedDraftFlow("LOCAL", null);
    }

    @Test
    void verifiedCrossBorderReplicationKeepsTheSameDraftCreateAndReadbackFlow() {
        for (Object methods : List.of("LOCAL_REPLICATION", List.of("LOCAL_REPLICATION"))) {
            verifyConfirmedDraftFlow("CROSS_BORDER", methods);
        }
    }

    private void verifyConfirmedDraftFlow(String sellerType, Object listingMethods) {
        ShopAuthorizationService authorizationService = mock(ShopAuthorizationService.class);
        TikTokShopOfficialClient client = mock(TikTokShopOfficialClient.class);
        ProductCaptureSkuMapper skuMapper = mock(ProductCaptureSkuMapper.class);
        ProductCaptureImageMapper imageMapper = mock(ProductCaptureImageMapper.class);
        MarketplaceImageDownloader downloader = mock(MarketplaceImageDownloader.class);
        TikTokShopOfficialPublishingProvider provider = provider(
                authorizationService, client, skuMapper, imageMapper, downloader);
        MarketplaceAccess.Connection connection = connection(sellerType, "PH");
        ProductCapture capture = capture();
        List<ProductCaptureSku> skus = List.of(
                sku("red-s", "RED-S", "Red / S", "19.90", "https://source.example/red.jpg",
                        "{\"Color\":\"Red\",\"Size\":\"S\"}", 0),
                sku("blue-m", "BLUE-M", "Blue / M", "21.90", "https://source.example/blue.jpg",
                        "{\"Color\":\"Blue\",\"Size\":\"M\"}", 1));
        ProductCaptureImage red = image(capture.getId(), "https://source.example/red.jpg", "MAIN", 0,
                "/api/v1/product-watermark/assets/red-processed");
        ProductCaptureImage blue = image(capture.getId(), "https://source.example/blue.jpg", "VARIANT", 1,
                "https://processed.example/blue.jpg");
        ProductPublishingProvider.ExternalCreateBoundary createBoundary =
                mock(ProductPublishingProvider.ExternalCreateBoundary.class);

        when(authorizationService.connectionForPublishing(
                capture.getOrganizationId(), "shop-local", MarketplacePlatform.TIKTOK_SHOP))
                .thenReturn(connection);
        when(skuMapper.selectList(any())).thenReturn(skus);
        when(imageMapper.selectList(any())).thenReturn(List.of(red, blue));
        when(downloader.downloadProductImage(anyString(), any(ProductCaptureImage.class))).thenReturn(
                new MarketplaceImageDownloader.ImageBinary(new byte[]{1, 2, 3}, "image/jpeg", ".jpg"));
        when(client.apiVersion()).thenReturn("202309");
        when(client.apiGet("/product/202507/global_listing_rules", "access-token", "shop-cipher", Map.of()))
                .thenReturn(Map.of("data", listingMethods == null ? Map.of() : Map.of("listing_methods", listingMethods,
                                "inventory_rules", List.of(Map.of("local_warehouse_id", "warehouse-1", "allocation_mode", "MANUAL"))),
                        "request_id", "rules-request"));
        when(client.apiGet("/product/202309/categories", "access-token", "shop-cipher",
                Map.of("locale", "en-US", "category_version", "v2"))).thenReturn(Map.of("data", Map.of(
                        "categories", List.of(Map.of("id", "601302", "is_leaf", true, "parent_id", "0",
                                "permission_statuses", List.of("AVAILABLE"))))));
        when(client.apiGet(eq("/product/202309/categories/601302/attributes"), eq("access-token"),
                eq("shop-cipher"), eq(Map.of("locale", "en-US", "category_version", "v2"))))
                .thenReturn(Map.of("data", Map.of("attributes", List.of())));
        when(client.apiMultipart(anyString(), eq(connection), any(), anyString(), anyString(),
                any(byte[].class), any())).thenReturn(
                        uploadResponse("main-uri"),
                        uploadResponse("attribute-red-uri"),
                        uploadResponse("attribute-blue-uri"));
        when(client.apiPost(eq("/product/202309/products/listing_check"), eq(connection),
                any(), any())).thenReturn(Map.of(
                        "data", Map.of("check_result", "PASS", "fail_reasons", List.of()),
                        "request_id", "listing-check-request"));
        when(client.apiPost(eq("/product/202309/products"), eq(connection), any(), any()))
                .thenReturn(Map.of(
                        "data", Map.of(
                                "product_id", "product-1",
                                "skus", List.of(
                                        Map.of("id", "remote-red", "seller_sku", "RED-S"),
                                        Map.of("id", "remote-blue", "seller_sku", "BLUE-M"))),
                        "request_id", "create-request"));
        when(client.apiGet(eq("/product/202309/products/product-1"), eq("access-token"),
                eq("shop-cipher"), eq(Map.of("return_draft_version", true))))
                .thenReturn(Map.of(
                        "data", Map.of(
                                "id", "product-1",
                                "status", "DRAFT",
                                "skus", List.of(
                                        remoteSku("remote-blue", "BLUE-M", "Blue", "M",
                                                "attribute-blue-uri"),
                                        remoteSku("remote-red", "RED-S", "Red", "S",
                                                "attribute-red-uri"))),
                        "request_id", "readback-request"));

        ProductPublishingProvider.PublishResult result = provider.publish(
                capture, "TIKTOK_SHOP", "shop-local", publishRequest(), createBoundary);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> listingPayload = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> createPayload = ArgumentCaptor.forClass(Map.class);
        InOrder ordered = inOrder(client, createBoundary);
        if ("CROSS_BORDER".equals(sellerType)) {
            ordered.verify(client).apiGet("/product/202507/global_listing_rules", "access-token", "shop-cipher", Map.of());
            assertThat(result.providerResponse()).containsEntry("listingMethod", "LOCAL_REPLICATION")
                    .containsEntry("listingRulesRequestId", "rules-request")
                    .containsEntry("inventoryAllocationMode", "MANUAL")
                    .containsEntry("inventoryWarehouseId", "warehouse-1")
                    .containsEntry("inventoryRules", List.of(Map.of("local_warehouse_id", "warehouse-1", "allocation_mode", "MANUAL")));
        } else {
            verify(client, org.mockito.Mockito.never()).apiGet(eq("/product/202507/global_listing_rules"), anyString(), anyString(), any());
            verify(client, org.mockito.Mockito.never()).apiGet(eq("/product/202309/categories"), anyString(), anyString(), any());
            assertThat(result.providerResponse()).containsEntry("listingMethod", "LOCAL");
        }
        ordered.verify(client).apiPost(eq("/product/202309/products/listing_check"), eq(connection),
                eq(Map.of()), listingPayload.capture());
        ordered.verify(createBoundary).enter();
        ordered.verify(client).apiPost(eq("/product/202309/products"), eq(connection),
                eq(Map.of()), createPayload.capture());
        ordered.verify(client).apiGet(eq("/product/202309/products/product-1"), eq("access-token"),
                eq("shop-cipher"), eq(Map.of("return_draft_version", true)));
        verify(client, times(1)).apiPost(eq("/product/202309/products"), eq(connection), any(), any());
        verify(createBoundary, times(1)).enter();

        Map<String, Object> expectedListingPayload = new LinkedHashMap<>(createPayload.getValue());
        expectedListingPayload.keySet().removeAll(
                List.of("save_mode", "category_version", "is_not_for_sale", "idempotency_key"));
        assertThat(listingPayload.getValue()).isEqualTo(expectedListingPayload);
        assertThat(createPayload.getValue())
                .containsEntry("save_mode", "AS_DRAFT")
                .containsEntry("category_version", "v2")
                .containsEntry("idempotency_key", "publish-key");
        verify(downloader, org.mockito.Mockito.times(2)).downloadProductImage(eq(red.getProcessedUrl()), eq(red));
        verify(downloader).downloadProductImage(eq(blue.getProcessedUrl()), eq(blue));
        verify(downloader, org.mockito.Mockito.never()).download(anyString());
        assertThat(result.externalId()).isEqualTo("product-1");
        assertThat(result.providerResponse())
                .containsEntry("verification", "CONFIRMED_BY_GET_PRODUCT")
                .containsEntry("remoteStatus", "DRAFT")
                .containsEntry("listingCheckRequestId", "listing-check-request")
                .containsEntry("readbackRequestId", "readback-request");
        assertThat(result.providerResponse().get("remoteSkuIds")).isEqualTo(Map.of(
                "RED-S", "remote-red", "BLUE-M", "remote-blue"));
    }

    @Test
    void listingCheckFailsClosedForFailedResultOrBlockerWarning() {
        TikTokShopOfficialPublishingProvider provider = provider(
                mock(ShopAuthorizationService.class), mock(TikTokShopOfficialClient.class),
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class));

        assertThatThrownBy(() -> provider.verifyListingCheck(Map.of(
                "data", Map.of(
                        "check_result", "FAILED",
                        "fail_reasons", List.of(Map.of(
                                "code", 12052700,
                                "message", "Product title invalid"))))))
                .hasMessageContaining("预检未通过")
                .hasMessageContaining("12052700")
                .hasMessageContaining("Product title invalid");

        assertThatThrownBy(() -> provider.verifyListingCheck(Map.of(
                "data", Map.of(
                        "check_result", "PASS",
                        "warnings", Map.of("message", "Product will not be sent for review")))))
                .hasMessageContaining("预检未通过")
                .hasMessageContaining("will not be sent for review");

        assertThatThrownBy(() -> provider.verifyListingCheck(Map.of("data", Map.of())))
                .hasMessageContaining("check_result=缺失");
    }

    @Test
    void createCriticalWarningsAreRedactedAndCannotBeReportedAsSuccess() {
        TikTokShopOfficialPublishingProvider provider = provider(
                mock(ShopAuthorizationService.class), mock(TikTokShopOfficialClient.class),
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class));

        assertThatThrownBy(() -> provider.rejectCreateWarnings("product-1", Map.of(
                "data", Map.of("warnings", List.of(Map.of(
                        "message", "Brand was cleared\naccess_token=very-secret-token"))))))
                .hasMessageContaining("product_id=product-1")
                .hasMessageContaining("critical warning")
                .hasMessageContaining("Brand was cleared")
                .hasMessageContaining("access_token=REDACTED")
                .hasMessageContaining("不会把本次发布标记为成功")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("very-secret-token"));
    }

    @Test
    void getProductMismatchIsAnExplicitFailedVerificationAndNeverAClaimOfSuccess() {
        TikTokShopOfficialPublishingProvider provider = provider(
                mock(ShopAuthorizationService.class), mock(TikTokShopOfficialClient.class),
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class));
        Map<String, Object> createPayload = Map.of("skus", List.of(
                Map.of("seller_sku", "RED-S", "sales_attributes", List.of(
                        Map.of("name", "Color", "value_name", "Red"))),
                Map.of("seller_sku", "BLUE-M", "sales_attributes", List.of(
                        Map.of("name", "Color", "value_name", "Blue")))));

        assertThatThrownBy(() -> provider.verifyReadback(
                "product-1", createPayload, Map.of("data", Map.of("product_id", "product-1")),
                Map.of("data", Map.of(
                        "id", "product-1",
                        "skus", List.of(Map.of("id", "remote-red", "seller_sku", "RED-S"))))))
                .hasMessageContaining("product_id=product-1")
                .hasMessageContaining("SKU 数量不一致")
                .hasMessageContaining("远端商品可能已经存在")
                .hasMessageContaining("禁止盲目重试");
    }

    @Test
    void getProductAllowsOnlySubmittedStatusesAndRejectsEveryOtherRemoteState() {
        TikTokShopOfficialPublishingProvider provider = provider(
                mock(ShopAuthorizationService.class), mock(TikTokShopOfficialClient.class),
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class));
        Map<String, Object> createPayload = Map.of("skus", List.of(
                Map.of("seller_sku", "RED-S")));
        Map<String, Object> createResponse = Map.of("data", Map.of(
                "product_id", "product-1",
                "skus", List.of(Map.of("id", "remote-red", "seller_sku", "RED-S"))));

        for (String status : List.of("DRAFT", "draft")) {
            TikTokShopOfficialPublishingProvider.ReadbackVerification verification =
                    provider.verifyReadback("product-1", createPayload, createResponse,
                            Map.of("data", Map.of(
                                    "id", "product-1",
                                    "status", status,
                                    "skus", List.of(Map.of(
                                            "id", "remote-red", "seller_sku", "RED-S")))));
            assertThat(verification.status()).isEqualTo(status);
        }

        for (String status : List.of(
                "FAILED", "REJECTED", "REJECT", "DELETED", "PENDING", "ACTIVATE", "FROZEN",
                "SELLER_DEACTIVATED", "PLATFORM_DEACTIVATED", "UNKNOWN", "failed")) {
            assertThatThrownBy(() -> provider.verifyReadback(
                    "product-1", createPayload, createResponse,
                    Map.of("data", Map.of(
                            "id", "product-1",
                            "status", status,
                            "skus", List.of(Map.of(
                                    "id", "remote-red", "seller_sku", "RED-S"))))))
                    .hasMessageContaining("product_id=product-1")
                    .hasMessageContaining("status=" + status)
                    .hasMessageContaining("人工核查")
                    .hasMessageContaining("禁止盲目重试");
        }
    }

    @Test
    void getProductRetriesNotFoundOrNotYetConsistentWithoutSleepingInTheTest() {
        TikTokShopOfficialClient client = mock(TikTokShopOfficialClient.class);
        TikTokShopOfficialPublishingProvider provider = provider(
                mock(ShopAuthorizationService.class), client,
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class));
        MarketplaceAccess.Connection connection = connection("LOCAL", "PH");
        Map<String, Object> createPayload = Map.of("save_mode", "AS_DRAFT", "skus", List.of(
                Map.of("seller_sku", "RED-S")));
        Map<String, Object> createResponse = Map.of("data", Map.of(
                "product_id", "product-1",
                "skus", List.of(Map.of("id", "remote-red", "seller_sku", "RED-S"))));
        Map<String, Object> inconsistent = Map.of("data", Map.of(
                "id", "product-1", "skus", List.of()));
        Map<String, Object> consistent = Map.of("data", Map.of(
                "id", "product-1", "status", "DRAFT",
                "skus", List.of(Map.of("id", "remote-red", "seller_sku", "RED-S"))));
        when(client.apiGet("/product/202309/products/product-1", "access-token",
                "shop-cipher", Map.of("return_draft_version", true)))
                .thenThrow(new MarketplaceApiException(
                        "TikTok Shop 官方接口失败（错误码 12052032）：The product does not exist"))
                .thenReturn(inconsistent, consistent);
        List<Long> delays = new ArrayList<>();

        TikTokShopOfficialPublishingProvider.ReadbackResult result = provider.readbackWithRetry(
                connection, "/product/202309/products", "product-1",
                createPayload, createResponse, millis -> delays.add(millis));

        assertThat(result.verification().status()).isEqualTo("DRAFT");
        assertThat(delays).containsExactly(1000L, 2000L);
        verify(client, times(3)).apiGet(
                "/product/202309/products/product-1", "access-token", "shop-cipher", Map.of("return_draft_version", true));

        TikTokShopOfficialClient forbiddenClient = mock(TikTokShopOfficialClient.class);
        TikTokShopOfficialPublishingProvider forbiddenProvider = provider(
                mock(ShopAuthorizationService.class), forbiddenClient,
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class));
        when(forbiddenClient.apiGet(anyString(), anyString(), anyString(), any()))
                .thenThrow(new MarketplaceApiException("TikTok Shop API forbidden"));
        List<Long> forbiddenDelays = new ArrayList<>();

        assertThatThrownBy(() -> forbiddenProvider.readbackWithRetry(
                connection, "/product/202309/products", "product-1",
                createPayload, createResponse, millis -> forbiddenDelays.add(millis)))
                .hasMessageContaining("官方 Get Product 请求失败")
                .hasMessageContaining("禁止盲目重试");
        assertThat(forbiddenDelays).isEmpty();
        verify(forbiddenClient, times(1)).apiGet(anyString(), anyString(), anyString(), any());
    }

    @Test
    void interruptedGetProductNetworkReadsHaveBoundedRetriesWithoutRecreatingProduct() {
        TikTokShopOfficialClient client = mock(TikTokShopOfficialClient.class);
        TikTokShopOfficialPublishingProvider provider = provider(
                mock(ShopAuthorizationService.class), client,
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class));
        when(client.apiGet(anyString(), anyString(), anyString(), any()))
                .thenThrow(new MarketplaceApiException("connection reset", new java.net.SocketException("socket closed")));
        List<Long> delays = new ArrayList<>();
        assertThatThrownBy(() -> provider.readbackWithRetry(connection("LOCAL", "MY"),
                "/product/202309/products", "already-created", Map.of(), Map.of(), delays::add))
                .hasMessageContaining("共尝试 6 次")
                .hasMessageContaining("already-created")
                .hasMessageContaining("禁止盲目重试");
        assertThat(delays).containsExactly(1000L, 2000L, 4000L, 8000L, 15000L);
        verify(client, times(6)).apiGet(anyString(), anyString(), anyString(), any());
        verify(client, org.mockito.Mockito.never()).apiPost(anyString(), any(), any(), any());
    }

    @Test
    void stockOverridesRejectFractionsInvalidValuesAndUnknownSkuKeys() {
        TikTokShopOfficialPublishingProvider provider = provider(
                mock(ShopAuthorizationService.class), mock(TikTokShopOfficialClient.class),
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class));
        ProductCapture capture = capture();
        List<ProductCaptureSku> skus = List.of(
                sku("red-s", "RED-S", "Red / S", "19.90", null,
                        "{\"Color\":\"Red\"}", 0));

        assertThatThrownBy(() -> provider.requiredStock(Map.of("publishStock", 1.5)))
                .hasMessageContaining("0 到 99999 的整数");
        assertThat(provider.requiredStock(Map.of("publishStock", 0))).isZero();
        assertThat(provider.requiredStock(Map.of("publishStock", 99_999))).isEqualTo(99_999);
        assertThatThrownBy(() -> provider.requiredStock(Map.of("publishStock", 100_000)))
                .hasMessageContaining("0 到 99999 的整数");
        assertThatThrownBy(() -> provider.buildSkus(
                capture, skus, Map.of("skuStocks", Map.of("red-s", "2.25")),
                "warehouse-1", 3, Map.of()))
                .hasMessageContaining("red-s")
                .hasMessageContaining("整数");
        assertThatThrownBy(() -> provider.buildSkus(
                capture, skus, Map.of("skuStocks", Map.of("missing-sku", 2)),
                "warehouse-1", 3, Map.of()))
                .hasMessageContaining("未知 skuKey")
                .hasMessageContaining("missing-sku");

        List<Map<String, Object>> zeroStock = provider.buildSkus(
                capture, skus, Map.of("skuStocks", Map.of("red-s", 0)),
                "warehouse-1", 3, Map.of());
        assertThat((List<?>) zeroStock.getFirst().get("inventory"))
                .isEqualTo(List.of(Map.of("warehouse_id", "warehouse-1")));
        List<Map<String, Object>> maximumStock = provider.buildSkus(
                capture, skus, Map.of("skuStocks", Map.of("red-s", 99_999)),
                "warehouse-1", 3, Map.of());
        assertThat((List<?>) maximumStock.getFirst().get("inventory"))
                .isEqualTo(List.of(Map.of(
                        "warehouse_id", "warehouse-1", "quantity", 99_999)));
        assertThatThrownBy(() -> provider.buildSkus(
                capture, skus, Map.of("skuStocks", Map.of("red-s", 100_000)),
                "warehouse-1", 3, Map.of()))
                .hasMessageContaining("red-s")
                .hasMessageContaining("0 到 99999");
    }

    @Test
    void crossBorderSellerWithoutConfirmedReplicationIsBlockedBeforeAnyMediaOrProductApiCall() {
        ShopAuthorizationService authorizationService = mock(ShopAuthorizationService.class);
        TikTokShopOfficialClient client = mock(TikTokShopOfficialClient.class);
        ProductCaptureSkuMapper skuMapper = mock(ProductCaptureSkuMapper.class);
        ProductCaptureImageMapper imageMapper = mock(ProductCaptureImageMapper.class);
        MarketplaceImageDownloader downloader = mock(MarketplaceImageDownloader.class);
        TikTokShopOfficialPublishingProvider provider = provider(
                authorizationService, client, skuMapper, imageMapper, downloader);
        ProductCapture capture = capture();
        when(authorizationService.connectionForPublishing(
                capture.getOrganizationId(), "shop-global", MarketplacePlatform.TIKTOK_SHOP))
                .thenReturn(connection("CROSS_BORDER", "PH"));
        when(client.apiGet("/product/202507/global_listing_rules", "access-token", "shop-cipher", Map.of()))
                .thenReturn(Map.of("data", Map.of("listing_methods", "GLOBAL_PUBLISHING")));

        assertThatThrownBy(() -> provider.publish(
                capture, "TIKTOK_SHOP", "shop-global", Map.of("listingMethod", "LOCAL_REPLICATION", "canCreateDraft", true)))
                .hasMessageContaining("跨境刊登模式未就绪")
                .hasMessageContaining("LOCAL_REPLICATION")
                .hasMessageContaining("已退役");
        verify(client).apiGet("/product/202507/global_listing_rules", "access-token", "shop-cipher", Map.of());
        org.mockito.Mockito.verifyNoMoreInteractions(client);
        verifyNoInteractions(skuMapper, imageMapper, downloader);
    }

    @Test
    void crossBorderWarehouseMismatchOrUnopenedCategoryStopsBeforeMediaAndCreation() {
        for (boolean wrongWarehouse : List.of(true, false)) {
            var authorization = mock(ShopAuthorizationService.class);
            var client = mock(TikTokShopOfficialClient.class);
            var skus = mock(ProductCaptureSkuMapper.class);
            var images = mock(ProductCaptureImageMapper.class);
            var downloader = mock(MarketplaceImageDownloader.class);
            var provider = provider(authorization, client, skus, images, downloader);
            var capture = capture();
            when(authorization.connectionForPublishing(capture.getOrganizationId(), "shop-global", MarketplacePlatform.TIKTOK_SHOP))
                    .thenReturn(connection("CROSS_BORDER", "MY"));
            when(skus.selectList(any())).thenReturn(List.of());
            when(images.selectList(any())).thenReturn(List.of());
            when(client.apiVersion()).thenReturn("202309");
            when(client.apiGet("/product/202507/global_listing_rules", "access-token", "shop-cipher", Map.of()))
                    .thenReturn(Map.of("data", Map.of("listing_methods", List.of("LOCAL_REPLICATION"), "inventory_rules",
                            List.of(Map.of("local_warehouse_id", wrongWarehouse ? "another-warehouse" : "warehouse-1",
                                    "allocation_mode", "MANUAL")))));
            when(client.apiGet("/product/202309/categories", "access-token", "shop-cipher",
                    Map.of("locale", "en-US", "category_version", "v2"))).thenReturn(Map.of("data", Map.of("categories",
                            List.of(Map.of("id", "601302", "is_leaf", true, "parent_id", "0", "local_name", "Women's T-shirts",
                                    "permission_statuses", List.of("NON_MAIN_CATEGORY"))))));
            var boundary = mock(ProductPublishingProvider.ExternalCreateBoundary.class);
            assertThatThrownBy(() -> provider.publish(capture, "TIKTOK_SHOP", "shop-global", publishRequest(), boundary))
                    .hasMessageContaining(wrongWarehouse ? "所选发货仓库" : "请切换已开通该类目的店铺");
            verify(client, org.mockito.Mockito.never()).apiPost(anyString(), any(), anyMap(), anyMap());
            verify(client, org.mockito.Mockito.never()).apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap());
            verifyNoInteractions(downloader, boundary);
            if (wrongWarehouse) verify(client, org.mockito.Mockito.never()).apiGet(eq("/product/202309/categories"), anyString(), anyString(), anyMap());
        }
    }

    @Test
    void crossBorderCategoryRequiresPermissionForTheLeafAndEveryReturnedAncestor() {
        for (Object parentPermission : java.util.Arrays.asList(List.of("AVAILABLE"), List.of("NON_MAIN_CATEGORY"),
                List.of("AVAILABLE", "INVITE_ONLY"), List.of(), null)) {
            var client = mock(TikTokShopOfficialClient.class);
            when(client.apiVersion()).thenReturn("202309");
            var parent = new LinkedHashMap<String, Object>();
            parent.put("id", "parent"); parent.put("parent_id", "0"); parent.put("is_leaf", false);
            parent.put("permission_statuses", parentPermission);
            when(client.apiGet("/product/202309/categories", "access-token", "shop-cipher",
                    Map.of("locale", "en-US", "category_version", "v2"))).thenReturn(Map.of("data", Map.of("categories",
                            List.of(Map.of("id", "601302", "is_leaf", true, "parent_id", "parent",
                                    "permission_statuses", List.of("AVAILABLE")), parent))));
            var provider = provider(mock(ShopAuthorizationService.class), client, mock(ProductCaptureSkuMapper.class),
                    mock(ProductCaptureImageMapper.class), mock(MarketplaceImageDownloader.class));
            if (List.of("AVAILABLE").equals(parentPermission)) {
                provider.requireAvailableCrossBorderCategory(connection("CROSS_BORDER", "MY"), "601302", "v2");
            } else {
                assertThatThrownBy(() -> provider.requireAvailableCrossBorderCategory(connection("CROSS_BORDER", "MY"), "601302", "v2"))
                        .hasMessageContaining("发布权限");
            }
        }
    }

    private TikTokShopOfficialPublishingProvider provider(
            ShopAuthorizationService authorizationService,
            TikTokShopOfficialClient client,
            ProductCaptureSkuMapper skuMapper,
            ProductCaptureImageMapper imageMapper,
            MarketplaceImageDownloader downloader) {
        TikTokProductVideoUploader videoUploader = mock(TikTokProductVideoUploader.class);
        when(videoUploader.upload(any(), any(), any())).thenReturn(TikTokProductVideoUploader.UploadedVideo.none());
        return new TikTokShopOfficialPublishingProvider(
                authorizationService, client, skuMapper, imageMapper, downloader, new ObjectMapper(), videoUploader, new TikTokProductDescription(client, downloader));
    }

    private ProductCapture capture() {
        return new ProductCapture(
                "org-1", "user-1", "capture-key", "fingerprint", "SHOPEE",
                "https://shopee.ph/item-i.1.2", "source-shop", "2", "Summer shirt",
                "Comfortable summer shirt", "Shirts", "", "PHP",
                new BigDecimal("19.90"), new BigDecimal("21.90"), "{}", "{}");
    }

    private ProductCaptureSku sku(String key, String sellerSku, String variation,
                                  String price, String imageUrl, String attributes, int order) {
        return new ProductCaptureSku("org-1", "capture-1", key, null, sellerSku,
                variation, new BigDecimal(price), null, null, imageUrl,
                "MODEL", attributes, "{}", order);
    }

    private ProductCaptureImage image(String captureId, String originalUrl, String role, int order, String processedUrl) {
        ProductCaptureImage image = new ProductCaptureImage(
                "org-1", captureId, originalUrl, role, null, order, "image/jpeg", "{}");
        image.processed(processedUrl);
        return image;
    }

    private MarketplaceAccess.Connection connection(String sellerType, String region) {
        Shop shop = new Shop("org-1", MarketplacePlatform.TIKTOK_SHOP, "external-shop",
                "merchant-1", "shop-cipher", sellerType, "Demo", region, "[]");
        MarketplaceAccess.TokenSet tokens = new MarketplaceAccess.TokenSet(
                "access-token", "refresh-token", Instant.now().plusSeconds(3600), null, List.of());
        return new MarketplaceAccess.Connection(shop, tokens);
    }

    private Map<String, Object> publishRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("publishStock", 5);
        request.put("categoryId", "601302");
        request.put("warehouseId", "warehouse-1");
        request.put("weightGrams", "500");
        request.put("packageLengthCm", "10");
        request.put("packageWidthCm", "20");
        request.put("packageHeightCm", "30");
        request.put("idempotencyKey", "publish-key");
        return request;
    }

    private Map<String, Object> uploadResponse(String uri) {
        return Map.of("data", Map.of("uri", uri));
    }

    private Map<String, Object> remoteSku(String id, String sellerSku, String color,
                                          String size, String imageUri) {
        return Map.of(
                "id", id,
                "seller_sku", sellerSku,
                "sales_attributes", List.of(
                        Map.of("id", "generated-color-id", "name", "Color",
                                "value_id", "generated-" + color, "value_name", color,
                                "sku_img", Map.of("uri", imageUri)),
                        Map.of("id", "generated-size-id", "name", "Size",
                                "value_id", "generated-" + size, "value_name", size)));
    }
}
