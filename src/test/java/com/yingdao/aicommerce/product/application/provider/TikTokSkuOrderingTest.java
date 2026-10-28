package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TikTokSkuOrderingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final TikTokShopOfficialPublishingProvider provider = new TikTokShopOfficialPublishingProvider(
            null, null, null, null, null, json, null, null);

    @Test void shuffledSevenColorSixSizeCaptureKeepsEverySkuPriceStockAndImageTogether() {
        var sizes = List.of("S", "M", "L", "XL", "XXL", "XXXL");
        var source = new ArrayList<ProductCaptureSku>();
        var images = new LinkedHashMap<String, String>();
        var stocks = new LinkedHashMap<String, Integer>();
        for (String color : List.of("Gray", "Beige", "Pink", "Brown", "Red", "Black", "White")) {
            for (String size : sizes) {
                String key = color + "-" + size;
                int index = source.size();
                source.add(sku(key, "Size", size, color, index));
                images.put(key, "image-" + color);
                stocks.put(key, index + 1);
            }
        }
        Collections.shuffle(source, new Random(20261028));
        var originalKeys = source.stream().map(ProductCaptureSku::getSkuKey).toList();
        var result = build(source, Map.of("skuStocks", stocks), images);
        var colorOrder = source.stream().map(s -> s.getSkuKey().split("-")[0]).distinct().toList();
        assertThat(result).extracting(row -> row.get("seller_sku"))
                .containsExactlyElementsOf(colorOrder.stream().flatMap(c -> sizes.stream().map(s -> c + "-" + s)).toList());
        for (var row : result) {
            String key = row.get("seller_sku").toString();
            var sku = source.stream().filter(s -> s.getSkuKey().equals(key)).findFirst().orElseThrow();
            assertThat(row.get("price")).isEqualTo(Map.of("amount", sku.getPrice().toPlainString(), "currency", "MYR"));
            assertThat(row.get("inventory")).isEqualTo(List.of(Map.of("warehouse_id", "warehouse", "quantity", stocks.get(key))));
            assertThat(attributes(row).getFirst()).containsEntry("name", "Color")
                    .containsEntry("sku_img", Map.of("uri", images.get(key)));
        }
        assertThat(source).extracting(ProductCaptureSku::getSkuKey).containsExactlyElementsOf(originalKeys);
    }

    @Test void sizesWithAliasesAndMeasurementNotesSortWithoutRewritingLabels() {
        var labels = List.of("10XL", "M(40-55KG)", "XS", "4XL", "XXS", "S（35-40KG）", "2XL", "L", "XL", "3XL");
        assertThat(sortedSizes(labels, "Saiz")).containsExactly(
                "XXS", "XS", "S（35-40KG）", "M(40-55KG)", "L", "XL", "2XL", "3XL", "4XL", "10XL");
    }

    @Test void numericSizesUseNumericOrderRatherThanTextOrder() {
        assertThat(sortedSizes(List.of("12", "8", "10", "8.5"), "尺码")).containsExactly("8", "8.5", "10", "12");
        assertThat(sortedSizes(List.of("120cm", "90cm", "100cm"), "Size")).containsExactly("90cm", "100cm", "120cm");
    }

    @Test void confirmedDestinationSizeMappingControlsSortingInsteadOfSourceSize() {
        var source = List.of(sku("a", "Size", "S", "Red", 0), sku("b", "Size", "XL", "Red", 1));
        var mapping = Map.of(
                "a", List.of(Map.of("name", "Color", "value_name", "Red"), Map.of("name", "Size", "value_name", "XL")),
                "b", List.of(Map.of("name", "Color", "value_name", "Red"), Map.of("name", "Size", "value_name", "S")));
        assertThat(build(source, Map.of("salesAttributesBySku", mapping), Map.of()))
                .extracting(row -> row.get("seller_sku")).containsExactly("b", "a");
    }

    @Test void unrecognizedSizesAndOtherNumericDimensionsKeepTheirSourceOrder() {
        assertThat(sortedSizes(List.of("custom-B", "custom-A", "custom-C"), "Size"))
                .containsExactly("custom-B", "custom-A", "custom-C");
        assertThat(sortedSizes(List.of("12", "8", "10"), "Style")).containsExactly("12", "8", "10");
    }

    @Test void missingCombinationsAreNotInventedAndEquivalentLabelsStayDistinct() {
        var source = List.of(sku("red-xxl", "Size", "XXL", "Red", 0),
                sku("blue-m", "Size", "M", "Blue", 1),
                sku("red-2xl", "Size", "2XL", "Red", 2),
                sku("red-s", "Size", "S", "Red", 3));
        assertThat(build(source, Map.of(), Map.of())).extracting(row -> row.get("seller_sku"))
                .containsExactly("red-s", "red-xxl", "red-2xl", "blue-m");
    }

    private List<String> sortedSizes(List<String> labels, String dimension) {
        var source = new ArrayList<ProductCaptureSku>();
        for (String label : labels) source.add(sku("sku-" + source.size(), dimension, label, "Red", source.size()));
        return build(source, Map.of(), Map.of()).stream()
                .map(row -> attributes(row).stream().filter(a -> dimension.equals(a.get("name")))
                        .findFirst().orElseThrow().get("value_name").toString()).toList();
    }

    private List<Map<String, Object>> build(List<ProductCaptureSku> source, Map<String, Object> request, Map<String, String> images) {
        var capture = mock(ProductCapture.class);
        when(capture.getCurrency()).thenReturn("MYR");
        return provider.buildSkus(capture, source, request, "warehouse", 999, images);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attributes(Map<String, Object> row) {
        return (List<Map<String, Object>>) row.get("sales_attributes");
    }

    private ProductCaptureSku sku(String key, String dimension, String size, String color, int index) {
        var attributes = new LinkedHashMap<String, String>();
        attributes.put(dimension, size);
        attributes.put("Color", color);
        return new ProductCaptureSku("org", "capture", key, null, key, key,
                new BigDecimal("8.80").add(BigDecimal.valueOf(index)), null, index + 1,
                "https://images.example/" + color, "PLATFORM", json.writeValueAsString(attributes), "{}", index);
    }
}
