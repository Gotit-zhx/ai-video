package com.yingdao.aicommerce.shop.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shops")
public class ShopController {
    private final ShopAuthorizationService service;

    public ShopController(ShopAuthorizationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ShopAuthorizationService.ShopView>> list(
            @RequestParam(required = false) String platform,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.list(principal, platform));
    }

    @GetMapping("/integration-status")
    public ApiResponse<ShopAuthorizationService.PlatformStatus> integrationStatus() {
        return ApiResponse.ok(service.status());
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<ShopAuthorizationService.ShopView> refresh(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.refresh(id, principal));
    }

    @PostMapping("/{id}/disconnect")
    public ApiResponse<ShopAuthorizationService.ShopView> disconnect(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.disconnect(id, principal));
    }
}
