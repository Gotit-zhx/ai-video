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
        ShopAuthorizationService authorizationService = mock(ShopAuthorizationService.class);
        TikTokShopOfficialClient client = mock(TikTokShopOfficialClient.class);
        ProductCaptureSkuMapper skuMapper = mock(ProductCaptureSkuMapper.class);
        ProductCaptureImageMapper imageMapper = mock(ProductCaptureImageMapper.class);
        MarketplaceImageDownloader downloader = mock(MarketplaceImageDownloader.class);
        TikTokShopOfficialPublishingProvider provider = provider(
                authorizationService, client, skuMapper, imageMapper, downloader);
        MarketplaceAccess.Connection connection = connection("LOCAL", "PH");
        ProductCapture capture = capture();
        List<ProductCaptureSku> skus = List.of(
                sku("red-s", "RED-S", "Red / S", "19.90", "https://source.example/red.jpg",
                        "{\"Color\":\"Red\",\"Size\":\"S\"}", 0),
                sku("blue-m", "BLUE-M", "Blue / M", "21.90", "https://source.example/blue.jpg",
                        "{\"Color\":\"Blue\",\"Size\":\"M\"}", 1));
        ProductCaptureImage red = image("https://source.example/red.jpg", "MAIN", 0,
                "https://processed.example/red.jpg");
        ProductCaptureImage blue = image("https://source.example/blue.jpg", "VARIANT", 1,
                "https://processed.example/blue.jpg");
        ProductPublishingProvider.ExternalCreateBoundary createBoundary =
                mock(ProductPublishingProvider.ExternalCreateBoundary.class);

        when(authorizationService.connectionForPublishing(
                capture.getOrganizationId(), "shop-local", MarketplacePlatform.TIKTOK_SHOP))
                .thenReturn(connection);
        when(skuMapper.selectList(any())).thenReturn(skus);
        when(imageMapper.selectList(any())).thenReturn(List.of(red, blue));
        when(downloader.download(anyString())).thenReturn(
                new MarketplaceImageDownloader.ImageBinary(new byte[]{1, 2, 3}, "image/jpeg", ".jpg"));
        when(client.apiVersion()).thenReturn("202309");
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
                eq("shop-cipher"), eq(Map.of())))
                .thenReturn(Map.of(
                        "data", Map.of(
                                "id", "product-1",
                                "status", "PENDING",
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
        ordered.verify(client).apiPost(eq("/product/202309/products/listing_check"), eq(connection),
                eq(Map.of()), listingPayload.capture());
        ordered.verify(createBoundary).enter();
        ordered.verify(client).apiPost(eq("/product/202309/products"), eq(connection),
                eq(Map.of()), createPayload.capture());
        ordered.verify(client).apiGet(eq("/product/202309/products/product-1"), eq("access-token"),
                eq("shop-cipher"), eq(Map.of()));

        Map<String, Object> expectedListingPayload = new LinkedHashMap<>(createPayload.getValue());
        expectedListingPayload.keySet().removeAll(
                List.of("save_mode", "category_version", "is_not_for_sale", "idempotency_key"));
        assertThat(listingPayload.getValue()).isEqualTo(expectedListingPayload);
        assertThat(createPayload.getValue())
                .containsEntry("save_mode", "LISTING")
                .containsEntry("category_version", "v2")
                .containsEntry("idempotency_key", "publish-key");
        assertThat(result.externalId()).isEqualTo("product-1");
        assertThat(result.providerResponse())
                .containsEntry("verification", "CONFIRMED_BY_GET_PRODUCT")
                .containsEntry("remoteStatus", "PENDING")
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

        for (String status : List.of("PENDING", "ACTIVATE", "pending", "activate")) {
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
                "FAILED", "REJECTED", "REJECT", "DELETED", "DRAFT", "FROZEN",
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
    void getProductRetriesOnlyNotFoundOrNotYetConsistentWithoutSleepingInTheTest() {
        TikTokShopOfficialClient client = mock(TikTokShopOfficialClient.class);
        TikTokShopOfficialPublishingProvider provider = provider(
                mock(ShopAuthorizationService.class), client,
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class));
        MarketplaceAccess.Connection connection = connection("LOCAL", "PH");
        Map<String, Object> createPayload = Map.of("skus", List.of(
                Map.of("seller_sku", "RED-S")));
        Map<String, Object> createResponse = Map.of("data", Map.of(
                "product_id", "product-1",
                "skus", List.of(Map.of("id", "remote-red", "seller_sku", "RED-S"))));
        Map<String, Object> inconsistent = Map.of("data", Map.of(
                "id", "product-1", "skus", List.of()));
        Map<String, Object> consistent = Map.of("data", Map.of(
                "id", "product-1", "status", "PENDING",
                "skus", List.of(Map.of("id", "remote-red", "seller_sku", "RED-S"))));
        when(client.apiGet("/product/202309/products/product-1", "access-token",
                "shop-cipher", Map.of()))
                .thenThrow(new MarketplaceApiException(
                        "TikTok Shop 官方接口失败（错误码 12052032）：The product does not exist"))
                .thenReturn(inconsistent, consistent);
        List<Long> delays = new ArrayList<>();

        TikTokShopOfficialPublishingProvider.ReadbackResult result = provider.readbackWithRetry(
                connection, "/product/202309/products", "product-1",
                createPayload, createResponse, millis -> delays.add(millis));

        assertThat(result.verification().status()).isEqualTo("PENDING");
        assertThat(delays).containsExactly(500L, 500L);
        verify(client, times(3)).apiGet(
                "/product/202309/products/product-1", "access-token", "shop-cipher", Map.of());

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
    void crossBorderSellerIsBlockedBeforeAnyMediaOrProductApiCall() {
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

        assertThatThrownBy(() -> provider.publish(
                capture, "TIKTOK_SHOP", "shop-global", Map.of()))
                .hasMessageContaining("CROSS_BORDER/Global Seller")
                .hasMessageContaining("local replication")
                .hasMessageContaining("禁止");
        verifyNoInteractions(client, skuMapper, imageMapper, downloader);
    }

    private TikTokShopOfficialPublishingProvider provider(
            ShopAuthorizationService authorizationService,
            TikTokShopOfficialClient client,
            ProductCaptureSkuMapper skuMapper,
            ProductCaptureImageMapper imageMapper,
            MarketplaceImageDownloader downloader) {
        return new TikTokShopOfficialPublishingProvider(
                authorizationService, client, skuMapper, imageMapper, downloader, new ObjectMapper());
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

    private ProductCaptureImage image(String originalUrl, String role, int order, String processedUrl) {
        ProductCaptureImage image = new ProductCaptureImage(
                "org-1", "capture-1", originalUrl, role, null, order, "image/jpeg", "{}");
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
        request.put("categoryId", "category-1");
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
