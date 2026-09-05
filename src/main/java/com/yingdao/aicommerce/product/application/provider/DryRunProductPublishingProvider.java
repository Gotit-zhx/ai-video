package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DryRunProductPublishingProvider implements ProductPublishingProvider {
    @Override public String code() { return "DRY_RUN"; }
    @Override public boolean dryRun() { return true; }
    @Override public boolean supports(String destinationPlatform) { return true; }

    @Override
    public PublishResult publish(ProductCapture capture, String destinationPlatform,
                                 String shopId, Map<String, Object> request,
                                 ExternalCreateBoundary externalCreateBoundary) {
        // A Dry Run never crosses the external-create boundary.
        return new PublishResult("dryrun-" + capture.getId(), Map.of(
                "dryRun", true,
                "destinationPlatform", destinationPlatform,
                "message", "演练发布已完成，未向电商平台提交任何内容"
        ));
    }
}
