package com.yingdao.aicommerce.shop.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.shop.application.ShopOperatingSettingsService;
import com.yingdao.aicommerce.shop.domain.ShopOperatingSettings;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/shops/{id}/operating-settings")
public class ShopOperatingSettingsController {
    private final ShopOperatingSettingsService service;
    public ShopOperatingSettingsController(ShopOperatingSettingsService service){this.service=service;}
    @GetMapping public ApiResponse<ShopOperatingSettings> get(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.get(id,u));}
    @PutMapping public ApiResponse<ShopOperatingSettings> save(@PathVariable String id,@RequestBody ShopOperatingSettingsService.Request r,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.save(id,r,u));}
}
