package com.yingdao.aicommerce.shop.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.ShopAuthorizationSession;
import com.yingdao.aicommerce.shop.mapper.ShopAuthorizationSessionMapper;
import com.yingdao.aicommerce.shop.mapper.ShopCredentialMapper;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopAuthorizationServiceTest {

    @Test
    void successfulStateCanOnlyBeConsumedOnce() {
        Fixture fixture = fixture();
        MarketplaceAccess.Client client = mock(MarketplaceAccess.Client.class);
        when(fixture.clients.require(MarketplacePlatform.SHOPEE)).thenReturn(client);
        MarketplaceAccess.TokenSet tokens = new MarketplaceAccess.TokenSet(
                "access", "refresh", Instant.now().plusSeconds(3600), null, List.of("product.write"));
        when(client.exchangeAuthorizationCode(eq("code-1"), any())).thenReturn(
                new MarketplaceAccess.AuthorizationGrant(tokens, List.of(
                        new MarketplaceAccess.RemoteShop(
                                "shop-100", "merchant-1", null, "SELLER", "测试店铺", "PH"))));

        ShopAuthorizationService.CallbackResult result = fixture.service.complete(
                "shopee", "state-1", "code-1", Map.of("shop_id", "shop-100"));

        assertThat(result.shops()).hasSize(1);
        assertThat(fixture.session.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(fixture.session.getConsumedAt()).isNotNull();
        assertThatThrownBy(() -> fixture.service.complete(
                "shopee", "state-1", "code-1", Map.of("shop_id", "shop-100")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已过期或已使用");
        verify(client, times(1)).exchangeAuthorizationCode(eq("code-1"), any());
        verify(fixture.sessionMapper, times(1)).consume(eq(fixture.session.getId()), any());
    }

    @Test
    void providerRejectionAlsoConsumesStateAndBoundsRedirectMessage() {
        Fixture fixture = fixture();
        String longMessage = "平台拒绝：" + "x".repeat(2000);

        fixture.service.reject("shopee", "state-1", longMessage);

        assertThat(fixture.session.getStatus()).isEqualTo("FAILED");
        assertThat(fixture.session.getConsumedAt()).isNotNull();
        assertThat(fixture.session.getErrorMessage()).hasSizeLessThanOrEqualTo(900);
        assertThat(fixture.service.callbackErrorRedirect("SHOPEE", longMessage).length())
                .isLessThan(500);
        assertThatThrownBy(() -> fixture.service.reject("shopee", "state-1", longMessage))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已过期或已使用");
        verify(fixture.sessionMapper, times(1)).consume(eq(fixture.session.getId()), any());
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        ShopMapper shopMapper = mock(ShopMapper.class);
        ShopCredentialMapper credentialMapper = mock(ShopCredentialMapper.class);
        ShopAuthorizationSessionMapper sessionMapper = mock(ShopAuthorizationSessionMapper.class);
        MarketplaceClientRegistry clients = mock(MarketplaceClientRegistry.class);
        ShopAuthorizationSession session = new ShopAuthorizationSession(
                "org-1", "user-1", MarketplacePlatform.SHOPEE, "ignored-hash",
                "https://api.example/callback", "/?view=product-publish", Duration.ofMinutes(10));
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session);
        when(sessionMapper.consume(eq(session.getId()), any())).thenReturn(1);
        ShopAuthorizationService service = new ShopAuthorizationService(
                shopMapper, credentialMapper, sessionMapper, clients,
                new ShopCredentialCipher("test-credential-key-that-is-long-enough"),
                new ShopIntegrationSettings(Duration.ofMinutes(10), Duration.ofMinutes(5),
                        URI.create("https://api.example"), URI.create("https://app.example"),
                        false, "DRY_RUN"),
                new ObjectMapper());
        return new Fixture(service, clients, sessionMapper, session);
    }

    private record Fixture(ShopAuthorizationService service, MarketplaceClientRegistry clients,
                           ShopAuthorizationSessionMapper sessionMapper,
                           ShopAuthorizationSession session) {}
}
