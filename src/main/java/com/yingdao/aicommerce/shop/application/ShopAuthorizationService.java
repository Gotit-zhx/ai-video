package com.yingdao.aicommerce.shop.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.domain.ShopAuthorizationSession;
import com.yingdao.aicommerce.shop.domain.ShopCredential;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.mapper.ShopAuthorizationSessionMapper;
import com.yingdao.aicommerce.shop.mapper.ShopCredentialMapper;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ShopAuthorizationService {
    private final ShopMapper shopMapper;
    private final ShopCredentialMapper credentialMapper;
    private final ShopAuthorizationSessionMapper sessionMapper;
    private final MarketplaceClientRegistry clients;
    private final ShopCredentialCipher cipher;
    private final ShopIntegrationSettings settings;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public ShopAuthorizationService(ShopMapper shopMapper,
                                    ShopCredentialMapper credentialMapper,
                                    ShopAuthorizationSessionMapper sessionMapper,
                                    MarketplaceClientRegistry clients,
                                    ShopCredentialCipher cipher,
                                    ShopIntegrationSettings settings,
                                    ObjectMapper objectMapper) {
        this.shopMapper = shopMapper;
        this.credentialMapper = credentialMapper;
        this.sessionMapper = sessionMapper;
        this.clients = clients;
        this.cipher = cipher;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    public List<ShopView> list(PlatformPrincipal principal, String platform) {
        MarketplacePlatform requested = platform == null || platform.isBlank()
                ? null : MarketplacePlatform.parse(platform);
        LambdaQueryWrapper<Shop> query = new LambdaQueryWrapper<Shop>()
                .eq(Shop::getOrganizationId, principal.organizationId())
                .orderByAsc(Shop::getPlatform)
                .orderByAsc(Shop::getName);
        if (requested != null) query.eq(Shop::getPlatform, requested.name());
        return shopMapper.selectList(query).stream().map(this::view).toList();
    }

    public PlatformStatus status() {
        return new PlatformStatus(
                clients.configured(MarketplacePlatform.SHOPEE),
                clients.configured(MarketplacePlatform.TIKTOK_SHOP),
                settings.effectiveLivePublishing(), settings.publishingProvider()
        );
    }

    public AuthorizationStart start(String platformValue, String returnPath,
                                    PlatformPrincipal principal) {
        MarketplacePlatform platform = MarketplacePlatform.parse(platformValue);
        MarketplaceAccess.Client client = clients.require(platform);
        String state = state();
        String callbackUrl = settings.callbackBaseUrl()
                + "/api/v1/shop-authorizations/callback/" + platform.name().toLowerCase();
        String safeReturnPath = safeReturnPath(returnPath);
        ShopAuthorizationSession session = new ShopAuthorizationSession(
                principal.organizationId(), principal.userId(), platform, sha256(state),
                callbackUrl, safeReturnPath, settings.stateTtl());
        sessionMapper.insert(session);
        return new AuthorizationStart(client.authorizationUrl(callbackUrl, state),
                session.getExpiresAt(), platform.name());
    }

    public CallbackResult complete(String platformValue, String state, String code,
                                   Map<String, String> callbackParameters) {
        MarketplacePlatform platform = MarketplacePlatform.parse(platformValue);
        ShopAuthorizationSession session = session(platform, state);
        if (session == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "店铺授权请求不存在或已失效");
        Instant now = Instant.now();
        if (!"PENDING".equals(session.getStatus()) || !session.getExpiresAt().isAfter(now)
                || sessionMapper.consume(session.getId(), now) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "店铺授权请求已过期或已使用，请重新发起授权");
        }
        session.consumed(now);
        try {
            MarketplaceAccess.AuthorizationGrant grant = clients.require(platform)
                    .exchangeAuthorizationCode(code, callbackParameters);
            if (grant.shops().isEmpty()) {
                throw new MarketplaceApiException("授权成功，但平台没有返回店铺");
            }
            List<ShopView> connected = new ArrayList<>();
            String scopes = writeJson(grant.tokens().scopes());
            for (MarketplaceAccess.RemoteShop remote : grant.shops()) {
                Shop shop = upsertShop(session.getOrganizationId(), platform, remote, scopes);
                upsertCredential(shop, grant.tokens());
                connected.add(view(shop));
            }
            session.succeeded();
            sessionMapper.updateById(session);
            return new CallbackResult(platform.name(), List.copyOf(connected), session.getReturnPath(), null);
        } catch (RuntimeException exception) {
            String message = readable(exception);
            session.failed(message);
            sessionMapper.updateById(session);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, message);
        }
    }

    public void reject(String platformValue, String state, String providerMessage) {
        MarketplacePlatform platform = MarketplacePlatform.parse(platformValue);
        ShopAuthorizationSession session = session(platform, state);
        if (session == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "店铺授权请求不存在或已失效");
        Instant now = Instant.now();
        if (!"PENDING".equals(session.getStatus()) || !session.getExpiresAt().isAfter(now)
                || sessionMapper.consume(session.getId(), now) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "店铺授权请求已过期或已使用，请重新发起授权");
        }
        session.consumed(now);
        session.failed(readable(new MarketplaceApiException(providerMessage)));
        sessionMapper.updateById(session);
    }

    @Transactional
    public MarketplaceAccess.Connection connectionForPublishing(String organizationId,
                                                                 String shopId,
                                                                 MarketplacePlatform platform) {
        Shop shop = shopMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getId, shopId)
                .eq(Shop::getOrganizationId, organizationId)
                .eq(Shop::getPlatform, platform.name())
                .last("LIMIT 1"));
        if (shop == null) throw new BusinessException(HttpStatus.NOT_FOUND, "目标授权店铺不存在");
        if (!"ACTIVE".equals(shop.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "目标店铺授权已失效，请重新授权");
        }
        ShopCredential stored = credentialMapper.selectForUpdate(shop.getId());
        if (stored == null || !organizationId.equals(stored.getOrganizationId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "目标店铺没有可用凭证，请重新授权");
        }
        MarketplaceAccess.TokenSet current = decrypt(stored, shop);
        if (mustRefresh(current.accessTokenExpiresAt())) {
            try {
                MarketplaceAccess.TokenSet refreshed = clients.require(platform).refresh(current, shop);
                rotate(stored, shop, refreshed);
                credentialMapper.updateById(stored);
                current = refreshed;
            } catch (RuntimeException exception) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "店铺 Token 刷新失败，请重新授权：" + readable(exception));
            }
        }
        return new MarketplaceAccess.Connection(shop, current);
    }

    @Transactional
    public ShopView refresh(String id, PlatformPrincipal principal) {
        Shop shop = requireOwned(id, principal.organizationId());
        ShopCredential stored = credentialMapper.selectForUpdate(shop.getId());
        if (stored == null) throw new BusinessException(HttpStatus.CONFLICT, "店铺没有刷新凭证，请重新授权");
        MarketplacePlatform platform = MarketplacePlatform.parse(shop.getPlatform());
        MarketplaceAccess.TokenSet refreshed = clients.require(platform).refresh(decrypt(stored, shop), shop);
        rotate(stored, shop, refreshed);
        credentialMapper.updateById(stored);
        shop.authorized(shop.getMerchantId(), shop.getShopCipher(), shop.getSellerType(),
                shop.getName(), shop.getRegion(), writeJson(refreshed.scopes()));
        shopMapper.updateById(shop);
        return view(shop);
    }

    @Transactional
    public ShopView disconnect(String id, PlatformPrincipal principal) {
        Shop shop = requireOwned(id, principal.organizationId());
        credentialMapper.deleteById(shop.getId());
        shop.disabled();
        shopMapper.updateById(shop);
        return view(shop);
    }

    public String callbackRedirect(CallbackResult result) {
        return settings.frontendBaseUrl() + result.returnPath()
                + separator(result.returnPath()) + "shopAuth=success&platform="
                + encode(result.platform()) + "&connected=" + result.shops().size();
    }

    public String callbackErrorRedirect(String platform, String message) {
        return settings.frontendBaseUrl() + "/?shopAuth=error&platform=" + encode(platform)
                + "&message=" + encode(safeCallbackMessage(message));
    }

    private ShopAuthorizationSession session(MarketplacePlatform platform, String state) {
        if (state == null || state.isBlank() || state.length() > 1000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "店铺授权 state 无效");
        }
        return sessionMapper.selectOne(new LambdaQueryWrapper<ShopAuthorizationSession>()
                .eq(ShopAuthorizationSession::getStateHash, sha256(state))
                .eq(ShopAuthorizationSession::getPlatform, platform.name())
                .last("LIMIT 1"));
    }

    private Shop upsertShop(String organizationId, MarketplacePlatform platform,
                            MarketplaceAccess.RemoteShop remote, String scopes) {
        Shop existing = shopMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getOrganizationId, organizationId)
                .eq(Shop::getPlatform, platform.name())
                .eq(Shop::getExternalShopId, remote.externalShopId())
                .last("LIMIT 1"));
        if (existing == null) {
            Shop created = new Shop(organizationId, platform, remote.externalShopId(),
                    remote.merchantId(), remote.shopCipher(), remote.sellerType(),
                    remote.name(), remote.region(), scopes);
            try {
                shopMapper.insert(created);
                return created;
            } catch (DuplicateKeyException exception) {
                existing = shopMapper.selectOne(new LambdaQueryWrapper<Shop>()
                        .eq(Shop::getOrganizationId, organizationId)
                        .eq(Shop::getPlatform, platform.name())
                        .eq(Shop::getExternalShopId, remote.externalShopId())
                        .last("LIMIT 1"));
                if (existing == null) throw exception;
            }
        }
        existing.authorized(remote.merchantId(), remote.shopCipher(), remote.sellerType(),
                remote.name(), remote.region(), scopes);
        shopMapper.updateById(existing);
        return existing;
    }

    private void upsertCredential(Shop shop, MarketplaceAccess.TokenSet tokens) {
        ShopCredential credential = credentialMapper.selectById(shop.getId());
        if (credential == null) {
            credentialMapper.insert(new ShopCredential(shop.getId(), shop.getOrganizationId(),
                    cipher.encrypt(tokens.accessToken(), shop.getOrganizationId(), shop.getId()),
                    cipher.encrypt(tokens.refreshToken(), shop.getOrganizationId(), shop.getId()),
                    tokens.accessTokenExpiresAt(), tokens.refreshTokenExpiresAt()));
            return;
        }
        rotate(credential, shop, tokens);
        credentialMapper.updateById(credential);
    }

    private void rotate(ShopCredential credential, Shop shop, MarketplaceAccess.TokenSet tokens) {
        credential.rotate(
                cipher.encrypt(tokens.accessToken(), shop.getOrganizationId(), shop.getId()),
                tokens.refreshToken() == null ? null
                        : cipher.encrypt(tokens.refreshToken(), shop.getOrganizationId(), shop.getId()),
                tokens.accessTokenExpiresAt(), tokens.refreshTokenExpiresAt());
    }

    private MarketplaceAccess.TokenSet decrypt(ShopCredential stored, Shop shop) {
        return new MarketplaceAccess.TokenSet(
                cipher.decrypt(stored.getAccessTokenCiphertext(), shop.getOrganizationId(), shop.getId()),
                cipher.decrypt(stored.getRefreshTokenCiphertext(), shop.getOrganizationId(), shop.getId()),
                stored.getAccessTokenExpiresAt(), stored.getRefreshTokenExpiresAt(),
                readScopes(shop.getAuthorizedScopes()));
    }

    private Shop requireOwned(String id, String organizationId) {
        Shop shop = shopMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getId, id)
                .eq(Shop::getOrganizationId, organizationId)
                .last("LIMIT 1"));
        if (shop == null) throw new BusinessException(HttpStatus.NOT_FOUND, "店铺不存在");
        return shop;
    }

    private boolean mustRefresh(Instant expiresAt) {
        return expiresAt != null && !expiresAt.isAfter(Instant.now().plus(settings.tokenRefreshSkew()));
    }

    private List<String> readScopes(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            if (parsed instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        } catch (Exception ignored) {
            // A malformed legacy value never exposes credentials; reauthorization can replace it.
        }
        return List.of();
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("无法保存店铺授权范围", exception); }
    }

    private ShopView view(Shop shop) {
        return new ShopView(shop.getId(), shop.getPlatform(), shop.getExternalShopId(),
                shop.getName(), shop.getRegion(), shop.getSellerType(), shop.getStatus(),
                readScopes(shop.getAuthorizedScopes()), shop.getLastSyncedAt(), shop.getLastAuthError());
    }

    private String state() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成授权 state", exception);
        }
    }

    private String safeReturnPath(String value) {
        String path = value == null || value.isBlank() ? "/?view=product-publish" : value.trim();
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\r")
                || path.contains("\n") || path.length() > 1000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "授权完成后的返回路径不安全");
        }
        return path;
    }

    private String readable(Throwable exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) return "店铺官方授权失败，请稍后重试";
        return value.length() <= 900 ? value : value.substring(0, 900);
    }

    private String safeCallbackMessage(String message) {
        String value = message == null ? "店铺官方授权失败，请稍后重试" : message.trim();
        value = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private String separator(String path) { return path.contains("?") ? "&" : "?"; }
    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public record ShopView(String id, String platform, String externalShopId, String name,
                           String region, String sellerType, String status, List<String> scopes,
                           Instant lastSyncedAt, String lastAuthError) {}
    public record PlatformStatus(boolean shopeeConfigured, boolean tiktokShopConfigured,
                                 boolean livePublishingEnabled, String publishingProvider) {}
    public record AuthorizationStart(String authorizationUrl, Instant expiresAt, String platform) {}
    public record CallbackResult(String platform, List<ShopView> shops, String returnPath,
                                 String warning) {}
}
