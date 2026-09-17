package com.yingdao.aicommerce.product.application.provider;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ShopeePublishPricingTest {
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
