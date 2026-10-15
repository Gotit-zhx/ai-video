package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** A module is a data namespace, not a replacement for organization authorization. */
public final class CommerceModule {
    public static final String CAPTURE = "CAPTURE";
    public static final String FISSION = "FISSION";
    private CommerceModule() {}

    public static String validate(String value) {
        if (value == null || value.isBlank()) return CAPTURE;
        return switch (value) {
            case CAPTURE, FISSION, "TEMPLATES", "ADS", "ASSETS" -> value;
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "商品功能配置范围无效");
        };
    }

    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
            return validate(attributes.getRequest().getHeader("X-Commerce-Module"));
        return CAPTURE;
    }
}
