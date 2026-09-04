package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;

import java.util.Map;

public interface ProductPublishingProvider {
    String code();
    boolean dryRun();
    boolean supports(String destinationPlatform);
    PublishResult publish(ProductCapture capture, String destinationPlatform,
                          String shopId, Map<String, Object> request) throws Exception;

    record PublishResult(String externalId, Map<String, Object> providerResponse) {}
}
