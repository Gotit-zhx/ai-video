package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeProductImageUrlTest {
    private final SafeProductImageUrl validator = new SafeProductImageUrl();

    @Test
    void allowsPublicHttpsAndRejectsLocalAddresses() {
        assertEquals("https://cf.shopee.ph/file/a.webp?size=large",
                validator.validate("https://cf.shopee.ph/file/a.webp?size=large"));
        assertThrows(BusinessException.class,
                () -> validator.validate("http://cf.shopee.ph/file/a.webp"));
        assertThrows(BusinessException.class,
                () -> validator.validate("https://127.0.0.1/a.webp"));
        assertThrows(BusinessException.class,
                () -> validator.validate("https://192.168.1.2/a.webp"));
        assertThrows(BusinessException.class,
                () -> validator.validate("https://localhost/a.webp"));
    }
}
