package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficialPublishingPayloadTest {
    @Test
    void shopeeGroupsColorBeforeSizeEvenWhenCapturedAttributesStartWithSize() {
        var provider = shopeeProvider(mock(ShopeeOfficialClient.class), mock(MarketplaceImageDownloader.class));
        var skus = List.of(
            sku("pink-m", "PINK-M", "Pink / M", "19.90", "https://img.example/pink.jpg",
                    "{\"Size\":\"M(40-55KG)\",\"Color\":\"Pink\"}", 0),
            sku("black-s", "BLACK-S", "Black / S", "19.90", "https://img.example/black.jpg",
                    "{\"Size\":\"S(35-40KG)\",\"Color\":\"Black\"}", 1),
            sku("pink-s", "PINK-S", "Pink / S", "19.90", "https://img.example/pink.jpg",
                    "{\"Size\":\"S(35-40KG)\",\"Color\":\"Pink\"}", 2)
        );
        var payload = provider.variationPayload("12345", skus, 10,
                Map.of("https://img.example/pink.jpg","image-pink","https://img.example/black.jpg","image-black"),
                Map.of());
        @SuppressWarnings("unchecked")
        var tiers = (List<Map<String,Object>>) payload.get("standardise_tier_variation");
        assertThat(tiers).extracting(tier -> tier.get("variation_name")).containsExactly("Color","Size");
        @SuppressWarnings("unchecked")
        var colors = (List<Map<String,Object>>) tiers.getFirst().get("variation_option_list");
        @SuppressWarnings("unchecked")
        var sizes = (List<Map<String,Object>>) tiers.get(1).get("variation_option_list");
        assertThat(colors).extracting(option -> option.get("variation_option_name")).containsExactly("Pink","Black");
        assertThat(sizes).extracting(option -> option.get("variation_option_name"))
                .containsExactly("S(35-40KG)","M(40-55KG)");
        @SuppressWarnings("unchecked")
        var models = (List<Map<String,Object>>) payload.get("model");
        assertThat(models).extracting(model -> model.get("tier_index"))
                .containsExactly(List.of(0,1),List.of(1,0),List.of(0,0));
    }

    @Test void shopeeVariantPricesUseEachSkuOriginalInsteadOfPromotionalPrice() {
        var provider = shopeeProvider(mock(ShopeeOfficialClient.class), mock(MarketplaceImageDownloader.class));
        var skus = List.of(
            new ProductCaptureSku("org-1", "capture-1", "black", "1", "black", "Black", new BigDecimal("0.01"), new BigDecimal("15.80"), 9, "https://img.example/black.jpg", "MODEL", "{\"Color\":\"Black\"}", "{}", 0),
            new ProductCaptureSku("org-1", "capture-1", "white", "2", "white", "White", new BigDecimal("0.01"), new BigDecimal("17.80"), 9, "https://img.example/white.jpg", "MODEL", "{\"Color\":\"White\"}", "{}", 1));
        var result = provider.variationPayload("12345", skus, 9, Map.of("https://img.example/black.jpg", "black-image", "https://img.example/white.jpg", "white-image"),
            Map.of("exchangeRateSnapshot", Map.of("targetCurrency", "PHP", "rate", "15.395109")));
        var models = (List<Map<String,Object>>) result.get("model");
        assertThat(models.get(0).get("original_price")).isEqualTo(new BigDecimal("243.24"));
        assertThat(models.get(1).get("original_price")).isEqualTo(new BigDecimal("274.03"));
    }

    @Test
    void shopeeUsesStandardisedVariationsAndSellerStock() throws Exception {
        ShopeeOfficialPublishingProvider provider = shopeeProvider(
                mock(ShopeeOfficialClient.class), mock(MarketplaceImageDownloader.class));
        List<ProductCaptureSku> skus = List.of(
                sku("red-s", "RED-S", "Red / S", "19.90", "https://img.example/red.jpg",
                        "{\"Color\":\"Red\",\"Size\":\"S\"}", 0),
                sku("blue-m", "BLUE-M", "Blue / M", "21.90", "https://img.example/blue.jpg",
                        "{\"Color\":\"Blue\",\"Size\":\"M\"}", 1)
        );

        Map<String, Object> payload = provider.variationPayload("12345", skus, 3,
                Map.of(
                        "https://img.example/red.jpg", "image-red",
                        "https://img.example/blue.jpg", "image-blue"
                ), Map.of("skuStocks", Map.of("red-s", 7)));

        assertThat(payload).containsKey("standardise_tier_variation");
        assertThat(payload).doesNotContainKeys("tier_variation", "normal_stock");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiers =
                (List<Map<String, Object>>) payload.get("standardise_tier_variation");
        assertThat(tiers.getFirst()).containsEntry("variation_id", 0)
                .containsEntry("variation_group_id", 0)
                .containsEntry("variation_name", "Color")
                .containsKey("variation_option_list")
                .doesNotContainKeys("name", "option_list");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstOptions =
                (List<Map<String, Object>>) tiers.getFirst().get("variation_option_list");
        assertThat(firstOptions.getFirst()).containsEntry("variation_option_id", 0)
                .containsEntry("variation_option_name", "Red")
                .containsEntry("image_id", "image-red")
                .doesNotContainKeys("option", "image");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) payload.get("model");
        assertThat(models).hasSize(2);
        assertThat(models.getFirst()).containsEntry("seller_stock", List.of(Map.of("stock", 7)));
        assertThat(models.get(1)).containsEntry("seller_stock", List.of(Map.of("stock", 3)));
        assertThat(new ObjectMapper().writeValueAsString(payload)).doesNotContain("normal_stock");
    }

    @Test
    void shopeeInitializesFirstFiftyModelsAndAddsTheRemainingModelsInBatches() {
        ShopeeOfficialClient client = mock(ShopeeOfficialClient.class);
        ShopeeOfficialPublishingProvider provider = shopeeProvider(
                client, mock(MarketplaceImageDownloader.class));
        MarketplaceAccess.Connection connection = shopeeConnection();
        List<Map<String, Object>> models = IntStream.range(0, 96)
                .mapToObj(this::shopeeModel)
                .toList();
        Map<String, Object> payload = Map.of(
                "item_id", 12345L,
                "standardise_tier_variation", List.of(Map.of("variation_id", 0)),
                "model", models);
        when(client.shopPost(anyString(), eq(connection), any()))
                .thenReturn(Map.of("response", Map.of()));
        List<Map<String, Object>> initializedModels = IntStream.range(0, 50)
                .mapToObj(index -> Map.<String, Object>of("model_id", 9000L + index,
                        "model_sku", "SKU-" + index, "tier_index", List.of(index)))
                .toList();
        when(client.shopGet("/api/v2/product/get_model_list", connection, Map.of("item_id", 12345L)))
                .thenReturn(Map.of("response", Map.of("model", initializedModels)));

        ShopeeOfficialPublishingProvider.VariationSubmission submission =
                provider.submitVariations(connection, payload);

        ArgumentCaptor<Map<String, ?>> initPayload = ArgumentCaptor.forClass(Map.class);
        verify(client).shopPost(eq("/api/v2/product/init_tier_variation"),
                eq(connection), initPayload.capture());
        assertThat((List<?>) initPayload.getValue().get("model")).hasSize(50);
        assertThat(initPayload.getValue()).doesNotContainKey("model_list");
        verify(client).shopGet("/api/v2/product/get_model_list", connection, Map.of("item_id", 12345L));

        ArgumentCaptor<Map<String, ?>> addPayload = ArgumentCaptor.forClass(Map.class);
        verify(client, times(1)).shopPost(eq("/api/v2/product/add_model"),
                eq(connection), addPayload.capture());
        assertThat(addPayload.getValue().get("item_id")).isEqualTo(12345L);
        assertThat(addPayload.getValue()).doesNotContainKey("model");
        assertThat((List<?>) addPayload.getValue().get("model_list")).hasSize(46);
        assertThat(submission.addModelResponses()).hasSize(1);
    }

    @Test
    void shopeeRejectsFractionalStockInsteadOfTruncatingIt() {
        ShopeeOfficialPublishingProvider provider = shopeeProvider(
                mock(ShopeeOfficialClient.class), mock(MarketplaceImageDownloader.class));

        assertThatThrownBy(() -> provider.requiredStock(Map.of("publishStock", 1.5)))
                .hasMessageContaining("0 到 999999 的整数");

        ProductCaptureSku sku = sku("red-s", "RED-S", "Red / S", "19.90",
                "https://img.example/red.jpg", "{\"Color\":\"Red\"}", 0);
        assertThatThrownBy(() -> provider.variationPayload("12345", List.of(sku), 3,
                Map.of("https://img.example/red.jpg", "image-red"),
                Map.of("skuStocks", Map.of("red-s", "2.25"))))
                .hasMessageContaining("red-s")
                .hasMessageContaining("整数");
        assertThatThrownBy(() -> provider.variationPayload("12345", List.of(sku), 3,
                Map.of("https://img.example/red.jpg", "image-red"),
                Map.of("skuStocks", Map.of("missing-sku", 2))))
                .hasMessageContaining("不存在的 SKU Key")
                .hasMessageContaining("missing-sku");
    }

    @Test
    void shopeeRequiresAtLeastOneValidEnabledLogisticChannel() {
        ShopeeOfficialPublishingProvider provider = shopeeProvider(
                mock(ShopeeOfficialClient.class), mock(MarketplaceImageDownloader.class));

        assertThatThrownBy(() -> provider.requiredLogisticInfo(List.of()))
                .hasMessageContaining("必须选择");
        assertThatThrownBy(() -> provider.requiredLogisticInfo(
                List.of(Map.of("logistic_id", 123L, "enabled", false))))
                .hasMessageContaining("至少要启用一个");
        assertThatThrownBy(() -> provider.requiredLogisticInfo(
                List.of(Map.of("logistic_id", 123L, "enabled", 1))))
                .hasMessageContaining("true 或 false");

        assertThat(provider.requiredLogisticInfo(
                List.of(Map.of("logistic_id", "123", "enabled", "true"))))
                .containsExactly(Map.of("logistic_id", 123L, "enabled", true));
    }

    @Test
    void shopeeOnlyAcceptsCompleteUniqueTierIndexToModelIdReadback() {
        ShopeeOfficialPublishingProvider provider = shopeeProvider(
                mock(ShopeeOfficialClient.class), mock(MarketplaceImageDownloader.class));
        List<Map<String, Object>> expected = IntStream.range(0, 3)
                .mapToObj(this::shopeeModel)
                .toList();
        List<Map<String, Object>> complete = IntStream.range(0, 3)
                .mapToObj(index -> Map.<String, Object>of(
                        "model_id", 9000L + index,
                        "tier_index", List.of((long) index)))
                .toList();

        assertThat(provider.completeModelMappings(complete, expected)).isTrue();
        assertThat(provider.completeModelMappings(List.of(
                Map.of("model_id", 9000L, "tier_index", List.of(0)),
                Map.of("model_id", 9001L, "tier_index", List.of(1))), expected)).isFalse();
        assertThat(provider.completeModelMappings(List.of(
                Map.of("model_id", 9000L, "tier_index", List.of(0)),
                Map.of("model_id", 9000L, "tier_index", List.of(1)),
                Map.of("model_id", 9002L, "tier_index", List.of(2))), expected)).isFalse();
        assertThat(provider.completeModelMappings(List.of(
                Map.of("model_id", 9000L, "tier_index", List.of(3)),
                Map.of("model_id", 9001L, "tier_index", List.of(4)),
                Map.of("model_id", 9002L, "tier_index", List.of(5))), expected)).isFalse();
    }

    @Test
    void shopeeLocalProductRouteFailsClosedForAbnormalOrUnknownCbscShop() {
        ShopeeOfficialClient client = mock(ShopeeOfficialClient.class);
        ShopeeOfficialPublishingProvider provider = shopeeProvider(
                client, mock(MarketplaceImageDownloader.class));
        MarketplaceAccess.Connection connection = shopeeConnection();

        when(client.shopGet(eq(ShopeeOfficialClient.SHOP_INFO_PATH), eq(connection), eq(Map.of())))
                .thenReturn(Map.of("response", Map.of("status", "NORMAL", "is_upgraded_cbsc", false)))
                .thenReturn(Map.of("response", Map.of("status", "FROZEN", "is_upgraded_cbsc", false)))
                .thenReturn(Map.of("response", Map.of("status", "NORMAL")))
                .thenReturn(Map.of("response", Map.of("status", "NORMAL", "is_upgraded_cbsc", true)));

        provider.verifyLocalProductRoute(connection);
        assertThatThrownBy(() -> provider.verifyLocalProductRoute(connection))
                .hasMessageContaining("不是 NORMAL");
        assertThatThrownBy(() -> provider.verifyLocalProductRoute(connection))
                .hasMessageContaining("缺少 is_upgraded_cbsc");
        assertThatThrownBy(() -> provider.verifyLocalProductRoute(connection))
                .hasMessageContaining("global_product");
    }

    @Test
    void shopeeImageUploadMapsOriginalAndProcessedUrlsAndSendsScene() {
        ShopeeOfficialClient client = mock(ShopeeOfficialClient.class);
        MarketplaceImageDownloader downloader = mock(MarketplaceImageDownloader.class);
        ShopeeOfficialPublishingProvider provider = shopeeProvider(client, downloader);
        MarketplaceAccess.Connection connection = shopeeConnection();
        ProductCaptureImage image = new ProductCaptureImage(
                "org-1", "capture-1", "https://source.example/original.jpg",
                "MAIN", null, 0, "image/jpeg", "{}");
        image.processed("https://cdn.example/processed.jpg");
        when(downloader.downloadProductImage("https://cdn.example/processed.jpg", image))
                .thenReturn(new MarketplaceImageDownloader.ImageBinary(
                        new byte[]{1, 2, 3}, "image/jpeg", ".jpg"));
        when(client.shopMultipart(anyString(), eq(connection), anyString(), anyString(),
                any(byte[].class), any())).thenReturn(Map.of(
                        "response", Map.of("image_info", Map.of("image_id", "image-1"))));

        Map<String, String> uploaded = provider.uploadImages(connection, List.of(image));

        assertThat(uploaded).containsEntry("https://source.example/original.jpg", "image-1")
                .containsEntry("https://cdn.example/processed.jpg", "image-1");
        verify(client).shopMultipart(anyString(), eq(connection), anyString(), eq("image/jpeg"),
                any(byte[].class), eq(Map.of("scene", "normal")));
    }

    @Test
    void tiktokSeaPackageUsesKilogramsAndWholeCentimeters() {
        TikTokShopOfficialPublishingProvider provider = tiktokProvider();

        assertThat(provider.packageWeight(new BigDecimal("1250")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("value", "1.25", "unit", "KILOGRAM"));
        assertThat(provider.packageDimensions(
                new BigDecimal("10.1"), new BigDecimal("20"), new BigDecimal("30.01")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "length", "11", "width", "20", "height", "31", "unit", "CENTIMETER"));
    }

    @Test
    void tiktokUsesCategoryVersionV2ForUsAndSeaRegions() {
        TikTokShopOfficialPublishingProvider provider = tiktokProvider();

        assertThat(List.of("US", "PH", "MY", "ID", "TH", "VN", "SG"))
                .allMatch(provider::requiresCategoryVersionV2);
        assertThat(provider.requiresCategoryVersionV2("GB")).isFalse();
    }

    @Test
    void tiktokBuildsCustomSalesAttributesAndAttributeImagesFromCapturedSkus() {
        TikTokShopOfficialPublishingProvider provider = tiktokProvider();
        ProductCapture capture = new ProductCapture(
                "org-1", "user-1", "capture-key", "fingerprint", "SHOPEE",
                "https://shopee.ph/item-i.1.2", "source-shop", "2", "Title", "Description",
                "Category", "Brand", "PHP", new BigDecimal("19.90"), new BigDecimal("21.90"),
                "{}", "{}");
        List<ProductCaptureSku> skus = List.of(
                sku("red-s", "RED-S", "Red / S", "19.90", "https://img.example/red.jpg",
                        "{\"Color\":\"Red\",\"Size\":\"S\"}", 0),
                sku("blue-m", "BLUE-M", "Blue / M", "21.90", "https://img.example/blue.jpg",
                        "{\"Color\":\"Blue\",\"Size\":\"M\"}", 1)
        );

        List<Map<String, Object>> result = provider.buildSkus(
                capture, skus, Map.of("skuStocks", Map.of("red-s", 8)),
                "warehouse-1", 4,
                Map.of("red-s", "uri-red", "blue-m", "uri-blue"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> redAttributes =
                (List<Map<String, Object>>) result.getFirst().get("sales_attributes");
        assertThat(redAttributes).hasSize(2);
        assertThat(redAttributes.getFirst()).containsEntry("name", "Color")
                .containsEntry("value_name", "Red")
                .containsEntry("sku_img", Map.of("uri", "uri-red"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inventory =
                (List<Map<String, Object>>) result.getFirst().get("inventory");
        assertThat(inventory.getFirst()).containsEntry("warehouse_id", "warehouse-1")
                .containsEntry("quantity", 8);
    }

    private ShopeeOfficialPublishingProvider shopeeProvider(
            ShopeeOfficialClient client, MarketplaceImageDownloader downloader) {
        return new ShopeeOfficialPublishingProvider(
                mock(ShopAuthorizationService.class), client,
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                downloader, new ObjectMapper(), mock(ShopeeProductVideoUploader.class));
    }

    private TikTokShopOfficialPublishingProvider tiktokProvider() {
        return new TikTokShopOfficialPublishingProvider(
                mock(ShopAuthorizationService.class), mock(TikTokShopOfficialClient.class),
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class), new ObjectMapper(), mock(TikTokProductVideoUploader.class), mock(TikTokProductDescription.class));
    }

    private MarketplaceAccess.Connection shopeeConnection() {
        Shop shop = new Shop("org-1", MarketplacePlatform.SHOPEE, "9988", "merchant-1",
                null, "SELLER", "Demo", "PH", "[]");
        MarketplaceAccess.TokenSet tokens = new MarketplaceAccess.TokenSet(
                "access", "refresh", Instant.now().plusSeconds(3600), null, List.of());
        return new MarketplaceAccess.Connection(shop, tokens);
    }

    private ProductCaptureSku sku(String key, String sellerSku, String variation,
                                  String price, String imageUrl, String attributes, int order) {
        return new ProductCaptureSku("org-1", "capture-1", key, null, sellerSku,
                variation, new BigDecimal(price), null, null, imageUrl,
                "MODEL", attributes, "{}", order);
    }

    private Map<String, Object> shopeeModel(int index) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("tier_index", List.of(index));
        model.put("original_price", new BigDecimal("19.90"));
        model.put("seller_stock", List.of(Map.of("stock", 3)));
        model.put("model_sku", "SKU-" + index);
        return model;
    }
}
