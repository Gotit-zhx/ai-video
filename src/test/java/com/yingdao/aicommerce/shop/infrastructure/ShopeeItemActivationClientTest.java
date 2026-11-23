package com.yingdao.aicommerce.shop.infrastructure;

import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopeeItemActivationClientTest {
    @Test
    void preservesTheSpecificItemFailureWhenTheTopLevelMessageIsGeneric() {
        var http = mock(MarketplaceHttp.class);
        when(http.json(anyMap())).thenReturn("{}");
        when(http.safeBody(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(http.postJson(any(), anyMap(), anyString())).thenReturn(Map.of(
                "error", "product.error", "message", "Unlist item all failed.",
                "response", Map.of("failure_list", List.of(Map.of(
                        "item_id", 12L, "failed_reason", "Item under promotion")))));
        var client = new ShopeeOfficialClient(http, true, "123", "unit-key",
                "https://open.shopee.com", "https://partner.shopeemobile.com", "SHOP");
        var tokens = new MarketplaceAccess.TokenSet("fixture-access", "fixture-refresh",
                Instant.now().plusSeconds(3600), null, List.of());
        var shop = new Shop("org", MarketplacePlatform.SHOPEE, "9988", "merchant",
                null, "SELLER", "Fixture", "PH", "[]");
        var connection = new MarketplaceAccess.Connection(shop, tokens);

        assertThatThrownBy(() -> client.shopPost("/api/v2/product/unlist_item", connection,
                Map.of("item_list", List.of(Map.of("item_id", 12L, "unlist", false)))))
                .isInstanceOf(ShopeeOfficialClient.ApiFailure.class)
                .hasMessageContaining("Item under promotion");
        verify(http).postJson(any(), anyMap(), anyString());
    }
}
