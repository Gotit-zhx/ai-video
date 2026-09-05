package com.yingdao.aicommerce.shop.infrastructure;

import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ShopeeOfficialClient implements MarketplaceAccess.Client {
    public static final String AUTH_SUBJECT_SHOP = "SHOP";
    public static final String AUTH_SUBJECT_MERCHANT = "MERCHANT";
    public static final String AUTH_PATH = "/auth";
    public static final String TOKEN_PATH = "/api/v2/auth/token/get";
    public static final String REFRESH_PATH = "/api/v2/auth/access_token/get";
    public static final String SHOP_INFO_PATH = "/api/v2/shop/get_shop_info";
    public static final String MERCHANT_SHOP_LIST_PATH =
            "/api/v2/merchant/get_shop_list_by_merchant";
    private static final int MERCHANT_SHOP_PAGE_SIZE = 500;
    private static final int MAX_MERCHANT_SHOP_PAGES = 100;

    private final MarketplaceHttp http;
    private final boolean enabled;
    private final String partnerId;
    private final String partnerKey;
    private final String authBaseUrl;
    private final String apiBaseUrl;
    private final ImageUploadSignScope imageUploadSignScope;

    public ShopeeOfficialClient(MarketplaceHttp http,
            @Value("${app.shop-auth.shopee.enabled:false}") boolean enabled,
            @Value("${app.shop-auth.shopee.partner-id:}") String partnerId,
            @Value("${app.shop-auth.shopee.partner-key:}") String partnerKey,
            @Value("${app.shop-auth.shopee.auth-base-url:https://open.shopee.com}") String authBaseUrl,
            @Value("${app.shop-auth.shopee.api-base-url:https://partner.shopeemobile.com}") String apiBaseUrl,
            @Value("${app.shop-auth.shopee.image-upload-sign-scope:SHOP}") String imageUploadSignScope) {
        this.http = http;
        this.enabled = enabled;
        this.partnerId = partnerId == null ? "" : partnerId.trim();
        this.partnerKey = partnerKey == null ? "" : partnerKey.trim();
        this.authBaseUrl = authBaseUrl;
        this.apiBaseUrl = apiBaseUrl;
        this.imageUploadSignScope = ImageUploadSignScope.parse(imageUploadSignScope);
    }

    @Override public MarketplacePlatform platform() { return MarketplacePlatform.SHOPEE; }
    @Override public boolean configured() {
        return enabled && !partnerId.isBlank() && !partnerKey.isBlank();
    }

    @Override
    public String authorizationUrl(String callbackUrl, String state) {
        requireConfigured();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("partner_id", partnerId);
        query.put("auth_type", "seller");
        query.put("redirect_uri", callbackUrl);
        query.put("response_type", "code");
        query.put("state", state);
        return MarketplaceHttp.uri(authBaseUrl, AUTH_PATH, query).toString();
    }

    @Override
    public MarketplaceAccess.AuthorizationGrant exchangeAuthorizationCode(
            String code, Map<String, String> callbackParameters) {
        requireConfigured();
        String callbackShopId = first(callbackParameters, "shop_id", "shopId");
        String mainAccountId = first(callbackParameters, "main_account_id", "mainAccountId");
        if (callbackShopId == null && mainAccountId == null) {
            throw new MarketplaceApiException("Shopee 授权回调缺少店铺或主账号 ID");
        }
        if (callbackShopId != null && mainAccountId != null) {
            throw new MarketplaceApiException("Shopee 授权回调同时包含店铺和主账号 ID，已拒绝交换 Token");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", required(code, "Shopee 未返回授权码"));
        body.put("partner_id", number(partnerId, "Shopee Partner ID 配置不正确"));
        if (callbackShopId != null) body.put("shop_id", number(callbackShopId, "Shopee 店铺 ID 不正确"));
        if (mainAccountId != null) body.put("main_account_id", number(mainAccountId, "Shopee 主账号 ID 不正确"));
        Map<String, Object> tokenResponse = publicPost(TOKEN_PATH, body);
        String accessToken = text(tokenResponse, "access_token");
        String refreshToken = text(tokenResponse, "refresh_token");
        Instant now = Instant.now();
        MarketplaceAccess.TokenSet tokens = new MarketplaceAccess.TokenSet(
                required(accessToken, "Shopee 授权响应缺少 access_token"), refreshToken,
                expiry(tokenResponse.get("expire_in"), now, 14_400L), null, List.of());

        Set<String> shopIds = new LinkedHashSet<>();
        if (callbackShopId != null) {
            shopIds.add(numericId(callbackShopId, "Shopee 店铺 ID 不正确"));
        }
        shopIds.addAll(idList(tokenResponse.get("shop_id_list"), "Shopee 店铺 ID 不正确"));

        List<String> merchantIds = idList(tokenResponse.get("merchant_id_list"),
                "Shopee Merchant ID 不正确");
        String legacyMerchantId = text(tokenResponse, "merchant_id");
        if (merchantIds.isEmpty() && legacyMerchantId != null) {
            merchantIds = List.of(numericId(legacyMerchantId, "Shopee Merchant ID 不正确"));
        }
        boolean merchantGrant = mainAccountId != null;
        if (merchantGrant && merchantIds.isEmpty()) {
            throw new MarketplaceApiException("Shopee 主账号授权成功，但 Token 响应缺少 Merchant ID");
        }
        Map<String, String> merchantByShop = merchantGrant
                ? shopsByMerchant(tokens.accessToken(), merchantIds) : Map.of();
        shopIds.addAll(merchantByShop.keySet());
        if (shopIds.isEmpty()) {
            throw new MarketplaceApiException("Shopee 授权成功，但没有返回可绑定的店铺 ID");
        }
        String refreshSubjectId = merchantGrant ? merchantIds.getFirst()
                : fallback(callbackShopId, shopIds.iterator().next());
        List<MarketplaceAccess.RemoteShop> shops = new ArrayList<>();
        for (String shopId : shopIds) {
            Map<String, Object> info = getShopInfo(tokens.accessToken(), shopId);
            Map<String, Object> response = MarketplaceHttp.object(info, "response");
            Map<String, Object> source = response.isEmpty() ? info : response;
            String merchantId = fallback(text(source, "merchant_id"),
                    fallback(merchantByShop.get(shopId),
                            merchantGrant ? refreshSubjectId : null));
            shops.add(new MarketplaceAccess.RemoteShop(shopId, merchantId, null,
                    "SELLER", fallback(text(source, "shop_name", "name"), "Shopee " + shopId),
                    text(source, "region", "country"),
                    merchantGrant ? AUTH_SUBJECT_MERCHANT : AUTH_SUBJECT_SHOP,
                    refreshSubjectId));
        }
        return new MarketplaceAccess.AuthorizationGrant(tokens, shops);
    }

    @Override
    public MarketplaceAccess.TokenSet refresh(MarketplaceAccess.TokenSet current, Shop shop) {
        requireConfigured();
        String refreshToken = required(current.refreshToken(), "Shopee 刷新令牌不存在，请重新授权店铺");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partner_id", number(partnerId, "Shopee Partner ID 配置不正确"));
        body.put("refresh_token", refreshToken);
        if (AUTH_SUBJECT_MERCHANT.equals(shop.getAuthorizationSubjectType())) {
            body.put("merchant_id", number(
                    fallback(shop.getAuthorizationSubjectId(), shop.getMerchantId()),
                    "Shopee Merchant ID 不正确"));
        } else if (shop.getAuthorizationSubjectType() == null
                || AUTH_SUBJECT_SHOP.equals(shop.getAuthorizationSubjectType())) {
            body.put("shop_id", number(
                    fallback(shop.getAuthorizationSubjectId(), shop.getExternalShopId()),
                    "Shopee 店铺 ID 不正确"));
        } else {
            throw new MarketplaceApiException("Shopee 授权主体类型不受支持，请重新授权店铺");
        }
        Map<String, Object> response = publicPost(REFRESH_PATH, body);
        String nextAccess = required(text(response, "access_token"), "Shopee 刷新响应缺少 access_token");
        String nextRefresh = fallback(text(response, "refresh_token"), refreshToken);
        return new MarketplaceAccess.TokenSet(nextAccess, nextRefresh,
                expiry(response.get("expire_in"), Instant.now(), 14_400L),
                current.refreshTokenExpiresAt(), current.scopes());
    }

    public Map<String, Object> shopGet(String path, MarketplaceAccess.Connection connection,
                                       Map<String, ?> businessQuery) {
        Shop shop = connection.shop();
        long timestamp = Instant.now().getEpochSecond();
        Map<String, Object> query = shopQuery(path, timestamp, connection.tokens().accessToken(),
                shop.getExternalShopId());
        if (businessQuery != null) query.putAll(businessQuery);
        return checked(http.get(MarketplaceHttp.uri(apiBaseUrl, path, query), Map.of()), path);
    }

    public Map<String, Object> shopPost(String path, MarketplaceAccess.Connection connection,
                                        Map<String, ?> body) {
        Shop shop = connection.shop();
        long timestamp = Instant.now().getEpochSecond();
        Map<String, Object> query = shopQuery(path, timestamp, connection.tokens().accessToken(),
                shop.getExternalShopId());
        return checked(http.postJson(MarketplaceHttp.uri(apiBaseUrl, path, query), Map.of(), http.json(body)), path);
    }

    public Map<String, Object> shopMultipart(String path, MarketplaceAccess.Connection connection,
                                             String filename, String contentType, byte[] bytes,
                                             Map<String, String> fields) {
        Shop shop = connection.shop();
        long timestamp = Instant.now().getEpochSecond();
        Map<String, Object> query = imageUploadSignScope == ImageUploadSignScope.PUBLIC
                ? publicQuery(path, timestamp)
                : shopQuery(path, timestamp, connection.tokens().accessToken(), shop.getExternalShopId());
        return checked(http.postMultipart(MarketplaceHttp.uri(apiBaseUrl, path, query), Map.of(),
                "image", filename, contentType, bytes, fields == null ? Map.of() : fields), path);
    }

    String signPublic(String path, long timestamp) {
        return hmacSha256(partnerId + path + timestamp);
    }

    String signShop(String path, long timestamp, String accessToken, String shopId) {
        return hmacSha256(partnerId + path + timestamp + accessToken + shopId);
    }

    String signMerchant(String path, long timestamp, String accessToken, String merchantId) {
        return hmacSha256(partnerId + path + timestamp + accessToken + merchantId);
    }

    private Map<String, Object> getShopInfo(String accessToken, String shopId) {
        long timestamp = Instant.now().getEpochSecond();
        Map<String, Object> query = shopQuery(SHOP_INFO_PATH, timestamp, accessToken, shopId);
        return checked(http.get(MarketplaceHttp.uri(apiBaseUrl, SHOP_INFO_PATH, query), Map.of()), SHOP_INFO_PATH);
    }

    private Map<String, String> shopsByMerchant(String accessToken, List<String> merchantIds) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String merchantId : merchantIds) {
            boolean more = true;
            int page = 1;
            while (more) {
                if (page > MAX_MERCHANT_SHOP_PAGES) {
                    throw new MarketplaceApiException(
                            "Shopee Merchant 店铺分页超过安全上限，请联系平台支持");
                }
                Map<String, Object> envelope = merchantGet(MERCHANT_SHOP_LIST_PATH,
                        accessToken, merchantId,
                        Map.of("page_no", page, "page_size", MERCHANT_SHOP_PAGE_SIZE));
                Map<String, Object> response = MarketplaceHttp.object(envelope, "response");
                Map<String, Object> source = response.isEmpty() ? envelope : response;
                Object rawShops = source.get("shop_list");
                if (rawShops instanceof List<?> values) {
                    for (Object rawShop : values) {
                        if (!(rawShop instanceof Map<?, ?> rawMap)) continue;
                        Object rawId = rawMap.get("shop_id");
                        if (rawId != null) addMerchantShop(result, rawId, merchantId);
                        Object rawAffiliates = rawMap.get("sip_affi_shops");
                        if (!(rawAffiliates instanceof List<?> affiliates)) continue;
                        for (Object rawAffiliate : affiliates) {
                            if (!(rawAffiliate instanceof Map<?, ?> affiliate)) continue;
                            Object rawAffiliateId = affiliate.get("affi_shop_id");
                            if (rawAffiliateId != null) {
                                addMerchantShop(result, rawAffiliateId, merchantId);
                            }
                        }
                    }
                }
                more = booleanValue(source.get("more"));
                page++;
            }
        }
        return result;
    }

    private void addMerchantShop(Map<String, String> shops, Object rawShopId,
                                 String merchantId) {
        String shopId = numericId(String.valueOf(rawShopId),
                "Shopee Merchant 返回的店铺 ID 不正确");
        String previous = shops.putIfAbsent(shopId, merchantId);
        if (previous != null && !previous.equals(merchantId)) {
            throw new MarketplaceApiException(
                    "Shopee Merchant 返回了归属冲突的店铺，未保存任何店铺");
        }
    }

    private Map<String, Object> merchantGet(String path, String accessToken,
                                            String merchantId,
                                            Map<String, ?> businessQuery) {
        long timestamp = Instant.now().getEpochSecond();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("partner_id", partnerId);
        query.put("timestamp", timestamp);
        query.put("access_token", accessToken);
        query.put("merchant_id", merchantId);
        if (businessQuery != null) query.putAll(businessQuery);
        query.put("sign", signMerchant(path, timestamp, accessToken, merchantId));
        return checked(http.get(MarketplaceHttp.uri(apiBaseUrl, path, query), Map.of()), path);
    }

    private Map<String, Object> publicPost(String path, Map<String, ?> body) {
        long timestamp = Instant.now().getEpochSecond();
        Map<String, Object> query = publicQuery(path, timestamp);
        return checked(http.postJson(MarketplaceHttp.uri(apiBaseUrl, path, query), Map.of(), http.json(body)), path);
    }

    private Map<String, Object> publicQuery(String path, long timestamp) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("partner_id", partnerId);
        query.put("timestamp", timestamp);
        query.put("sign", signPublic(path, timestamp));
        return query;
    }

    private Map<String, Object> shopQuery(String path, long timestamp, String accessToken, String shopId) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("partner_id", partnerId);
        query.put("timestamp", timestamp);
        query.put("access_token", accessToken);
        query.put("shop_id", shopId);
        query.put("sign", signShop(path, timestamp, accessToken, shopId));
        return query;
    }

    private Map<String, Object> checked(Map<String, Object> response, String path) {
        String error = text(response, "error");
        if (error != null && !error.isBlank()) {
            throw new MarketplaceApiException("Shopee 官方接口 " + path + " 失败："
                    + http.safeBody(fallback(text(response, "message"), error)));
        }
        return response;
    }

    private String hmacSha256(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(partnerKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new MarketplaceApiException("无法生成 Shopee API 签名", exception);
        }
    }

    private void requireConfigured() {
        if (!configured()) throw new MarketplaceApiException("Shopee 官方应用尚未配置或启用");
    }

    private static String text(Map<String, ?> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
        }
        return null;
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new MarketplaceApiException(message);
        return value;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<String> idList(Object raw, String message) {
        if (!(raw instanceof List<?> values)) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (value != null) result.add(numericId(String.valueOf(value), message));
        }
        return List.copyOf(result);
    }

    private static String numericId(String value, String message) {
        return String.valueOf(number(value, message));
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static Instant expiry(Object raw, Instant now, long defaultSeconds) {
        long value = longValue(raw, defaultSeconds);
        if (value > 1_000_000_000L) return Instant.ofEpochSecond(value);
        return now.plusSeconds(Math.max(60, value));
    }

    private static long number(String value, String message) {
        try { return Long.parseLong(value); }
        catch (Exception exception) { throw new MarketplaceApiException(message); }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private enum ImageUploadSignScope {
        SHOP,
        PUBLIC;

        private static ImageUploadSignScope parse(String value) {
            try {
                return value == null ? SHOP : valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "APP_SHOPEE_IMAGE_UPLOAD_SIGN_SCOPE 只能是 SHOP 或 PUBLIC", exception);
            }
        }
    }
}
