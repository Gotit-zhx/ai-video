package com.yingdao.aicommerce.shop.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.domain.ShopCredential;
import com.yingdao.aicommerce.shop.mapper.ShopCredentialMapper;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persists every shop returned by one OAuth grant as one atomic unit. */
@Service
public class ShopAuthorizationGrantPersistence {
    private final ShopMapper shopMapper;
    private final ShopCredentialMapper credentialMapper;
    private final ShopCredentialCipher cipher;

    public ShopAuthorizationGrantPersistence(ShopMapper shopMapper,
                                             ShopCredentialMapper credentialMapper,
                                             ShopCredentialCipher cipher) {
        this.shopMapper = shopMapper;
        this.credentialMapper = credentialMapper;
        this.cipher = cipher;
    }

    @Transactional
    public List<AuthorizedShop> persist(String organizationId, String authorizedBy,
                                        MarketplacePlatform platform,
                                        MarketplaceAccess.AuthorizationGrant grant,
                                        String authorizedScopes) {
        Set<String> externalIds = new HashSet<>();
        String tokenGroupId = UUID.randomUUID().toString();
        String refreshSubject = null;
        for (MarketplaceAccess.RemoteShop remote : grant.shops()) {
            String externalId = remote.externalShopId() == null
                    ? "" : remote.externalShopId().trim();
            if (externalId.isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "平台授权响应缺少店铺 ID，未保存任何店铺");
            }
            if (!externalIds.add(externalId)) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "平台授权响应包含重复店铺，未保存任何店铺");
            }
            String currentSubject = refreshSubject(remote);
            if (refreshSubject == null) {
                refreshSubject = currentSubject;
            } else if (!refreshSubject.equals(currentSubject)) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "平台在同一 Token 中返回了不同授权主体，未保存任何店铺");
            }
        }
        List<AuthorizedShop> saved = new ArrayList<>();
        for (MarketplaceAccess.RemoteShop remote : grant.shops()) {
            Shop shop = upsertShop(organizationId, authorizedBy, platform, remote,
                    authorizedScopes);
            ShopCredential credential = upsertCredential(shop, tokenGroupId, grant.tokens());
            saved.add(new AuthorizedShop(shop, credential));
        }
        return List.copyOf(saved);
    }

    private String refreshSubject(MarketplaceAccess.RemoteShop remote) {
        String type = remote.authorizationSubjectType();
        String id = remote.authorizationSubjectId();
        if (type == null || type.isBlank()) {
            // Unknown legacy subjects are isolated per shop; sharing a rotating refresh
            // token without knowing its principal is less safe than an extra refresh.
            return "SHOP:" + remote.externalShopId();
        }
        return type.trim().toUpperCase() + ":" + (id == null ? "" : id.trim());
    }

    private Shop upsertShop(String organizationId, String authorizedBy,
                            MarketplacePlatform platform, MarketplaceAccess.RemoteShop remote,
                            String scopes) {
        Shop existing = find(organizationId, platform, remote.externalShopId());
        if (existing == null) {
            Shop created = new Shop(organizationId, platform, remote.externalShopId(),
                    remote.merchantId(), remote.shopCipher(), remote.sellerType(),
                    remote.name(), remote.region(), scopes, authorizedBy,
                    remote.authorizationSubjectType(), remote.authorizationSubjectId());
            try {
                if (shopMapper.insert(created) != 1) {
                    throw new BusinessException(HttpStatus.CONFLICT,
                            "店铺授权保存失败，请重新发起授权");
                }
                return created;
            } catch (DuplicateKeyException exception) {
                existing = find(organizationId, platform, remote.externalShopId());
                if (existing == null) throw exception;
            }
        }
        existing.authorized(remote.merchantId(), remote.shopCipher(), remote.sellerType(),
                remote.name(), remote.region(), scopes, authorizedBy,
                remote.authorizationSubjectType(), remote.authorizationSubjectId());
        if (shopMapper.updateById(existing) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "店铺授权已被其他请求更新，请重新发起授权");
        }
        return existing;
    }

    private Shop find(String organizationId, MarketplacePlatform platform, String externalShopId) {
        return shopMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getOrganizationId, organizationId)
                .eq(Shop::getPlatform, platform.name())
                .eq(Shop::getExternalShopId, externalShopId)
                .last("LIMIT 1"));
    }

    private ShopCredential upsertCredential(Shop shop, String tokenGroupId,
                                            MarketplaceAccess.TokenSet tokens) {
        ShopCredential credential = credentialMapper.selectById(shop.getId());
        if (credential == null) {
            ShopCredential created = new ShopCredential(shop.getId(), shop.getOrganizationId(),
                    tokenGroupId,
                    cipher.encrypt(tokens.accessToken(), shop.getOrganizationId(), shop.getId()),
                    cipher.encrypt(tokens.refreshToken(), shop.getOrganizationId(), shop.getId()),
                    tokens.accessTokenExpiresAt(), tokens.refreshTokenExpiresAt());
            try {
                if (credentialMapper.insert(created) != 1) {
                    throw new BusinessException(HttpStatus.CONFLICT,
                            "店铺凭证保存失败，请重新发起授权");
                }
                return created;
            } catch (DuplicateKeyException exception) {
                credential = credentialMapper.selectById(shop.getId());
                if (credential == null) throw exception;
            }
        }
        if (!shop.getOrganizationId().equals(credential.getOrganizationId())) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "店铺凭证企业归属不一致，已拒绝覆盖");
        }
        credential.reauthorized(tokenGroupId,
                cipher.encrypt(tokens.accessToken(), shop.getOrganizationId(), shop.getId()),
                cipher.encrypt(tokens.refreshToken(), shop.getOrganizationId(), shop.getId()),
                tokens.accessTokenExpiresAt(), tokens.refreshTokenExpiresAt());
        if (credentialMapper.updateById(credential) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "店铺凭证已被其他请求更新，请重新发起授权");
        }
        return credential;
    }

    public record AuthorizedShop(Shop shop, ShopCredential credential) {}
}
