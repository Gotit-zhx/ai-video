package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductListingFulfillmentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/product-listing-templates/fulfillment")
public class ProductListingFulfillmentController {
    private final ProductListingFulfillmentService service;
    public ProductListingFulfillmentController(ProductListingFulfillmentService service) { this.service=service; }
    @GetMapping("/{platform}/{country}")
    public ApiResponse<Map<String,Object>> get(@PathVariable String platform, @PathVariable String country,
            @RequestParam(defaultValue="TARGET") String originCountry,
            @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.get(platform,country,originCountry,user)); }
    @PutMapping("/{platform}/{country}")
    public ApiResponse<Map<String,Object>> save(@PathVariable String platform, @PathVariable String country,
            @RequestBody Map<String,Object> body, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.save(platform,country,body,user));
    }
}
