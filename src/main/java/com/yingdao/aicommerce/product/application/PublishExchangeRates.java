package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

@Service
public class PublishExchangeRates {
    private final MarketplaceHttp http;
    public PublishExchangeRates(MarketplaceHttp http) { this.http = http; }
    public static String currency(String region) {
        String value = Map.ofEntries(Map.entry("MY","MYR"),Map.entry("PH","PHP"),Map.entry("SG","SGD"),
            Map.entry("TH","THB"),Map.entry("VN","VND"),Map.entry("ID","IDR"),Map.entry("TW","TWD"),
            Map.entry("BR","BRL"),Map.entry("MX","MXN"),Map.entry("US","USD"),Map.entry("GB","GBP"),
            Map.entry("JP","JPY"),Map.entry("DE","EUR"),Map.entry("FR","EUR"),Map.entry("IT","EUR"),Map.entry("ES","EUR"))
            .get(String.valueOf(region).toUpperCase());
        if (value == null) throw bad("无法确定目标店铺币种，请检查店铺地区配置");
        return value;
    }
    public Map<String,Object> snapshot(String source, String region) {
        String from = String.valueOf(source).toUpperCase();
        String to = currency(region);
        if (!from.matches("[A-Z]{3}")) throw bad("来源商品缺少有效币种，无法自动换算");
        if (from.equals(to)) return Map.of("sourceCurrency",from,"targetCurrency",to,"rate","1",
            "provider","SAME_CURRENCY","quotedAt",Instant.now().toString());
        var response = http.get(URI.create("https://open.er-api.com/v6/latest/" + from), Map.of());
        if (!"success".equals(response.get("result")) || !from.equals(response.get("base_code")))
            throw bad("最新汇率读取失败，请稍后重试");
        long updated;
        try { updated = Long.parseLong(String.valueOf(response.get("time_last_update_unix"))); }
        catch (RuntimeException e) { throw bad("汇率缺少更新时间，已停止上传"); }
        long age = Instant.now().getEpochSecond() - updated;
        if (age < -300 || age > 48 * 3600) throw bad("汇率已过期，已停止上传，请稍后重试");
        Object raw = response.get("rates") instanceof Map<?,?> rates ? rates.get(to) : null;
        BigDecimal rate;
        try { rate = new BigDecimal(String.valueOf(raw)); if (rate.signum() <= 0) throw new NumberFormatException(); }
        catch (RuntimeException e) { throw bad("目标币种汇率不可用，请稍后重试"); }
        return Map.of("sourceCurrency",from,"targetCurrency",to,"rate",rate.toPlainString(),
            "provider","ExchangeRate-API","quotedAt",Instant.ofEpochSecond(updated).toString(),
            "fetchedAt",Instant.now().toString());
    }
    public static BigDecimal convert(BigDecimal source, Map<String,Object> request) {
        if (!(request.get("exchangeRateSnapshot") instanceof Map<?,?> quote)) return source;
        if (source == null || source.signum() <= 0) throw bad("SKU 原售价必须大于 0");
        String currency = String.valueOf(quote.get("targetCurrency"));
        int scale = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
        BigDecimal result = source.multiply(new BigDecimal(String.valueOf(quote.get("rate"))))
            .setScale(Math.max(0,scale), RoundingMode.HALF_UP);
        if (result.signum() <= 0) throw bad("SKU 换算金额小于目标币种最小单位");
        return result;
    }
    public static String targetCurrency(String source, Map<String,Object> request) {
        return request.get("exchangeRateSnapshot") instanceof Map<?,?> quote
            ? String.valueOf(quote.get("targetCurrency")) : source;
    }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.CONFLICT,message); }
}
