package com.yingdao.aicommerce.shop.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.domain.ShopCredential;
import com.yingdao.aicommerce.shop.mapper.ShopCredentialMapper;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopAuthorizationGrantPersistenceTest {
    private final ShopMapper shopMapper = mock(ShopMapper.class);
    private final ShopCredentialMapper credentialMapper = mock(ShopCredentialMapper.class);
    private final ShopCredentialCipher cipher = new ShopCredentialCipher(
            "test-credential-key-that-is-long-enough");
    private final ShopAuthorizationGrantPersistence persistence =
            new ShopAuthorizationGrantPersistence(shopMapper, credentialMapper, cipher);

    @Test
    void oneGrantPersistsMultipleShopsWithoutFlatteningThemIntoOneRecord() {
        when(shopMapper.insert(any(Shop.class))).thenReturn(1);
        when(credentialMapper.insert(any(ShopCredential.class))).thenReturn(1);
        Instant accessExpiry = Instant.now().plusSeconds(3600);
        Instant refreshExpiry = Instant.now().plusSeconds(7200);
        MarketplaceAccess.AuthorizationGrant grant = grant("access-new", "refresh-new",
                accessExpiry, refreshExpiry,
                remote("shop-a", "cipher-a"), remote("shop-b", "cipher-b"));

        List<ShopAuthorizationGrantPersistence.AuthorizedShop> result = persistence.persist(
                "org-1", "user-1", MarketplacePlatform.TIKTOK_SHOP, grant,
                "[\"product.write\"]");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(value -> value.shop().getExternalShopId())
                .containsExactly("shop-a", "shop-b");
        assertThat(result).extracting(value -> value.shop().getAuthorizedBy())
                .containsOnly("user-1");
        assertThat(result).allSatisfy(value -> {
            assertThat(value.shop().getAuthorizedAt()).isNotNull();
            assertThat(value.shop().getAuthorizationSubjectType()).isEqualTo("SELLER");
            assertThat(value.shop().getAuthorizationSubjectId()).isEqualTo("merchant-1");
            assertThat(value.credential().getOrganizationId()).isEqualTo("org-1");
            assertThat(value.credential().getAccessTokenExpiresAt()).isEqualTo(accessExpiry);
            assertThat(cipher.decrypt(value.credential().getAccessTokenCiphertext(), "org-1",
                    value.shop().getId())).isEqualTo("access-new");
        });
        assertThat(result.get(0).credential().getTokenGroupId())
                .isEqualTo(result.get(1).credential().getTokenGroupId())
                .isNotEqualTo(result.get(0).shop().getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedAuthorizationUpdatesTheSameShopAndReplacesTheOldGrant() {
        Shop existing = new Shop("org-1", MarketplacePlatform.TIKTOK_SHOP, "shop-a",
                "merchant", "old-cipher", "LOCAL", "Old", "PH", "[]", "user-old");
        ShopCredential oldCredential = new ShopCredential(existing.getId(), "org-1", "old-group",
                cipher.encrypt("access-old", "org-1", existing.getId()),
                cipher.encrypt("refresh-old", "org-1", existing.getId()),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(100));
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(shopMapper.updateById(existing)).thenReturn(1);
        when(credentialMapper.selectById(existing.getId())).thenReturn(oldCredential);
        when(credentialMapper.updateById(oldCredential)).thenReturn(1);

        List<ShopAuthorizationGrantPersistence.AuthorizedShop> result = persistence.persist(
                "org-1", "user-new", MarketplacePlatform.TIKTOK_SHOP,
                grant("access-new", null, Instant.now().plusSeconds(3600), null,
                        new MarketplaceAccess.RemoteShop("shop-a", "merchant", "new-cipher",
                                "LOCAL", "New", "PH", "SELLER", "merchant")),
                "[]");

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.shop()).isSameAs(existing);
            assertThat(value.shop().getName()).isEqualTo("New");
            assertThat(value.shop().getShopCipher()).isEqualTo("new-cipher");
            assertThat(value.shop().getAuthorizedBy()).isEqualTo("user-new");
            assertThat(value.shop().getAuthorizationSubjectType()).isEqualTo("SELLER");
            assertThat(value.credential().getTokenGroupId()).isNotEqualTo("old-group");
            assertThat(value.credential().getRefreshTokenCiphertext()).isNull();
            assertThat(cipher.decrypt(value.credential().getAccessTokenCiphertext(), "org-1",
                    existing.getId())).isEqualTo("access-new");
        });
        verify(shopMapper, never()).insert(any(Shop.class));
        verify(credentialMapper, never()).insert(any(ShopCredential.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesToOverwriteCredentialOwnedByAnotherOrganization() {
        Shop shop = new Shop("org-1", MarketplacePlatform.SHOPEE, "shop-a",
                "merchant", null, "SELLER", "Shop", "PH", "[]", "user-1");
        ShopCredential foreignCredential = new ShopCredential(shop.getId(), "org-2", "group-2",
                "ciphertext", null, Instant.now().plusSeconds(60), null);
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop);
        when(shopMapper.updateById(shop)).thenReturn(1);
        when(credentialMapper.selectById(shop.getId())).thenReturn(foreignCredential);

        assertThatThrownBy(() -> persistence.persist("org-1", "user-1",
                MarketplacePlatform.SHOPEE,
                grant("access", "refresh", Instant.now().plusSeconds(60), null,
                        remote("shop-a", null)), "[]"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("企业归属不一致");
        verify(credentialMapper, never()).updateById(any(ShopCredential.class));
    }

    @Test
    void persistMethodDefinesAnAtomicTransactionBoundary() throws Exception {
        assertThat(ShopAuthorizationGrantPersistence.class
                .getMethod("persist", String.class, String.class, MarketplacePlatform.class,
                        MarketplaceAccess.AuthorizationGrant.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    private MarketplaceAccess.AuthorizationGrant grant(
            String accessToken, String refreshToken, Instant accessExpiry, Instant refreshExpiry,
            MarketplaceAccess.RemoteShop... shops) {
        return new MarketplaceAccess.AuthorizationGrant(
                new MarketplaceAccess.TokenSet(accessToken, refreshToken, accessExpiry,
                        refreshExpiry, List.of("product.write")), List.of(shops));
    }

    private MarketplaceAccess.RemoteShop remote(String id, String shopCipher) {
        return new MarketplaceAccess.RemoteShop(id, "merchant-1", shopCipher,
                "LOCAL", "Shop " + id, "PH", "SELLER", "merchant-1");
    }
}
