package com.yingdao.aicommerce.product.application.provider;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ShopeePublishPricingTest {
    @Test void originalPriceWinsBeforeExchangeConversionAndSourceIsPreserved() {
        var sku = new com.yingdao.aicommerce.product.domain.ProductCaptureSku("org", "capture", "sku", "1", "sku", "Black / S",
                new BigDecimal("0.01"), new BigDecimal("15.80"), 10, "image", "MODEL", "{}", "{}", 0);
        var request = Map.<String,Object>of("exchangeRateSnapshot", Map.of("targetCurrency", "PHP", "rate", "15.395109"));
        assertEquals(new BigDecimal("243.24"), ShopeePublishPricing.price(ShopeePublishPricing.sourcePrice(sku), request));
        assertEquals(new BigDecimal("0.01"), sku.getPrice());
        assertEquals(new BigDecimal("15.80"), sku.getOriginalPrice());
        assertEquals(new BigDecimal("19.90"), ShopeePublishPricing.price(ShopeePublishPricing.sourcePrice(sku), Map.of("publishPrice", "19.90")));
    }
    @Test void invalidConvertedPriceIsRejectedBeforeMedia() {
        var limits = Map.<String,Object>of("min_limit", 5, "max_limit", 200000);
        assertThrows(RuntimeException.class, () -> ShopeeProductTitle.validatePrices(java.util.List.of(new BigDecimal("0.15")), limits));
        assertDoesNotThrow(() -> ShopeeProductTitle.validatePrices(java.util.List.of(new BigDecimal("230.93"), new BigDecimal("274.03")), limits));
    }
    @Test void cannotReinterpretPhilippinePriceAsRinggit() {
        assertThrows(RuntimeException.class, () -> ShopeePublishPricing.validate("MY", "PHP", Map.of()));
        assertThrows(RuntimeException.class, () -> ShopeePublishPricing.validate("MY", "PHP", Map.of("publishCurrency", "MYR")));
        assertThrows(RuntimeException.class, () -> ShopeePublishPricing.validate("MY", "PHP", Map.of("publishPrice", "19.90", "publishCurrency", "PHP")));
    }
    @Test void explicitPublicationPricePreservesSourcePrice() {
        var source = new BigDecimal("155");
        var request = Map.<String,Object>of("publishCurrency", "MYR", "publishPrice", "19.90");
        ShopeePublishPricing.validate("MY", "PHP", request);
        assertEquals(new BigDecimal("19.90"), ShopeePublishPricing.price(source, request));
        assertEquals(new BigDecimal("155"), source);
        assertEquals(source, ShopeePublishPricing.price(source, Map.of()));
    }
    @Test void rejectsInvalidTargetPrice() {
        for (String price : new String[]{"0", "-1", "NaN", "19.999"}) {
            assertThrows(RuntimeException.class, () -> ShopeePublishPricing.validate("MY", "PHP", Map.of("publishCurrency", "MYR", "publishPrice", price)));
        }
    }
}
