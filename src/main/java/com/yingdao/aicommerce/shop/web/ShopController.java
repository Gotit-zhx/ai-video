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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;

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

    public record RenameRequest(String name) {}

    @PatchMapping("/{id}")
    public ApiResponse<ShopAuthorizationService.ShopView> rename(@PathVariable String id,
            @RequestBody RenameRequest request, @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.rename(id, request.name(), principal));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        service.delete(id, principal);
        return ApiResponse.ok("已删除");
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
