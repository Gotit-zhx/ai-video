package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductCaptureTemplateService;
import com.yingdao.aicommerce.product.application.ProductListingWarehouseService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/product-listing-templates/warehouses")
public class ProductListingWarehouseController {
    private final ProductListingWarehouseService service;
    private final ProductCaptureTemplateService captureTemplates;
    public ProductListingWarehouseController(ProductListingWarehouseService service, ProductCaptureTemplateService captureTemplates) {
        this.service = service;
        this.captureTemplates = captureTemplates;
    }
    @GetMapping("/shops") public ApiResponse<?> shops(@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.shops(user));}
    @GetMapping("/{id}")
    public ApiResponse<ProductListingWarehouseService.View> get(@PathVariable String id,
            @RequestParam(required = false) String captureTemplateId, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.get(id, user, captureTemplates.settingsScope(captureTemplateId, user, false)));
    }
}
