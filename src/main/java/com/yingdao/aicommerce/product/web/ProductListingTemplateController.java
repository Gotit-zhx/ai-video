package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductListingTemplateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/product-listing-templates")
public class ProductListingTemplateController {
    private final ProductListingTemplateService service;
    public ProductListingTemplateController(ProductListingTemplateService service) { this.service = service; }
    @GetMapping
    public ApiResponse<List<ProductListingTemplateService.Template>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.list(principal));
    }
    @PutMapping("/{platform}")
    public ApiResponse<ProductListingTemplateService.Template> save(@PathVariable String platform,
            @RequestBody Map<String, Object> body, @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.save(platform, body, principal));
    }
}
