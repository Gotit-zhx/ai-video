package com.yingdao.aicommerce.shop.infrastructure;

import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void shopeeMerchantSignatureMatchesIndependentHmacVector() {
        ShopeeOfficialClient client = new ShopeeOfficialClient(
                http, true, "123", "secret", "https://open.shopee.com",
                "https://partner.shopeemobile.com", "SHOP");

        assertEquals("48c5841c801d90fe39f4d92a70afc47a413e02d91dab7d2b2aade752255555ea",
                client.signMerchant(ShopeeOfficialClient.MERCHANT_SHOP_LIST_PATH,
                        1_700_000_000L, "token", "9988"));
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
    void shopeeRefusesAmbiguousAuthorizationSubjectBeforeTokenExchange() {
        MarketplaceHttp transport = mock(MarketplaceHttp.class);
        ShopeeOfficialClient client = new ShopeeOfficialClient(
                transport, true, "123", "secret", "https://open.shopee.com",
                "https://partner.shopeemobile.com", "SHOP");

        assertThatThrownBy(() -> client.exchangeAuthorizationCode("code",
                Map.of("shop_id", "456", "main_account_id", "9988")))
                .hasMessageContaining("同时包含");
        verifyNoInteractions(transport);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shopeeMainAccountGrantRefreshesWithItsOriginalMerchantSubject() {
        MarketplaceHttp transport = mock(MarketplaceHttp.class);
        when(transport.json(any())).thenReturn("{}");
        when(transport.postJson(any(), any(), any())).thenReturn(Map.of(
                "access_token", "next-access", "refresh_token", "next-refresh",
                "expire_in", 3600));
        ShopeeOfficialClient client = new ShopeeOfficialClient(
                transport, true, "123", "secret", "https://open.shopee.com",
                "https://partner.shopeemobile.com", "SHOP");
        Shop shop = new Shop("org-1", MarketplacePlatform.SHOPEE, "456", "9988",
                null, "SELLER", "Shop", "PH", "[]", "user-1",
                ShopeeOfficialClient.AUTH_SUBJECT_MERCHANT, "9988");

        client.refresh(new MarketplaceAccess.TokenSet("access", "refresh",
                Instant.now().minusSeconds(1), null, List.of()), shop);

        ArgumentCaptor<Map> body = ArgumentCaptor.forClass(Map.class);
        verify(transport).json(body.capture());
        assertEquals(9988L, body.getValue().get("merchant_id"));
        assertFalse(body.getValue().containsKey("shop_id"));
    }

    @Test
    void shopeeMainAccountExchangeCarriesMerchantSubjectIntoEveryReturnedShop() {
        MarketplaceHttp transport = mock(MarketplaceHttp.class);
        when(transport.json(any())).thenReturn("{}");
        when(transport.postJson(any(), any(), any())).thenReturn(Map.of(
                "access_token", "access", "refresh_token", "refresh",
                "merchant_id_list", List.of(9988L)));
        when(transport.get(any(), any())).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (uri.getPath().equals(ShopeeOfficialClient.MERCHANT_SHOP_LIST_PATH)) {
                return Map.of("shop_list", List.of(
                        Map.of("shop_id", 456L,
                                "sip_affi_shops", List.of(Map.of("affi_shop_id", 654L))),
                        Map.of("shop_id", 789L)),
                        "more", false);
            }
            return Map.of("response", Map.of("shop_name", "Shop", "region", "PH",
                    "merchant_id", 9988L));
        });
        ShopeeOfficialClient client = new ShopeeOfficialClient(
                transport, true, "123", "secret", "https://open.shopee.com",
                "https://partner.shopeemobile.com", "SHOP");

        MarketplaceAccess.AuthorizationGrant grant = client.exchangeAuthorizationCode(
                "code", Map.of("main_account_id", "111"));

        assertEquals(List.of("456", "654", "789"), grant.shops().stream()
                .map(MarketplaceAccess.RemoteShop::externalShopId).toList());
        assertTrue(grant.shops().stream().allMatch(shop ->
                ShopeeOfficialClient.AUTH_SUBJECT_MERCHANT.equals(shop.authorizationSubjectType())
                        && "9988".equals(shop.authorizationSubjectId())
                        && "9988".equals(shop.merchantId())));
        ArgumentCaptor<URI> urls = ArgumentCaptor.forClass(URI.class);
        assertTrue(grant.shops().stream().anyMatch(shop ->
                "654".equals(shop.externalShopId())
                        && ShopeeOfficialClient.AUTH_SUBJECT_MERCHANT.equals(
                        shop.authorizationSubjectType())
                        && "9988".equals(shop.authorizationSubjectId())
                        && "9988".equals(shop.merchantId())));
        verify(transport, times(4)).get(urls.capture(), any());
        assertTrue(urls.getAllValues().stream().anyMatch(uri ->
                uri.getPath().equals(ShopeeOfficialClient.MERCHANT_SHOP_LIST_PATH)
                        && uri.getQuery().contains("merchant_id=9988")
                        && uri.getQuery().contains("page_size=500")
                        && uri.getQuery().contains("sign=")));
    }

    @Test
    void shopeeShopGrantKeepsEveryReturnedShopInOneRefreshTokenGroup() {
        MarketplaceHttp transport = mock(MarketplaceHttp.class);
        when(transport.json(any())).thenReturn("{}");
        when(transport.postJson(any(), any(), any())).thenReturn(Map.of(
                "access_token", "access", "refresh_token", "refresh",
                "expire_in", 2_000_000_000L,
                "shop_id_list", List.of(456L, 789L)));
        when(transport.get(any(), any())).thenReturn(Map.of(
                "response", Map.of("shop_name", "Shop", "region", "PH")));
        ShopeeOfficialClient client = new ShopeeOfficialClient(
                transport, true, "123", "secret", "https://open.shopee.com",
                "https://partner.shopeemobile.com", "SHOP");

        MarketplaceAccess.AuthorizationGrant grant = client.exchangeAuthorizationCode(
                "code", Map.of("shop_id", "456"));

        assertEquals(Instant.ofEpochSecond(2_000_000_000L),
                grant.tokens().accessTokenExpiresAt());
        assertEquals(List.of("456", "789"), grant.shops().stream()
                .map(MarketplaceAccess.RemoteShop::externalShopId).toList());
        assertTrue(grant.shops().stream().allMatch(shop ->
                ShopeeOfficialClient.AUTH_SUBJECT_SHOP.equals(shop.authorizationSubjectType())
                        && "456".equals(shop.authorizationSubjectId())));
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
