package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WatermarkRemovalRegistry {
    private final List<WatermarkRemovalProvider> providers;
    private final String configuredProvider;

    public WatermarkRemovalRegistry(List<WatermarkRemovalProvider> providers,
                                    @Value("${app.product-capture.watermark.provider:DRY_RUN}") String configuredProvider) {
        this.providers = List.copyOf(providers);
        this.configuredProvider = configuredProvider == null ? "DRY_RUN" : configuredProvider.trim();
    }

    public WatermarkRemovalProvider current() {
        return providers.stream().filter(provider -> provider.code().equalsIgnoreCase(configuredProvider))
                .findFirst().orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "去水印供应商未配置；系统没有提交图片，也没有产生费用"));
    }
}
