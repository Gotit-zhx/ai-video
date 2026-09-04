package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductSourceUrlParserTest {
    private final ProductSourceUrlParser parser = new ProductSourceUrlParser();

    @Test
    void parsesShopeeProductAndRemovesTrackingQuery() {
        var result = parser.parse(
                "https://shopee.ph/sample-product-i.123.456?utm_source=ads&display_model_id=789",
                "SHOPEE", "123", "456"
        );

        assertEquals("SHOPEE", result.platform());
        assertEquals("123", result.shopId());
        assertEquals("456", result.productId());
        assertEquals("https://shopee.ph/sample-product-i.123.456?display_model_id=789",
                result.canonicalUrl());
        assertFalse(result.canonicalUrl().contains("utm_source"));
    }

    @Test
    void parsesTikTokProductAndKeepsOnlyAllowlistedQuery() {
        var result = parser.parse(
                "https://shop.tiktok.com/view/product/1729382256912345678?region=PH&trackParams=secret&locale=en-PH",
                "TIKTOK_SHOP", null, null
        );

        assertEquals("TIKTOK", result.platform());
        assertEquals("1729382256912345678", result.productId());
        assertEquals("https://shop.tiktok.com/view/product/1729382256912345678?region=PH&locale=en-PH",
                result.canonicalUrl());
    }

    @Test
    void rejectsUntrustedOrInconsistentSources() {
        assertThrows(BusinessException.class, () -> parser.parse(
                "https://shopee.ph.evil.example/item-i.1.2", "SHOPEE", null, null));
        assertThrows(BusinessException.class, () -> parser.parse(
                "http://shopee.ph/item-i.1.2", "SHOPEE", null, null));
        assertThrows(BusinessException.class, () -> parser.parse(
                "https://shopee.ph/item-i.1.2", "TIKTOK", null, null));
        assertThrows(BusinessException.class, () -> parser.parse(
                "https://shopee.ph/item-i.1.2", "SHOPEE", "1", "999"));
        assertThrows(BusinessException.class, () -> parser.parse(
                "https://shopee.ph/not-a-product", "SHOPEE", null, null));
    }
}
