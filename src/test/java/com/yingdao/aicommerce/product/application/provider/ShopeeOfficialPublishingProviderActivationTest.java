package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopeeOfficialPublishingProviderActivationTest {
    private static final String GET_ITEM_BASE_INFO = "/api/v2/product/get_item_base_info";
    private static final long ITEM_ID = 2300069665L;

    @Test
    void activationRequiresTargetInSuccessListWithUnlistFalse() {
        ShopeeOfficialPublishingProvider provider = provider(mock(ShopeeOfficialClient.class));
        Map<String, Object> confirmed = Map.of("response", Map.of(
                "failure_list", List.of(),
                "success_list", List.of(Map.of("item_id", ITEM_ID, "unlist", false))));

        assertThatCode(() -> provider.verifyActivationResponse(confirmed, ITEM_ID))
                .doesNotThrowAnyException();

        Map<String, Object> missingTarget = Map.of("response", Map.of(
                "failure_list", List.of(),
                "success_list", List.of(Map.of("item_id", ITEM_ID + 1, "unlist", false))));
        assertThatThrownBy(() -> provider.verifyActivationResponse(missingTarget, ITEM_ID))
                .hasMessageContaining("success_list")
                .hasMessageContaining("目标商品");

        Map<String, Object> stillUnlisted = Map.of("response", Map.of(
                "failure_list", List.of(),
                "success_list", List.of(Map.of("item_id", ITEM_ID, "unlist", true))));
        assertThatThrownBy(() -> provider.verifyActivationResponse(stillUnlisted, ITEM_ID))
                .hasMessageContaining("unlist=false");
    }

    @Test
    void activationRejectsTargetFailureEvenIfAnotherItemSucceeded() {
        ShopeeOfficialPublishingProvider provider = provider(mock(ShopeeOfficialClient.class));
        Map<String, Object> response = Map.of("response", Map.of(
                "failure_list", List.of(Map.of(
                        "item_id", ITEM_ID,
                        "failed_reason", "Can't unlist item when item is under promotion")),
                "success_list", List.of(Map.of(
                        "item_id", ITEM_ID + 1,
                        "unlist", false))));

        assertThatThrownBy(() -> provider.verifyActivationResponse(response, ITEM_ID))
                .hasMessageContaining("激活失败")
                .hasMessageContaining("under promotion");
    }

    @Test
    void statusReadbackRetriesUnlistAndAcceptsNormal() {
        ShopeeOfficialClient client = mock(ShopeeOfficialClient.class);
        ShopeeOfficialPublishingProvider provider = provider(client);
        MarketplaceAccess.Connection connection = connection();
        when(client.shopGet(GET_ITEM_BASE_INFO, connection, Map.of("item_id_list", ITEM_ID)))
                .thenReturn(baseInfo("UNLIST"), baseInfo("NORMAL"));
        List<Long> delays = new ArrayList<>();

        String status = provider.readActivatedItemStatus(
                connection, ITEM_ID, delays::add);

        assertThat(status).isEqualTo("NORMAL");
        assertThat(delays).containsExactly(1_000L);
        verify(client, times(2)).shopGet(
                GET_ITEM_BASE_INFO, connection, Map.of("item_id_list", ITEM_ID));
    }

    @Test
    void statusReadbackAcceptsReviewingButRejectsOtherTerminalStatus() {
        ShopeeOfficialClient reviewingClient = mock(ShopeeOfficialClient.class);
        ShopeeOfficialPublishingProvider reviewingProvider = provider(reviewingClient);
        MarketplaceAccess.Connection connection = connection();
        when(reviewingClient.shopGet(
                GET_ITEM_BASE_INFO, connection, Map.of("item_id_list", ITEM_ID)))
                .thenReturn(baseInfo("reviewing"));

        assertThat(reviewingProvider.readActivatedItemStatus(
                connection, ITEM_ID, ignored -> { })).isEqualTo("REVIEWING");

        ShopeeOfficialClient bannedClient = mock(ShopeeOfficialClient.class);
        ShopeeOfficialPublishingProvider bannedProvider = provider(bannedClient);
        when(bannedClient.shopGet(
                GET_ITEM_BASE_INFO, connection, Map.of("item_id_list", ITEM_ID)))
                .thenReturn(baseInfo("BANNED"));

        assertThatThrownBy(() -> bannedProvider.readActivatedItemStatus(
                connection, ITEM_ID, ignored -> { }))
                .hasMessageContaining("BANNED")
                .hasMessageContaining("不能标记为提交成功");
    }

    @Test
    void statusReadbackNeverAcceptsPersistentUnlist() {
        ShopeeOfficialClient client = mock(ShopeeOfficialClient.class);
        ShopeeOfficialPublishingProvider provider = provider(client);
        MarketplaceAccess.Connection connection = connection();
        when(client.shopGet(GET_ITEM_BASE_INFO, connection, Map.of("item_id_list", ITEM_ID)))
                .thenReturn(baseInfo("UNLIST"));
        List<Long> delays = new ArrayList<>();

        assertThatThrownBy(() -> provider.readActivatedItemStatus(
                connection, ITEM_ID, delays::add))
                .hasMessageContaining("回读仍为 UNLIST")
                .hasMessageContaining("不能标记为提交成功");
        assertThat(delays).containsExactly(1_000L, 2_000L, 3_000L, 4_000L);
        verify(client, times(5)).shopGet(
                GET_ITEM_BASE_INFO, connection, Map.of("item_id_list", ITEM_ID));
    }

    private Map<String, Object> baseInfo(String status) {
        return Map.of("response", Map.of("item_list", List.of(Map.of(
                "item_id", ITEM_ID,
                "item_status", status))));
    }

    private ShopeeOfficialPublishingProvider provider(ShopeeOfficialClient client) {
        return new ShopeeOfficialPublishingProvider(
                mock(ShopAuthorizationService.class), client,
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class),
                mock(MarketplaceImageDownloader.class), new ObjectMapper());
    }

    private MarketplaceAccess.Connection connection() {
        Shop shop = new Shop("org-1", MarketplacePlatform.SHOPEE, "9988", "merchant-1",
                null, "SELLER", "Demo", "PH", "[]");
        MarketplaceAccess.TokenSet tokens = new MarketplaceAccess.TokenSet(
                "access", "refresh", Instant.now().plusSeconds(3600), null, List.of());
        return new MarketplaceAccess.Connection(shop, tokens);
    }
}
