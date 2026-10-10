package com.yingdao.aicommerce.shop.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TikTokShopListingCapabilityResolverTest {
    private static final String RULES = "/product/202507/global_listing_rules";

    @Test
    void localShopDoesNotAcquireAnAdditionalNetworkDependency() {
        var client = mock(TikTokShopOfficialClient.class);
        var capability = new TikTokShopListingCapabilityResolver(client).resolve(connection("LOCAL", "local"));
        assertThat(capability.listingMethod()).isEqualTo("LOCAL");
        assertThat(capability.requestId()).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void acceptsOnlyTheExactReplicationMethodAsAStringOrStringArray() {
        for (Object methods : List.of("LOCAL_REPLICATION", List.of("LOCAL_REPLICATION"),
                List.of("GLOBAL_PUBLISHING", "LOCAL_REPLICATION"))) {
            var client = mock(TikTokShopOfficialClient.class);
            when(client.apiGet(RULES, "token-target", "cipher-target", Map.of()))
                    .thenReturn(Map.of("data", Map.of("listing_methods", methods, "inventory_rules", inventoryRules("MANUAL")), "request_id", "rules-request"));
            var capability = new TikTokShopListingCapabilityResolver(client).resolve(connection("CROSS_BORDER", "target"));
            assertThat(capability.listingMethod()).isEqualTo("LOCAL_REPLICATION");
            assertThat(capability.requestId()).isEqualTo("rules-request");
            assertThat(capability.requireWarehouse("target-warehouse")).containsEntry("allocation_mode", "MANUAL");
            verify(client).apiGet(RULES, "token-target", "cipher-target", Map.of());
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    void missingMalformedOrLegacyOnlyMethodsNeverEnableLocalCreation() {
        for (Object methods : Arrays.asList(null, "", "GLOBAL_PUBLISHING", "local_replication",
                "LOCAL_REPLICATION,GLOBAL_PUBLISHING", "NOT_LOCAL_REPLICATION", "LOCAL_REPLICATION ",
                List.of(), List.of("GLOBAL_PUBLISHING"), List.of("LOCAL_REPLICATION", 1),
                Map.of("LOCAL_REPLICATION", true), true)) {
            var client = mock(TikTokShopOfficialClient.class);
            Map<String, Object> data = methods == null ? Map.of() : Map.of("listing_methods", methods);
            when(client.apiGet(anyString(), anyString(), anyString(), anyMap())).thenReturn(Map.of("data", data));
            assertThatThrownBy(() -> new TikTokShopListingCapabilityResolver(client).resolve(connection("CROSS_BORDER", "target")))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                    .hasMessageContaining("跨境刊登模式未就绪");
        }
    }

    @Test
    void missingEnvelopeAndUnknownSellerTypeRemainBlocked() {
        var client = mock(TikTokShopOfficialClient.class);
        assertThatThrownBy(() -> new TikTokShopListingCapabilityResolver(client).resolve(connection(null, "target")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("seller_type");
        verifyNoInteractions(client);
        for (Map<String, Object> response : Arrays.<Map<String, Object>>asList(null, Map.of(), Map.of("data", "invalid"))) {
            when(client.apiGet(anyString(), anyString(), anyString(), anyMap())).thenReturn(response);
            assertThatThrownBy(() -> new TikTokShopListingCapabilityResolver(client).resolve(connection("CROSS_BORDER", "target")))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("listing_methods");
        }
    }

    @Test
    void lookupFailurePreservesItsCauseWithoutExposingProviderDataOrAllowingCreation() {
        var client = mock(TikTokShopOfficialClient.class);
        var cause = new MarketplaceApiException("upstream private response");
        when(client.apiGet(anyString(), anyString(), anyString(), anyMap())).thenThrow(cause);
        assertThatThrownBy(() -> new TikTokShopListingCapabilityResolver(client).resolve(connection("CROSS_BORDER", "target")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasCause(cause).hasMessageContaining("跨境刊登模式未就绪")
                .hasMessageNotContaining("private response");
    }

    @Test
    void destinationsAndRepeatedLookupsNeverReuseAnotherCapability() {
        var client = mock(TikTokShopOfficialClient.class);
        when(client.apiGet(RULES, "token-first", "cipher-first", Map.of()))
                .thenReturn(Map.of("data", Map.of("listing_methods", "LOCAL_REPLICATION", "inventory_rules", inventoryRules("MANUAL"))))
                .thenReturn(Map.of("data", Map.of("listing_methods", "GLOBAL_PUBLISHING")));
        when(client.apiGet(RULES, "token-second", "cipher-second", Map.of()))
                .thenReturn(Map.of("data", Map.of("listing_methods", List.of("GLOBAL_PUBLISHING"))));
        var resolver = new TikTokShopListingCapabilityResolver(client);
        assertThat(resolver.resolve(connection("CROSS_BORDER", "first")).listingMethod()).isEqualTo("LOCAL_REPLICATION");
        assertThatThrownBy(() -> resolver.resolve(connection("CROSS_BORDER", "second"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> resolver.resolve(connection("CROSS_BORDER", "first"))).isInstanceOf(BusinessException.class);
        verify(client, times(2)).apiGet(RULES, "token-first", "cipher-first", Map.of());
        verify(client).apiGet(RULES, "token-second", "cipher-second", Map.of());
    }

    @Test
    void crossBorderShopWithoutItsCipherNeverCallsTheCapabilityApi() {
        var client = mock(TikTokShopOfficialClient.class);
        var shop = new Shop("org", MarketplacePlatform.TIKTOK_SHOP, "target", "seller", null,
                "CROSS_BORDER", "Target", "MY", "[]");
        assertThatThrownBy(() -> new TikTokShopListingCapabilityResolver(client).resolve(
                new MarketplaceAccess.Connection(shop, connection("CROSS_BORDER", "target").tokens())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("shop_cipher");
        verifyNoInteractions(client);
    }

    @Test
    void keepsEachInventoryModeAndAssociatedWarehousesWithoutSelectingAnotherMarketsWarehouse() {
        for (String mode : List.of("MANUAL", "SHARED", "DYNAMIC")) {
            var client = mock(TikTokShopOfficialClient.class);
            var rules = List.of(Map.of("local_warehouse_id", "target-warehouse", "allocation_mode", mode,
                    "associated_warehouses", List.of(Map.of("warehouse_id", "other-market", "region", "DE"))));
            when(client.apiGet(anyString(), anyString(), anyString(), anyMap()))
                    .thenReturn(Map.of("data", Map.of("listing_methods", "LOCAL_REPLICATION", "inventory_rules", rules)));
            var capability = new TikTokShopListingCapabilityResolver(client).resolve(connection("CROSS_BORDER", "target"));
            assertThat(capability.inventoryRules()).isEqualTo(rules);
            assertThat(capability.requireWarehouse("target-warehouse")).containsEntry("allocation_mode", mode);
            assertThatThrownBy(() -> capability.requireWarehouse("other-market"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("所选发货仓库");
        }
    }

    @Test
    void absentMalformedUnknownOrDuplicateInventoryRulesDoNotConfirmCrossBorderDraftCreation() {
        for (Object rules : Arrays.asList(null, List.of(), Map.of(), List.of("warehouse"),
                List.of(Map.of("local_warehouse_id", "target-warehouse", "allocation_mode", "UNKNOWN")),
                List.of(Map.of("allocation_mode", "MANUAL")),
                List.of(Map.of("local_warehouse_id", "target-warehouse", "allocation_mode", "MANUAL"),
                        Map.of("local_warehouse_id", "target-warehouse", "allocation_mode", "SHARED")))) {
            var client = mock(TikTokShopOfficialClient.class);
            var data = new java.util.LinkedHashMap<String, Object>();
            data.put("listing_methods", "LOCAL_REPLICATION");
            data.put("inventory_rules", rules);
            when(client.apiGet(anyString(), anyString(), anyString(), anyMap())).thenReturn(Map.of("data", data));
            assertThatThrownBy(() -> new TikTokShopListingCapabilityResolver(client).resolve(connection("CROSS_BORDER", "target")))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("inventory_rules");
        }
    }

    private List<Map<String, Object>> inventoryRules(String mode) {
        return List.of(Map.of("local_warehouse_id", "target-warehouse", "allocation_mode", mode));
    }

    private MarketplaceAccess.Connection connection(String sellerType, String name) {
        return new MarketplaceAccess.Connection(new Shop("org", MarketplacePlatform.TIKTOK_SHOP, name,
                "seller", "cipher-" + name, sellerType, name, "MY", "[]"),
                new MarketplaceAccess.TokenSet("token-" + name, "refresh", Instant.now().plusSeconds(3600), null, List.of()));
    }
}
