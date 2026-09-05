package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DryRunWatermarkRemovalProvider implements WatermarkRemovalProvider {
    @Override public String code() { return "DRY_RUN"; }
    @Override public boolean dryRun() { return true; }

    @Override
    public ProcessedImage process(ProductCapture capture, ProductCaptureImage image) {
        return new ProcessedImage(image.getOriginalUrl(), Map.of(
                "dryRun", true,
                "message", "去水印供应商未启用，演练模式保留原图"
        ));
    }
}
