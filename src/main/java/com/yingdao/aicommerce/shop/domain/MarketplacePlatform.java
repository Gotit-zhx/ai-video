package com.yingdao.aicommerce.shop.domain;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.Locale;

public enum MarketplacePlatform {
    SHOPEE,
    TIKTOK_SHOP;

    public static MarketplacePlatform parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "店铺平台不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if ("TIKTOK".equals(normalized) || "TK".equals(normalized)) {
            normalized = "TIKTOK_SHOP";
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "目前只支持 Shopee 和 TikTok Shop 店铺");
        }
    }
}
