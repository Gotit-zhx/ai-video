package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.application.ProductSourceUrlParser.SourceIdentity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Reserved extension point. V1 intentionally never calls TMAPI or consumes credits. */
@Component
public class TmApiProductEnrichmentProvider implements ProductEnrichmentProvider {
    @Override public String code() { return "TMAPI"; }
    @Override public boolean enabled() { return false; }
    @Override public EnrichmentResult enrich(SourceIdentity source, Map<String, Object> capturedPayload) {
        throw new IllegalStateException("TMAPI 在纯 DOM 第一版中未启用");
    }
}
