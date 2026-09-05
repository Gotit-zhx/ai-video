package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.application.ProductSourceUrlParser.SourceIdentity;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public interface ProductEnrichmentProvider {
    String code();
    boolean enabled();
    EnrichmentResult enrich(SourceIdentity source, Map<String, Object> capturedPayload) throws Exception;

    record EnrichmentResult(Map<String, Object> normalizedData, Map<String, Object> rawData) {
        public EnrichmentResult {
            normalizedData = immutableCopyAllowingNulls(normalizedData);
            rawData = immutableCopyAllowingNulls(rawData);
        }

        private static Map<String, Object> immutableCopyAllowingNulls(Map<String, Object> value) {
            return value == null || value.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(value));
        }

        public static EnrichmentResult empty() {
            return new EnrichmentResult(Map.of(), Map.of());
        }
    }
}
