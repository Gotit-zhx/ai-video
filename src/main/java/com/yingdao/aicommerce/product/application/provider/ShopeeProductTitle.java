package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.springframework.http.HttpStatus;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/** Destination-specific title validation happens before any media uploads or product creation. */
final class ShopeeProductTitle {
    static final String LIMITS = "/api/v2/product/get_item_limit";
    static String prepare(ProductCapture capture, ShopeeOfficialClient client,
                          MarketplaceAccess.Connection connection, long category, java.util.List<java.math.BigDecimal> prices) {
        var response = MarketplaceHttp.object(client.shopGet(LIMITS, connection, Map.of("category_id", category)), "response");
        var limits = MarketplaceHttp.object(response, "item_name_length_limit");
        int min = number(limits.get("min_limit")), max = number(limits.get("max_limit"));
        if (min < 1 || max < min || max > 1000)
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "暂未取得 Shopee 店铺的标题长度规则，请稍后重试，商品尚未提交");
        var priceLimits = MarketplaceHttp.object(response, "price_limit");
        validatePrices(prices, priceLimits);
        return adapt(capture.getTitle(), capture.getCategoryName(), min, max);
    }
    static void validatePrices(java.util.List<java.math.BigDecimal> prices, Map<String,Object> limits) {
        java.math.BigDecimal minimum, maximum;
        try {
            minimum = new java.math.BigDecimal(String.valueOf(limits.get("min_limit")));
            maximum = new java.math.BigDecimal(String.valueOf(limits.get("max_limit")));
            if (minimum.signum() < 0 || maximum.compareTo(minimum) < 0) throw new NumberFormatException();
        } catch (RuntimeException failure) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "暂未取得 Shopee 店铺价格范围，商品尚未提交，请稍后重试");
        }
        for (java.math.BigDecimal price : prices) {
            if (price == null || price.compareTo(minimum) < 0 || price.compareTo(maximum) > 0)
                throw new BusinessException(HttpStatus.CONFLICT, "SKU 发布价格 " + price + " 超出目标店铺允许范围 " + minimum + "–" + maximum + "（店铺币种），请核对原价后重试");
        }
    }
    static String adapt(String original, String category, int min, int max) {
        String title = clean(original);
        if (title.isEmpty()) throw new BusinessException(HttpStatus.CONFLICT, "商品标题为空，请填写商品名称");
        if (count(title) > max) {
            // Remove only explicit promotional prefixes, never brand/model/specification brackets.
            title = title.replaceFirst("(?iu)^(?:\\[|【|\\()\\s*(?:ready\\s*stock|in\\s*stock|free\\s*shipping|hot\\s*sale)\\s*(?:\\]|】|\\))\\s*", "");
            title = shorten(title, max);
        }
        String detail = clean(category);
        if (count(title) < min && !detail.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(detail.toLowerCase(Locale.ROOT)))
            title = shorten(title + " - " + detail, max);
        if (count(title) < min)
            throw new BusinessException(HttpStatus.CONFLICT, "目标 Shopee 店铺要求标题 " + min + "–" + max + " 字符；现有商品名称和类目信息不足，请补充真实商品属性");
        return title;
    }
    private static String shorten(String value, int max) {
        if (count(value) <= max) return value;
        int limit = value.offsetByCodePoints(0, max);
        // Unicode word boundaries avoid cutting English words or surrogate pairs; support CJK too.
        BreakIterator words = BreakIterator.getWordInstance(Locale.ROOT);
        words.setText(value);
        int end = words.isBoundary(limit) ? limit : words.preceding(limit);
        if (end <= 0 || count(value.substring(0, end)) < max / 2) end = limit;
        String out = value.substring(0, end).strip().replaceFirst("[\\s/,:;|\\-–]+$", "");
        // Do not leave an opening parenthesis or bracket without its ending.
        for (String pair : new String[]{"()", "[]", "【】"}) {
            int open = out.lastIndexOf(pair.charAt(0)), close = out.lastIndexOf(pair.charAt(1));
            if (open > max / 2 && open > close) out = out.substring(0, open).strip();
        }
        return out;
    }
    private static String clean(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("[\\p{Z}\\s]+", " ").strip();
    }
    private static int count(String value) { return value.codePointCount(0, value.length()); }
    private static int number(Object value) {
        try { return Integer.parseInt(String.valueOf(value)); } catch (RuntimeException ignored) { return -1; }
    }
}
