package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProductSourceUrlParser {
    private static final List<String> SHOPEE_DOMAINS = List.of(
            "shopee.sg", "shopee.com.my", "shopee.co.th", "shopee.vn",
            "shopee.ph", "shopee.co.id"
    );
    private static final Pattern SHOPEE_ITEM_PATH = Pattern.compile(
            "(?:-i\\.|/product/)([0-9]{1,24})[./]([0-9]{1,24})(?:[/?#.]|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIKTOK_PRODUCT_PATH = Pattern.compile(
            "/(?:view/)?product/(?:[^/?#]+/)?([0-9]{6,32})(?:[/?#]|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIKTOK_PDP_PATH = Pattern.compile(
            "/shop/pdp/(?:[^/?#]+/)?([0-9]{6,32})(?:[/?#]|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SAFE_NUMERIC_ID = Pattern.compile("[0-9]{1,32}");

    public SourceIdentity parse(String sourceUrl, String declaredPlatform,
                                String hintedShopId, String hintedProductId) {
        if (sourceUrl == null || sourceUrl.isBlank() || sourceUrl.length() > 2048) {
            throw badRequest("商品链接不能为空且不能超过 2048 个字符");
        }
        URI uri;
        try {
            uri = URI.create(sourceUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw badRequest("商品链接格式不正确");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getPort() > 0 && uri.getPort() != 443) {
            throw badRequest("仅支持 Shopee 或 TikTok Shop 的 HTTPS 商品链接");
        }
        String host;
        try {
            host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw badRequest("商品链接域名不正确");
        }
        String platform;
        if (belongsTo(host, SHOPEE_DOMAINS)) {
            platform = "SHOPEE";
        } else if (host.equals("tiktok.com") || host.endsWith(".tiktok.com")) {
            platform = "TIKTOK";
        } else {
            throw badRequest("仅支持 Shopee 或 TikTok Shop 商品链接");
        }
        if (declaredPlatform != null && !declaredPlatform.isBlank()
                && !platform.equals(normalizePlatform(declaredPlatform))) {
            throw badRequest("商品链接与声明的平台不一致");
        }

        String hintedShop = safeNumericId(hintedShopId);
        String hintedProduct = safeNumericId(hintedProductId);
        String shopId = null;
        String productId = null;
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if ("SHOPEE".equals(platform)) {
            Matcher match = SHOPEE_ITEM_PATH.matcher(path + "/");
            if (match.find()) {
                shopId = match.group(1);
                productId = match.group(2);
            }
            if (shopId == null) shopId = query(uri, "shopid", "shop_id");
            if (productId == null) productId = query(uri, "itemid", "item_id");
        } else {
            Matcher product = TIKTOK_PRODUCT_PATH.matcher(path + "/");
            Matcher pdp = TIKTOK_PDP_PATH.matcher(path + "/");
            if (productId == null && product.find()) productId = product.group(1);
            if (productId == null && pdp.find()) productId = pdp.group(1);
            if (productId == null) productId = query(uri, "product_id", "productId");
            if (shopId == null) shopId = query(uri, "shop_id", "shopId", "seller_id");
        }
        if (hintedProduct != null && productId != null && !hintedProduct.equals(productId)) {
            throw badRequest("商品链接与提交的商品 ID 不一致");
        }
        if (hintedShop != null && shopId != null && !hintedShop.equals(shopId)) {
            throw badRequest("商品链接与提交的店铺 ID 不一致");
        }
        if (productId == null) productId = hintedProduct;
        if (shopId == null) shopId = hintedShop;
        productId = safeNumericId(productId);
        shopId = safeNumericId(shopId);
        if (productId == null) {
            throw badRequest("无法从商品链接识别商品 ID，请打开商品详情页后再采集");
        }
        try {
            URI canonical = new URI("https", null, host, -1,
                    uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath(),
                    canonicalQuery(uri, platform), null);
            return new SourceIdentity(platform, canonical.toASCIIString(), shopId, productId);
        } catch (Exception exception) {
            throw badRequest("商品链接格式不正确");
        }
    }

    public String normalizePlatform(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace("TIKTOK_SHOP", "TIKTOK").replace("TIKTOKSHOP", "TIKTOK");
        if (!List.of("SHOPEE", "TIKTOK").contains(normalized)) {
            throw badRequest("仅支持 SHOPEE 或 TIKTOK 平台");
        }
        return normalized;
    }

    private boolean belongsTo(String host, List<String> domains) {
        return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    private String query(URI uri, String... names) {
        if (uri.getRawQuery() == null) return null;
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String key = decodeQuery(separator < 0 ? pair : pair.substring(0, separator));
            for (String name : names) {
                if (name.equalsIgnoreCase(key)) {
                    String value = separator < 0 ? "" : decodeQuery(pair.substring(separator + 1));
                    String safe = safeNumericId(value);
                    if (safe != null) return safe;
                }
            }
        }
        return null;
    }

    private String canonicalQuery(URI uri, String platform) {
        List<String> pairs = new ArrayList<>();
        if ("SHOPEE".equals(platform)) {
            addCanonicalQueryPair(pairs, "itemid", queryValue(uri, "itemid", "item_id"), true);
            addCanonicalQueryPair(pairs, "shopid", queryValue(uri, "shopid", "shop_id"), true);
            addCanonicalQueryPair(pairs, "display_model_id", queryValue(uri, "display_model_id"), true);
        } else {
            addCanonicalQueryPair(pairs, "product_id", queryValue(uri, "product_id", "productId"), true);
            addCanonicalQueryPair(pairs, "shop_id", queryValue(uri, "shop_id", "shopId", "seller_id"), true);
            addCanonicalQueryPair(pairs, "region", queryValue(uri, "region"), false);
            addCanonicalQueryPair(pairs, "locale", queryValue(uri, "locale"), false);
        }
        return pairs.isEmpty() ? null : String.join("&", pairs);
    }

    private String queryValue(URI uri, String... names) {
        if (uri.getRawQuery() == null) return null;
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String key = decodeQuery(separator < 0 ? pair : pair.substring(0, separator));
            for (String name : names) {
                if (name.equalsIgnoreCase(key)) {
                    return separator < 0 ? "" : decodeQuery(pair.substring(separator + 1));
                }
            }
        }
        return null;
    }

    private void addCanonicalQueryPair(List<String> pairs, String name, String value, boolean numeric) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim();
        if (numeric ? !SAFE_NUMERIC_ID.matcher(normalized).matches()
                : !normalized.matches("[A-Za-z0-9_-]{1,24}")) return;
        pairs.add(name + "=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private String decodeQuery(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw badRequest("商品链接查询参数格式不正确");
        }
    }

    private String safeNumericId(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!SAFE_NUMERIC_ID.matcher(normalized).matches()) {
            throw badRequest("商品或店铺 ID 格式不正确");
        }
        return normalized;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }

    public record SourceIdentity(String platform, String canonicalUrl,
                                 String shopId, String productId) {}
}
