package com.yingdao.aicommerce.shop.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.domain.ShopAuthorizationSession;
import com.yingdao.aicommerce.shop.domain.ShopCredential;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    @SuppressWarnings("unchecked")
    void refreshingOneShopRotatesEveryCredentialFromTheSameAuthorizationGrant() {
        Fixture fixture = fixture();
        Shop target = shop("shop-a", "user-1");
        Shop sibling = shop("shop-b", "user-1");
        ShopCredential targetCredential = credential(fixture, target, "token-group", "old-access",
                "old-refresh", Instant.now().minusSeconds(1));
        ShopCredential siblingCredential = credential(fixture, sibling, "token-group", "old-access",
                "old-refresh", Instant.now().minusSeconds(1));
        when(fixture.shopMapper.selectOne(any(Wrapper.class))).thenReturn(target);
        when(fixture.credentialMapper.selectTokenGroupForUpdate("org-1", target.getId()))
                .thenReturn(List.of(targetCredential, siblingCredential));
        when(fixture.credentialMapper.updateById(any(ShopCredential.class))).thenReturn(1);
        MarketplaceAccess.Client client = mock(MarketplaceAccess.Client.class);
        when(fixture.clients.require(MarketplacePlatform.TIKTOK_SHOP)).thenReturn(client);
        Instant nextExpiry = Instant.now().plusSeconds(7200);
        when(client.refresh(any(MarketplaceAccess.TokenSet.class), eq(target))).thenReturn(
                new MarketplaceAccess.TokenSet("new-access", "new-refresh", nextExpiry,
                        Instant.now().plusSeconds(86_400), List.of("product.write")));

        MarketplaceAccess.Connection connection = fixture.service.connectionForPublishing(
                "org-1", target.getId(), MarketplacePlatform.TIKTOK_SHOP);

        assertThat(connection.tokens().accessToken()).isEqualTo("new-access");
        assertThat(targetCredential.getAccessTokenExpiresAt()).isEqualTo(nextExpiry);
        assertThat(siblingCredential.getAccessTokenExpiresAt()).isEqualTo(nextExpiry);
        assertThat(fixture.cipher.decrypt(targetCredential.getRefreshTokenCiphertext(),
                "org-1", target.getId())).isEqualTo("new-refresh");
        assertThat(fixture.cipher.decrypt(siblingCredential.getRefreshTokenCiphertext(),
                "org-1", sibling.getId())).isEqualTo("new-refresh");
        verify(client, times(1)).refresh(any(MarketplaceAccess.TokenSet.class), eq(target));
        verify(fixture.credentialMapper, times(2)).updateById(any(ShopCredential.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishingLookupCannotReadAnotherOrganizationsShop() {
        Fixture fixture = fixture();
        when(fixture.shopMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.connectionForPublishing(
                "org-other", "shop-id", MarketplacePlatform.SHOPEE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        verifyNoInteractions(fixture.credentialMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void disconnectRemovesOnlyTheSelectedShopCredentialAndRecordsTheActor() {
        Fixture fixture = fixture();
        Shop target = shop("shop-a", "user-old");
        Shop sibling = shop("shop-b", "user-old");
        ShopCredential targetCredential = credential(fixture, target, "token-group", "access",
                "refresh", Instant.now().plusSeconds(3600));
        ShopCredential siblingCredential = credential(fixture, sibling, "token-group", "access",
                "refresh", Instant.now().plusSeconds(3600));
        when(fixture.shopMapper.selectOne(any(Wrapper.class))).thenReturn(target);
        when(fixture.credentialMapper.selectTokenGroupForUpdate("org-1", target.getId()))
                .thenReturn(List.of(targetCredential, siblingCredential));
        when(fixture.credentialMapper.deleteById(target.getId())).thenReturn(1);
        when(fixture.shopMapper.updateById(target)).thenReturn(1);

        ShopAuthorizationService.ShopView result = fixture.service.disconnect(
                target.getId(), principal("user-disconnect", "org-1"));

        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(result.disconnectedBy()).isEqualTo("user-disconnect");
        assertThat(result.disconnectedAt()).isNotNull();
        assertThat(result.accessTokenExpiresAt()).isNull();
        verify(fixture.credentialMapper).deleteById(target.getId());
        verify(fixture.credentialMapper, never()).deleteById(sibling.getId());
        assertThat(fixture.cipher.decrypt(siblingCredential.getAccessTokenCiphertext(),
                "org-1", sibling.getId())).isEqualTo("access");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shopListIncludesAuthorizationAndExpiryMetadataButNeverCredentialSecrets() {
        Fixture fixture = fixture();
        Shop shop = shop("shop-a", "user-authorizer");
        Instant expiry = Instant.now().plusSeconds(3600);
        ShopCredential credential = credential(fixture, shop, "group", "access-secret",
                "refresh-secret", expiry);
        when(fixture.shopMapper.selectList(any(Wrapper.class))).thenReturn(List.of(shop));
        when(fixture.credentialMapper.selectForShops(eq("org-1"), any())).thenReturn(List.of(credential));

        List<ShopAuthorizationService.ShopView> result = fixture.service.list(
                principal("user-viewer", "org-1"), "tiktok_shop");

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.externalShopId()).isEqualTo("shop-a");
            assertThat(view.authorizedBy()).isEqualTo("user-authorizer");
            assertThat(view.authorizedAt()).isNotNull();
            assertThat(view.authorizationSubjectType()).isEqualTo("SELLER");
            assertThat(view.accessTokenExpiresAt()).isEqualTo(expiry);
            assertThat(view.toString()).doesNotContain("access-secret", "refresh-secret");
        });
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        ShopMapper shopMapper = mock(ShopMapper.class);
        ShopCredentialMapper credentialMapper = mock(ShopCredentialMapper.class);
        ShopAuthorizationSessionMapper sessionMapper = mock(ShopAuthorizationSessionMapper.class);
        MarketplaceClientRegistry clients = mock(MarketplaceClientRegistry.class);
        when(shopMapper.insert(any(Shop.class))).thenReturn(1);
        when(credentialMapper.insert(any(ShopCredential.class))).thenReturn(1);
        ShopAuthorizationSession session = new ShopAuthorizationSession(
                "org-1", "user-1", MarketplacePlatform.SHOPEE, "ignored-hash",
                "https://api.example/callback", "/?view=product-publish", Duration.ofMinutes(10));
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session);
        when(sessionMapper.consume(eq(session.getId()), any())).thenReturn(1);
        ShopCredentialCipher cipher = new ShopCredentialCipher(
                "test-credential-key-that-is-long-enough");
        ShopAuthorizationService service = new ShopAuthorizationService(
                shopMapper, credentialMapper, sessionMapper,
                new ShopAuthorizationGrantPersistence(shopMapper, credentialMapper, cipher), clients,
                cipher,
                new ShopIntegrationSettings(Duration.ofMinutes(10), Duration.ofMinutes(5),
                        URI.create("https://api.example"), URI.create("https://app.example"),
                        false, "DRY_RUN"),
                new ObjectMapper());
        return new Fixture(service, clients, shopMapper, credentialMapper, sessionMapper,
                session, cipher);
    }

    private Shop shop(String externalId, String authorizedBy) {
        return new Shop("org-1", MarketplacePlatform.TIKTOK_SHOP, externalId,
                "merchant-1", "cipher-" + externalId, "LOCAL", "Shop " + externalId,
                "PH", "[\"product.write\"]", authorizedBy, "SELLER", "merchant-1");
    }

    private ShopCredential credential(Fixture fixture, Shop shop, String tokenGroup,
                                      String accessToken, String refreshToken,
                                      Instant accessExpiry) {
        return new ShopCredential(shop.getId(), shop.getOrganizationId(), tokenGroup,
                fixture.cipher.encrypt(accessToken, shop.getOrganizationId(), shop.getId()),
                fixture.cipher.encrypt(refreshToken, shop.getOrganizationId(), shop.getId()),
                accessExpiry, Instant.now().plusSeconds(86_400));
    }

    private PlatformPrincipal principal(String userId, String organizationId) {
        return new PlatformPrincipal(userId, organizationId, "company", "tester", "Tester",
                "password", true, List.of("ADMIN"), List.of("product:publish"));
    }

    private record Fixture(ShopAuthorizationService service, MarketplaceClientRegistry clients,
                           ShopMapper shopMapper, ShopCredentialMapper credentialMapper,
                           ShopAuthorizationSessionMapper sessionMapper,
                           ShopAuthorizationSession session, ShopCredentialCipher cipher) {}
}
