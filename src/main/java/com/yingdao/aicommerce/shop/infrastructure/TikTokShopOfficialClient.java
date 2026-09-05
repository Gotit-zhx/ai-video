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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TikTokShopOfficialClient implements MarketplaceAccess.Client {
    public static final String AUTHORIZATION_API_VERSION = "202309";
    public static final String PRODUCT_API_VERSION = "202309";
    private final MarketplaceHttp http;
    private final boolean enabled;
    private final String serviceId;
    private final String appKey;
    private final String appSecret;
    private final String authorizationBaseUrl;
    private final String tokenBaseUrl;
    private final String apiBaseUrl;

    public TikTokShopOfficialClient(MarketplaceHttp http,
            @Value("${app.shop-auth.tiktok-shop.enabled:false}") boolean enabled,
            @Value("${app.shop-auth.tiktok-shop.service-id:}") String serviceId,
            @Value("${app.shop-auth.tiktok-shop.app-key:}") String appKey,
            @Value("${app.shop-auth.tiktok-shop.app-secret:}") String appSecret,
            @Value("${app.shop-auth.tiktok-shop.authorization-base-url:https://services.tiktokshop.com}") String authorizationBaseUrl,
            @Value("${app.shop-auth.tiktok-shop.token-base-url:https://auth.tiktok-shops.com}") String tokenBaseUrl,
            @Value("${app.shop-auth.tiktok-shop.api-base-url:https://open-api.tiktokglobalshop.com}") String apiBaseUrl) {
        this.http = http;
        this.enabled = enabled;
        this.serviceId = serviceId == null ? "" : serviceId.trim();
        this.appKey = appKey == null ? "" : appKey.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.authorizationBaseUrl = authorizationBaseUrl;
        this.tokenBaseUrl = tokenBaseUrl;
        this.apiBaseUrl = apiBaseUrl;
    }

    @Override public MarketplacePlatform platform() { return MarketplacePlatform.TIKTOK_SHOP; }
    @Override public boolean configured() {
        return enabled && !serviceId.isBlank() && !appKey.isBlank() && !appSecret.isBlank();
    }
    public String apiVersion() { return PRODUCT_API_VERSION; }

    @Override
    public String authorizationUrl(String callbackUrl, String state) {
        requireConfigured();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("service_id", serviceId);
        query.put("state", state);
        return MarketplaceHttp.uri(authorizationBaseUrl, "/open/authorize", query).toString();
    }

    @Override
    public MarketplaceAccess.AuthorizationGrant exchangeAuthorizationCode(
            String code, Map<String, String> callbackParameters) {
        requireConfigured();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("app_key", appKey);
        query.put("app_secret", appSecret);
        query.put("auth_code", required(code, "TikTok Shop 未返回授权码"));
        query.put("grant_type", "authorized_code");
        Map<String, Object> tokenEnvelope = http.get(
                MarketplaceHttp.uri(tokenBaseUrl, "/api/v2/token/get", query), Map.of());
        Map<String, Object> tokenData = tokenData(tokenEnvelope, "获取 TikTok Shop Token");
        MarketplaceAccess.TokenSet tokens = tokens(tokenData, null);

        Map<String, Object> shopsEnvelope = apiGet("/authorization/" + AUTHORIZATION_API_VERSION + "/shops",
                tokens.accessToken(), null, Map.of());
        Map<String, Object> data = MarketplaceHttp.object(shopsEnvelope, "data");
        Object rawShops = data.get("shops");
        List<MarketplaceAccess.RemoteShop> shops = new ArrayList<>();
        if (rawShops instanceof List<?> values) {
            for (Object raw : values) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                Map<String, Object> value = stringMap(map);
                String externalId = firstText(value, "id", "shop_id", "code");
                String cipher = firstText(value, "cipher", "shop_cipher");
                if (externalId == null || cipher == null) continue;
                shops.add(new MarketplaceAccess.RemoteShop(externalId,
                        firstText(tokenData, "open_id", "seller_id"), cipher,
                        firstText(value, "seller_type", "type"),
                        fallback(firstText(value, "name", "shop_name"), "TikTok Shop " + externalId),
                        firstText(value, "region", "seller_base_region")));
            }
        }
        if (shops.isEmpty()) throw new MarketplaceApiException("TikTok Shop 授权成功，但未返回任何可用店铺");
        return new MarketplaceAccess.AuthorizationGrant(tokens, shops);
    }

    @Override
    public MarketplaceAccess.TokenSet refresh(MarketplaceAccess.TokenSet current, Shop shop) {
        requireConfigured();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("app_key", appKey);
        query.put("app_secret", appSecret);
        query.put("refresh_token", required(current.refreshToken(), "TikTok Shop 刷新令牌不存在，请重新授权"));
        query.put("grant_type", "refresh_token");
        Map<String, Object> envelope = http.get(
                MarketplaceHttp.uri(tokenBaseUrl, "/api/v2/token/refresh", query), Map.of());
        return tokens(tokenData(envelope, "刷新 TikTok Shop Token"), current);
    }

    public Map<String, Object> apiGet(String path, String accessToken, String shopCipher,
                                      Map<String, ?> businessQuery) {
        Map<String, Object> query = commonQuery();
        if (businessQuery != null) query.putAll(businessQuery);
        if (shopCipher != null && !shopCipher.isBlank()) query.put("shop_cipher", shopCipher);
        query.put("sign", sign(path, query, "", false));
        return checked(http.get(MarketplaceHttp.uri(apiBaseUrl, path, query),
                Map.of("x-tts-access-token", accessToken)), path);
    }

    public Map<String, Object> apiPost(String path, MarketplaceAccess.Connection connection,
                                       Map<String, ?> businessQuery, Map<String, ?> body) {
        String json = http.json(body);
        Map<String, Object> query = commonQuery();
        if (businessQuery != null) query.putAll(businessQuery);
        String cipher = connection.shop().getShopCipher();
        if (requiresShopCipher(path) && cipher != null && !cipher.isBlank()) {
            query.put("shop_cipher", cipher);
        }
        query.put("sign", sign(path, query, json, false));
        return checked(http.postJson(MarketplaceHttp.uri(apiBaseUrl, path, query),
                Map.of("x-tts-access-token", connection.tokens().accessToken()), json), path);
    }

    public Map<String, Object> apiMultipart(String path, MarketplaceAccess.Connection connection,
                                            Map<String, ?> businessQuery, String filename,
                                            String contentType, byte[] bytes, Map<String, String> fields) {
        Map<String, Object> query = commonQuery();
        if (businessQuery != null) query.putAll(businessQuery);
        String cipher = connection.shop().getShopCipher();
        if (requiresShopCipher(path) && cipher != null && !cipher.isBlank()) {
            query.put("shop_cipher", cipher);
        }
        query.put("sign", sign(path, query, "", true));
        return checked(http.postMultipart(MarketplaceHttp.uri(apiBaseUrl, path, query),
                Map.of("x-tts-access-token", connection.tokens().accessToken()),
                "data", filename, contentType, bytes, fields), path);
    }

    String sign(String path, Map<String, ?> query, String body, boolean multipart) {
        StringBuilder base = new StringBuilder(path);
        query.entrySet().stream()
                .filter(entry -> !"sign".equals(entry.getKey())
                        && !"access_token".equals(entry.getKey())
                        && !"x-tts-access-token".equals(entry.getKey())
                        && !(entry.getValue() instanceof Iterable<?>))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> base.append(entry.getKey()).append(entry.getValue()));
        if (!multipart && body != null) base.append(body);
        String wrapped = appSecret + base + appSecret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(wrapped.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new MarketplaceApiException("无法生成 TikTok Shop API 签名", exception);
        }
    }

    private Map<String, Object> commonQuery() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("app_key", appKey);
        query.put("timestamp", Instant.now().getEpochSecond());
        return query;
    }

    boolean requiresShopCipher(String path) {
        return !path.matches("^/product/\\d{6}/(images/upload|files/upload|compliance(?:/.*)?|global_products(?:/.*)?)$")
                && !path.matches("^/(authorization|seller)/\\d{6}/.*$");
    }

    private Map<String, Object> tokenData(Map<String, Object> envelope, String action) {
        long code = longValue(envelope.get("code"), -1);
        if (code != 0) {
            throw new MarketplaceApiException(action + "失败："
                    + http.safeBody(fallback(firstText(envelope, "message"), "错误码 " + code)));
        }
        return MarketplaceHttp.object(envelope, "data");
    }

    private MarketplaceAccess.TokenSet tokens(Map<String, Object> data,
                                               MarketplaceAccess.TokenSet previous) {
        Instant now = Instant.now();
        String access = required(firstText(data, "access_token"), "TikTok Shop Token 响应缺少 access_token");
        String refresh = fallback(firstText(data, "refresh_token"), previous == null ? null : previous.refreshToken());
        Instant accessExpiry = expiry(data.get("access_token_expire_in"), now, 7 * 86_400L);
        Instant refreshExpiry = expiry(data.get("refresh_token_expire_in"), now, 30 * 86_400L);
        List<String> scopes = strings(data.get("granted_scopes"));
        if (scopes.isEmpty() && previous != null) scopes = previous.scopes();
        return new MarketplaceAccess.TokenSet(access, refresh, accessExpiry, refreshExpiry, scopes);
    }

    private Map<String, Object> checked(Map<String, Object> response, String path) {
        long code = longValue(response.get("code"), -1);
        if (code != 0) {
            throw new MarketplaceApiException("TikTok Shop 官方接口 " + path + " 失败（错误码 "
                    + code + "）：" + http.safeBody(
                    fallback(firstText(response, "message"), "未知错误")));
        }
        return response;
    }

    private Instant expiry(Object raw, Instant now, long defaultSeconds) {
        long value = longValue(raw, defaultSeconds);
        if (value > 1_000_000_000L) return Instant.ofEpochSecond(value);
        return now.plusSeconds(Math.max(60, value));
    }

    private void requireConfigured() {
        if (!configured()) throw new MarketplaceApiException("TikTok Shop 官方应用尚未配置或启用");
    }

    private static List<String> strings(Object raw) {
        if (raw instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        if (raw instanceof String text && !text.isBlank()) return List.of(text.split(","));
        return List.of();
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String firstText(Map<String, ?> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
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

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
