package com.yingdao.aicommerce.shop.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCredentialCipherTest {
    private final ShopCredentialCipher cipher = new ShopCredentialCipher(
            "test-only-shop-credential-key-with-32-characters");

    @Test
    void encryptsWithRandomIvAndBindsCiphertextToTenantAndShop() {
        String first = cipher.encrypt("access-token", "org-a", "shop-a");
        String second = cipher.encrypt("access-token", "org-a", "shop-a");

        assertTrue(first.startsWith("v1:"));
        assertNotEquals(first, second);
        assertEquals("access-token", cipher.decrypt(first, "org-a", "shop-a"));
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(first, "org-b", "shop-a"));
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(first, "org-a", "shop-b"));
    }

    @Test
    void rejectsCredentialKeysShorterThanDocumentedMinimum() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ShopCredentialCipher("x".repeat(31)));

        assertTrue(failure.getMessage().contains("at least 32 characters"));
    }
}
