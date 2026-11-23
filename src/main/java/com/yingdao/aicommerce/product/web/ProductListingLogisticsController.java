package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductCaptureTemplateService;
import com.yingdao.aicommerce.product.application.ProductListingLogisticsService;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/product-listing-templates/logistics")
public class ProductListingLogisticsController {
    private final ProductListingLogisticsService logistics;
    private final ProductCaptureTemplateService captureTemplates;
    public ProductListingLogisticsController(ProductListingLogisticsService logistics, ProductCaptureTemplateService captureTemplates) {
        this.logistics = logistics;
        this.captureTemplates = captureTemplates;
    }
    @GetMapping("/shops")
    public ApiResponse<List<ShopAuthorizationService.ShopView>> shops(@AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(logistics.shops(user));
    }
    @GetMapping("/{id}")
    public ApiResponse<ProductListingLogisticsService.View> get(@PathVariable String id,
            @RequestParam(required = false) String captureTemplateId, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(logistics.get(id, user, captureTemplates.settingsScope(captureTemplateId, user, false)));
    }
}
