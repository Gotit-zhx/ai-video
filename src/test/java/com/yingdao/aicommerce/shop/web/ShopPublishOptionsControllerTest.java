package com.yingdao.aicommerce.shop.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopPublishOptionsControllerTest {
    private static final String CATEGORY = "/api/v2/product/get_category";
    private static final String LOGISTICS = "/api/v2/logistics/get_channel_list";
    ShopAuthorizationService shops; ShopeeOfficialClient shopee; PlatformPrincipal user; MarketplaceAccess.Connection connection;

    @BeforeEach void setup() {
        shops = mock(ShopAuthorizationService.class); shopee = mock(ShopeeOfficialClient.class);
        user = mock(PlatformPrincipal.class); connection = mock(MarketplaceAccess.Connection.class);
        when(user.organizationId()).thenReturn("org-unit");
        when(shops.connectionForPublishing("org-unit", "shop-unit", MarketplacePlatform.SHOPEE)).thenReturn(connection);
    }

    @Test void tikTokUsesOwnedDestinationAndReadsCategoriesAndWarehouses() {
        var tiktok = mock(com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient.class);
        var shop = mock(com.yingdao.aicommerce.shop.domain.Shop.class);
        var tokens = mock(MarketplaceAccess.TokenSet.class);
        when(shops.connectionForPublishing("org-unit", "shop-unit")).thenReturn(connection);
        when(connection.shop()).thenReturn(shop);
        when(connection.tokens()).thenReturn(tokens);
        when(shop.getPlatform()).thenReturn("TIKTOK_SHOP");
        when(shop.getSellerType()).thenReturn("LOCAL");
        when(shop.getRegion()).thenReturn("MY");
        when(shop.getShopCipher()).thenReturn("cipher");
        when(tokens.accessToken()).thenReturn("test-token");
        when(tiktok.apiGet(anyString(), eq("test-token"), eq("cipher"), anyMap()))
                .thenAnswer(call -> Map.of("data", Map.of("api", call.getArgument(0))));
        try (var controller = new ShopPublishOptionsController(shops, shopee, tiktok)) {
            var result = controller.options("shop-unit", user).data();
            assertEquals(Map.of("api", "/product/202309/categories"), result.get("categories"));
            assertEquals(Map.of("api", "/logistics/202309/warehouses"), result.get("warehouses"));
            assertEquals("LOCAL", result.get("listingMethod"));
            assertEquals(true, result.get("canCreateDraft"));
            verify(tiktok, never()).apiGet(eq("/product/202507/global_listing_rules"), anyString(), anyString(), anyMap());
            verify(tiktok).apiGet("/product/202309/categories", "test-token", "cipher",
                    Map.of("locale", "en-US", "category_version", "v2"));
            verifyNoInteractions(shopee);
        }
    }

    @Test void crossBorderOptionsConfirmReplicationBeforeReadingLocalCategoriesAndWarehouses() {
        for (Object methods : java.util.List.of("LOCAL_REPLICATION", java.util.List.of("LOCAL_REPLICATION"))) {
            var tiktok = crossBorderClient();
            var inventoryRules = java.util.List.of(Map.of("local_warehouse_id", "warehouse-target", "allocation_mode", "MANUAL"));
            when(tiktok.apiGet("/product/202507/global_listing_rules", "target-token", "target-cipher", Map.of()))
                    .thenReturn(Map.of("data", Map.of("listing_methods", methods, "inventory_rules", inventoryRules), "request_id", "rules-1"));
            when(tiktok.apiGet(eq("/product/202309/categories"), anyString(), anyString(), anyMap()))
                    .thenReturn(Map.of("data", Map.of("categories", java.util.List.of())));
            when(tiktok.apiGet(eq("/logistics/202309/warehouses"), anyString(), anyString(), anyMap()))
                    .thenReturn(Map.of("data", Map.of("warehouses", java.util.List.of())));
            try (var controller = new ShopPublishOptionsController(shops, shopee, tiktok)) {
                var result = controller.options("shop-unit", user).data();
                assertEquals("LOCAL_REPLICATION", result.get("listingMethod"));
                assertEquals(true, result.get("canCreateDraft"));
                assertEquals("rules-1", result.get("listingRulesRequestId"));
                assertEquals(inventoryRules, result.get("inventoryRules"));
                assertTrue(result.containsKey("categories"));
                assertTrue(result.containsKey("warehouses"));
                var ordered = inOrder(tiktok);
                ordered.verify(tiktok).apiGet("/product/202507/global_listing_rules", "target-token", "target-cipher", Map.of());
                verify(tiktok).apiGet("/product/202309/categories", "target-token", "target-cipher",
                        Map.of("locale", "en-US", "category_version", "v2"));
                verify(tiktok).apiGet("/logistics/202309/warehouses", "target-token", "target-cipher", Map.of());
                verifyNoInteractions(shopee);
            }
        }
    }

    @Test void unconfirmedOrFailedCrossBorderCapabilityNeverStartsLocalOptionReads() {
        for (boolean lookupFails : java.util.List.of(false, true)) {
            var tiktok = crossBorderClient();
            var stub = when(tiktok.apiGet("/product/202507/global_listing_rules", "target-token", "target-cipher", Map.of()));
            if (lookupFails) stub.thenThrow(new MarketplaceApiException("network error"));
            else stub.thenReturn(Map.of("data", Map.of("listing_methods", "GLOBAL_PUBLISHING")));
            try (var controller = new ShopPublishOptionsController(shops, shopee, tiktok)) {
                var failure = assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                        () -> controller.options("shop-unit", user));
                assertEquals(lookupFails ? org.springframework.http.HttpStatus.BAD_GATEWAY : org.springframework.http.HttpStatus.CONFLICT,
                        failure.getStatus());
                assertTrue(failure.getMessage().contains("跨境刊登模式未就绪"));
                verify(tiktok).apiGet("/product/202507/global_listing_rules", "target-token", "target-cipher", Map.of());
                verifyNoMoreInteractions(tiktok);
                verifyNoInteractions(shopee);
            }
        }
    }

    @Test void crossBorderCapabilityDeadlineCancelsTheReadAndNeverStartsItsDependentOptions() throws Exception {
        var tiktok = crossBorderClient();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(tiktok.apiGet(anyString(), anyString(), anyString(), anyMap())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); throw new AssertionError("must be interrupted"); }
            catch (InterruptedException cancelled) { interrupted.countDown(); Thread.currentThread().interrupt(); throw new MarketplaceApiException("cancelled", cancelled); }
        });
        try (var controller = new ShopPublishOptionsController(shops, shopee, tiktok, Duration.ofMillis(300))) {
            var failure = assertThrows(MarketplaceApiException.class, () -> controller.options("shop-unit", user));
            assertTrue(failure.getMessage().contains("跨境刊登模式未就绪"));
            assertInstanceOf(HttpTimeoutException.class, failure.getCause());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            verify(tiktok).apiGet("/product/202507/global_listing_rules", "target-token", "target-cipher", Map.of());
            verifyNoMoreInteractions(tiktok);
        }
    }

    private com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient crossBorderClient() {
        var tiktok = mock(com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient.class);
        var shop = mock(com.yingdao.aicommerce.shop.domain.Shop.class);
        var tokens = mock(MarketplaceAccess.TokenSet.class);
        when(shops.connectionForPublishing("org-unit", "shop-unit")).thenReturn(connection);
        when(connection.shop()).thenReturn(shop);
        when(connection.tokens()).thenReturn(tokens);
        when(shop.getPlatform()).thenReturn("TIKTOK_SHOP");
        when(shop.getSellerType()).thenReturn("CROSS_BORDER");
        when(shop.getRegion()).thenReturn("MY");
        when(shop.getShopCipher()).thenReturn("target-cipher");
        when(tokens.accessToken()).thenReturn("target-token");
        return tiktok;
    }

    @Test void authorizedCategoryAndLogisticsReadsRunConcurrentlyAndKeepResponseShape() {
        var entered = new CountDownLatch(2);
        when(shopee.shopGet(anyString(), eq(connection), anyMap())).thenAnswer(call -> {
            entered.countDown(); assertTrue(entered.await(2, TimeUnit.SECONDS), "Sequential reads would block before the second request starts");
            return Map.of("response", Map.of("api", call.getArgument(0)));
        });
        try (var controller = new ShopPublishOptionsController(shops, shopee)) {
            var result = controller.options("shop-unit", user);
            assertTrue(result.success());
            assertEquals(Map.of("categories", Map.of("api", CATEGORY), "logistics", Map.of("api", LOGISTICS)), result.data());
            verify(shopee).shopGet(CATEGORY, connection, Map.of("language", "en"));
            verify(shopee).shopGet(LOGISTICS, connection, Map.of());
            verify(shopee, never()).shopPost(anyString(), any(), anyMap());
        }
    }

    @Test void accessFailureStartsNeitherExternalRead() {
        var failure = new MarketplaceApiException("shop access denied");
        when(shops.connectionForPublishing(anyString(), anyString(), any())).thenThrow(failure);
        try (var controller = new ShopPublishOptionsController(shops, shopee)) {
            assertSame(failure, assertThrows(MarketplaceApiException.class, () -> controller.options("shop-unit", user)));
            verifyNoInteractions(shopee);
        }
    }

    @Test void firstFailedReadImmediatelyInterruptsItsOutstandingPeer() throws Exception {
        var logisticsStarted = new CountDownLatch(1); var peerInterrupted = new CountDownLatch(1);
        var failure = new MarketplaceApiException("category unavailable");
        when(shopee.shopGet(eq(CATEGORY), eq(connection), anyMap())).thenAnswer(call -> {
            assertTrue(logisticsStarted.await(2, TimeUnit.SECONDS)); throw failure;
        });
        when(shopee.shopGet(eq(LOGISTICS), eq(connection), anyMap())).thenAnswer(call -> {
            logisticsStarted.countDown();
            try { new CountDownLatch(1).await(); throw new AssertionError("must be interrupted"); }
            catch (InterruptedException cancelled) { peerInterrupted.countDown(); Thread.currentThread().interrupt(); throw new MarketplaceApiException("cancelled", cancelled); }
        });
        try (var controller = new ShopPublishOptionsController(shops, shopee)) {
            assertSame(failure, assertThrows(MarketplaceApiException.class, () -> controller.options("shop-unit", user)));
            assertTrue(peerInterrupted.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void overallDeadlineCancelsBothReadsWithoutAnyReplay() throws Exception {
        var entered = new CountDownLatch(2); var interrupted = new CountDownLatch(2);
        when(shopee.shopGet(anyString(), eq(connection), anyMap())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); throw new AssertionError("must be interrupted"); }
            catch (InterruptedException cancelled) { interrupted.countDown(); Thread.currentThread().interrupt(); throw new MarketplaceApiException("cancelled", cancelled); }
        });
        try (var controller = new ShopPublishOptionsController(shops, shopee, Duration.ofMillis(300))) {
            var failure = assertThrows(MarketplaceApiException.class, () -> controller.options("shop-unit", user));
            assertInstanceOf(HttpTimeoutException.class, failure.getCause());
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            verify(shopee, times(2)).shopGet(anyString(), eq(connection), anyMap());
        }
    }
}
