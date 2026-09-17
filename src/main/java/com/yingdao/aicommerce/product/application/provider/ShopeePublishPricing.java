package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.Map;

/** An explicit publication price does not overwrite captured source prices. */
final class ShopeePublishPricing {
    private ShopeePublishPricing() {}
    static void validate(String region, String sourceCurrency, Map<String,Object> request) {
        if (request.get("exchangeRateSnapshot") instanceof Map<?,?> quote) {
            if (!com.yingdao.aicommerce.product.application.PublishExchangeRates.currency(region).equals(quote.get("targetCurrency"))
                    || !String.valueOf(sourceCurrency).equalsIgnoreCase(String.valueOf(quote.get("sourceCurrency"))))
                throw bad("汇率币种与商品或店铺不一致");
            return;
        }
        boolean override = request.containsKey("publishPrice");
        if ("MY".equalsIgnoreCase(region)) {
            if ((!"MYR".equalsIgnoreCase(sourceCurrency) || override)
                    && (!"MYR".equals(request.get("publishCurrency")) || !override)) {
                throw bad("马来西亚店铺须明确填写 MYR 发布价格，不能直接沿用 PHP 等来源币种金额");
            }
        } else if (override) {
            throw bad("独立发布价格当前仅支持马来西亚店铺");
        }
        if (override) price(null, request);
    }
    static BigDecimal price(BigDecimal source, Map<String,Object> request) {
        if (request.containsKey("exchangeRateSnapshot")) return com.yingdao.aicommerce.product.application.PublishExchangeRates.convert(source, request);
        if (!request.containsKey("publishPrice")) return source;
        try {
            var value = new BigDecimal(String.valueOf(request.get("publishPrice")));
            if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 2) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw bad("MYR 发布价格须大于 0 且最多两位小数");
        }
    }
    private static BusinessException bad(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }
}
