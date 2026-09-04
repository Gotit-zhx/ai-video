package com.yingdao.aicommerce.shop.infrastructure;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficialMarketplaceSignatureTest {
    private final MarketplaceHttp http = new MarketplaceHttp(new ObjectMapper());

    @Test
    void shopeeShopSignatureMatchesIndependentHmacVector() {
        ShopeeOfficialClient client = new ShopeeOfficialClient(
                http, true, "123", "secret", "https://open.shopee.com",
                "https://partner.shopeemobile.com", "SHOP");

        assertEquals("b55dcf787c9d7ce1abe238e22e8c8a2e40a0268040b0fdff4f08304111d18426",
                client.signShop("/api/v2/product/add_item", 1_700_000_000L, "token", "456"));
    }

    @Test
    void shopeeAuthorizationUsesLatestUnsignedSellerFlowAndTopLevelState() {
        ShopeeOfficialClient client = new ShopeeOfficialClient(
                http, true, "123", "secret", "https://open.shopee.com",
                "https://partner.shopeemobile.com", "SHOP");

        String url = client.authorizationUrl("https://app.example.com/api/v1/shop-authorizations/callback/shopee",
                "state-value");

        assertTrue(url.startsWith("https://open.shopee.com/auth?"));
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapp.example.com%2Fapi%2Fv1%2Fshop-authorizations%2Fcallback%2Fshopee"));
        assertTrue(url.contains("auth_type=seller"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("state=state-value"));
        assertFalse(url.contains("timestamp="));
        assertFalse(url.contains("sign="));
    }

    @Test
    void tiktokShopSignatureMatchesIndependentHmacVectorAndIgnoresSign() {
        TikTokShopOfficialClient client = new TikTokShopOfficialClient(
                http, true, "service-1", "test-app", "secret",
                "https://services.tiktokshop.com", "https://auth.tiktok-shops.com",
                "https://open-api.tiktokglobalshop.com");
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("timestamp", 1_700_000_000L);
        query.put("shop_cipher", "cipher-1");
        query.put("app_key", "test-app");
        query.put("sign", "must-not-be-signed");

        assertEquals("cffa3383b430302a899fbf85cfd27571984f13c5723b772bcf2f666a42626c77",
                client.sign("/product/202309/products", query, "{\"title\":\"A\"}", false));
    }

    @Test
    void tiktokImageUploadMustNotIncludeShopCipher() {
        TikTokShopOfficialClient client = new TikTokShopOfficialClient(
                http, true, "service-1", "test-app", "secret",
                "https://services.tiktokshop.com", "https://auth.tiktok-shops.com",
                "https://open-api.tiktokglobalshop.com");

        assertFalse(client.requiresShopCipher("/product/202309/images/upload"));
        assertTrue(client.requiresShopCipher("/product/202309/products"));
    }

    @Test
    void tiktokAuthorizationUsesSellerServiceEndpoint() {
        TikTokShopOfficialClient client = new TikTokShopOfficialClient(
                http, true, "service-1", "test-app", "secret",
                "https://services.tiktokshop.com", "https://auth.tiktok-shops.com",
                "https://open-api.tiktokglobalshop.com");

        assertEquals("https://services.tiktokshop.com/open/authorize?service_id=service-1&state=state-value",
                client.authorizationUrl("https://app.example/callback", "state-value"));
    }

    @Test
    void shopeeRedactsSecretsEchoedInsideSuccessfulHttpErrorEnvelope() {
        MarketplaceHttp transport = mock(MarketplaceHttp.class);
        when(transport.safeBody(any())).thenCallRealMethod();
        when(transport.json(any())).thenReturn("{}");
        when(transport.postJson(any(), any(), any())).thenReturn(Map.of(
                "error", "invalid_request",
                "message", "access_token=shopee-secret partner_key=partner-secret"));
        ShopeeOfficialClient client = new ShopeeOfficialClient(
                transport, true, "123", "secret", "https://open.shopee.com",
                "https://partner.shopeemobile.com", "SHOP");

        assertThatThrownBy(() -> client.exchangeAuthorizationCode("code", Map.of("shop_id", "456")))
                .hasMessageNotContaining("shopee-secret")
                .hasMessageNotContaining("partner-secret")
                .hasMessageContaining("access_token=REDACTED")
                .hasMessageContaining("partner_key=REDACTED");
    }

    @Test
    void tiktokRedactsSecretsEchoedInsideSuccessfulHttpErrorEnvelope() {
        MarketplaceHttp transport = mock(MarketplaceHttp.class);
        when(transport.safeBody(any())).thenCallRealMethod();
        when(transport.get(any(), any())).thenReturn(Map.of(
                "code", 10001,
                "message", "refresh_token=tiktok-secret app_secret=app-secret"));
        TikTokShopOfficialClient client = new TikTokShopOfficialClient(
                transport, true, "service-1", "test-app", "secret",
                "https://services.tiktokshop.com", "https://auth.tiktok-shops.com",
                "https://open-api.tiktokglobalshop.com");

        assertThatThrownBy(() -> client.exchangeAuthorizationCode("code", Map.of()))
                .hasMessageNotContaining("tiktok-secret")
                .hasMessageNotContaining("app-secret")
                .hasMessageContaining("refresh_token=REDACTED")
                .hasMessageContaining("app_secret=REDACTED");
    }
}
