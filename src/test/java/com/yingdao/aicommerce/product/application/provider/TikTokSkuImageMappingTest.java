package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TikTokSkuImageMappingTest {
    private final TikTokShopOfficialPublishingProvider provider = new TikTokShopOfficialPublishingProvider(
            mock(ShopAuthorizationService.class), mock(TikTokShopOfficialClient.class),
            mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
            mock(MarketplaceImageDownloader.class), new ObjectMapper(),
            mock(TikTokProductVideoUploader.class), mock(TikTokProductDescription.class));

    @Test
    void all71SkusRetainTheirPriceStockAndColorImageWhenDatabaseReturnsSizeBeforeColor() {
        List<ProductCaptureSku> skus = new ArrayList<>();
        Map<String, String> images = new LinkedHashMap<>();
        for (int index = 0; index < 71; index++) {
            String color = "color-" + index / 4;
            String size = "size-" + index % 4;
            String key = "sku-" + index;
            skus.add(sku(key, "{\"size\":\"" + size + "\",\"color\":\"" + color + "\"}", index));
            images.put(key, "image-" + color);
        }
        List<Map<String, Object>> result = provider.buildSkus(capture(), skus, Map.of(), "warehouse", 999, images);
        assertThat(result).hasSize(71);
        for (int index = 0; index < result.size(); index++) {
            Map<String, Object> row = result.get(index);
            assertThat(row).containsEntry("seller_sku", "sku-" + index);
            assertThat(row.get("price")).isEqualTo(Map.of("amount", "8.80", "currency", "MYR"));
            assertThat(row.get("inventory")).isEqualTo(List.of(Map.of("warehouse_id", "warehouse", "quantity", 999)));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attributes = (List<Map<String, Object>>) row.get("sales_attributes");
            assertThat(attributes.getFirst()).containsEntry("name", "color")
                    .containsEntry("value_name", "color-" + index / 4)
                    .containsEntry("sku_img", Map.of("uri", "image-color-" + index / 4));
            assertThat(attributes.get(1)).containsEntry("name", "size")
                    .containsEntry("value_name", "size-" + index % 4);
        }
    }

    @Test
    void sourceKeyOrderDifferencesDoNotLoseMalayColorImages() {
        List<ProductCaptureSku> skus = List.of(
                sku("a", "{\"Saiz\":\"S\",\"Warna\":\"Red\"}", 0),
                sku("b", "{\"Warna\":\"Blue\",\"Saiz\":\"S\"}", 1));
        List<Map<String, Object>> result = provider.buildSkus(capture(), skus, Map.of(), "warehouse", 999,
                Map.of("a", "red-image", "b", "blue-image"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> second = (List<Map<String, Object>>) result.get(1).get("sales_attributes");
        assertThat(second.getFirst()).containsEntry("name", "Warna")
                .containsEntry("sku_img", Map.of("uri", "blue-image"));
    }

    @Test
    void missingDimensionsReportAProductProblemRatherThanAnImmutableListFailure() {
        assertThatThrownBy(() -> provider.buildSkus(capture(), List.of(
                sku("a", "{\"size\":\"S\",\"color\":\"Red\"}", 0),
                sku("b", "{}", 1)), Map.of(), "warehouse", 999, Map.of()))
                .hasMessageContaining("缺少可用于 TikTok Shop 的销售规格");
    }

    private ProductCapture capture() {
        ProductCapture capture = mock(ProductCapture.class);
        org.mockito.Mockito.when(capture.getCurrency()).thenReturn("MYR");
        return capture;
    }

    private ProductCaptureSku sku(String key, String attributes, int order) {
        return new ProductCaptureSku("org", "capture", key, null, key, key,
                new BigDecimal("8.80"), null, null, "https://images.example/" + key,
                "PLATFORM", attributes, "{}", order);
    }
}
