package com.yingdao.aicommerce.shop.application;

import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MarketplaceAccess {
    private MarketplaceAccess() {}

    public static final class TokenSet {
        private final String accessToken;
        private final String refreshToken;
        private final Instant accessTokenExpiresAt;
        private final Instant refreshTokenExpiresAt;
        private final List<String> scopes;

        public TokenSet(String accessToken, String refreshToken, Instant accessTokenExpiresAt,
                        Instant refreshTokenExpiresAt, List<String> scopes) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.accessTokenExpiresAt = accessTokenExpiresAt;
            this.refreshTokenExpiresAt = refreshTokenExpiresAt;
            this.scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }

        public String accessToken() { return accessToken; }
        public String refreshToken() { return refreshToken; }
        public Instant accessTokenExpiresAt() { return accessTokenExpiresAt; }
        public Instant refreshTokenExpiresAt() { return refreshTokenExpiresAt; }
        public List<String> scopes() { return scopes; }

        @Override public String toString() { return "TokenSet[REDACTED]"; }
    }

    public record RemoteShop(String externalShopId, String merchantId, String shopCipher,
                             String sellerType, String name, String region) {}

    public record AuthorizationGrant(TokenSet tokens, List<RemoteShop> shops) {
        public AuthorizationGrant {
            shops = shops == null ? List.of() : List.copyOf(shops);
        }
    }

    public static final class Connection {
        private final Shop shop;
        private final TokenSet tokens;

        public Connection(Shop shop, TokenSet tokens) {
            this.shop = shop;
            this.tokens = tokens;
        }

        public Shop shop() { return shop; }
        public TokenSet tokens() { return tokens; }
        @Override public String toString() { return "Connection[shopId=" + shop.getId() + ",tokens=REDACTED]"; }
    }

    public record PublishReceipt(String externalProductId, Map<String, Object> response) {}

    public interface Client {
        MarketplacePlatform platform();
        boolean configured();
        String authorizationUrl(String callbackUrl, String state);
        AuthorizationGrant exchangeAuthorizationCode(String code, Map<String, String> callbackParameters);
        TokenSet refresh(TokenSet current, Shop shop);
    }
}
