package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductListingWarehouseService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/product-listing-templates/warehouses")
public class ProductListingWarehouseController {
    private final ProductListingWarehouseService service;
    public ProductListingWarehouseController(ProductListingWarehouseService service){this.service=service;}
    @GetMapping("/shops") public ApiResponse<?> shops(@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.shops(user));}
    @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.get(id,user));}
}
