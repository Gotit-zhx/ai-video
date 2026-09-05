package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;

import java.util.Map;

public interface ProductPublishingProvider {
    String code();
    boolean dryRun();
    boolean supports(String destinationPlatform);

    default PublishResult publish(ProductCapture capture, String destinationPlatform,
                                  String shopId, Map<String, Object> request) {
        return publish(capture, destinationPlatform, shopId, request, () -> {});
    }

    PublishResult publish(ProductCapture capture, String destinationPlatform,
                          String shopId, Map<String, Object> request,
                          ExternalCreateBoundary externalCreateBoundary);

    /**
     * Called after validation/preflight has succeeded and immediately before the first request
     * that can create the marketplace product. The callback must complete durably before that
     * request is allowed to start.
     */
    @FunctionalInterface
    interface ExternalCreateBoundary {
        void enter();
    }

    record PublishResult(String externalId, Map<String, Object> providerResponse) {}
}
