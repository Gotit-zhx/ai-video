package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;

import java.util.Map;

public interface WatermarkRemovalProvider {
    /**
     * Live implementations must resolve and reject private, loopback, link-local and multicast
     * destinations immediately before every download, disable unsafe redirects, and revalidate
     * every redirect target. The DIRECT_DOM MVP never downloads the source image itself.
     */
    String code();
    boolean dryRun();
    ProcessedImage process(ProductCapture capture, ProductCaptureImage image) throws Exception;

    record ProcessedImage(String url, Map<String, Object> providerResponse) {}
}
