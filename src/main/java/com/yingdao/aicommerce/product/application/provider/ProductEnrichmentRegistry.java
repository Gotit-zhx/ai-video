package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.application.ProductSourceUrlParser.SourceIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ProductEnrichmentRegistry {
    private final List<ProductEnrichmentProvider> providers;
    private final boolean autoEnabled;

    public ProductEnrichmentRegistry(
            List<ProductEnrichmentProvider> providers,
            @Value("${app.product-capture.enrichment.auto-enabled:false}") boolean autoEnabled
    ) {
        this.providers = List.copyOf(providers);
        this.autoEnabled = autoEnabled;
    }

    public ProductEnrichmentProvider.EnrichmentResult enrich(
            String requestedProvider,
            SourceIdentity source,
            Map<String, Object> capturedPayload
    ) {
        String code = requestedProvider == null ? "DOM" : requestedProvider.trim().toUpperCase(Locale.ROOT);
        if (code.isBlank() || List.of("DOM", "NONE", "MOCK", "DISABLED").contains(code)) {
            return ProductEnrichmentProvider.EnrichmentResult.empty();
        }
        if ("AUTO".equals(code)) {
            if (!autoEnabled) return ProductEnrichmentProvider.EnrichmentResult.empty();
            ProductEnrichmentProvider provider = providers.stream()
                    .filter(ProductEnrichmentProvider::enabled).findFirst().orElse(null);
            return provider == null ? ProductEnrichmentProvider.EnrichmentResult.empty()
                    : call(provider, source, capturedPayload);
        }
        ProductEnrichmentProvider provider = providers.stream()
                .filter(candidate -> candidate.code().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "不支持的商品补全服务：" + code));
        if (!provider.enabled()) {
            throw new BusinessException(HttpStatus.CONFLICT, code + " 尚未启用；本次没有消耗 API 额度");
        }
        return call(provider, source, capturedPayload);
    }

    private ProductEnrichmentProvider.EnrichmentResult call(
            ProductEnrichmentProvider provider,
            SourceIdentity source,
            Map<String, Object> payload
    ) {
        try {
            return provider.enrich(source, payload);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY,
                    provider.code() + " 商品补全失败，本次草稿未保存：" + readable(exception));
        }
    }

    private String readable(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "上游服务不可用";
        return message.substring(0, Math.min(300, message.length()));
    }
}
