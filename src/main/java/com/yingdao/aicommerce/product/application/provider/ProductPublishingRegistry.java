package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductPublishingRegistry {
    private final List<ProductPublishingProvider> providers;
    private final String configuredProvider;
    private final boolean livePublishingEnabled;

    public ProductPublishingRegistry(List<ProductPublishingProvider> providers,
                                     @Value("${app.product-capture.publishing.provider:DRY_RUN}") String configuredProvider,
                                     @Value("${app.shop-auth.live-publishing-enabled:false}") boolean livePublishingEnabled) {
        this.providers = List.copyOf(providers);
        this.configuredProvider = configuredProvider == null ? "DRY_RUN" : configuredProvider.trim();
        this.livePublishingEnabled = livePublishingEnabled;
    }

    public ProductPublishingProvider current() {
        return providers.stream().filter(provider -> provider.code().equalsIgnoreCase(configuredProvider))
                .findFirst().orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "商品发布供应商未配置；系统没有向店铺提交任何内容"));
    }

    public ProductPublishingProvider forPlatform(String destinationPlatform) {
        if ("DRY_RUN".equalsIgnoreCase(configuredProvider)) return current();
        if (!livePublishingEnabled) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "真实店铺发布开关尚未启用；系统没有向店铺提交任何内容");
        }
        return providers.stream()
                .filter(provider -> !provider.dryRun())
                .filter(provider -> ("OFFICIAL".equalsIgnoreCase(configuredProvider)
                        || provider.code().equalsIgnoreCase(configuredProvider)))
                .filter(provider -> provider.supports(destinationPlatform))
                .findFirst().orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        destinationPlatform + " 官方商品发布接口未配置；系统没有提交任何内容"));
    }
}
