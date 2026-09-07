package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceTemplateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/commerce-templates")
public class CommerceTemplateController {
    private final CommerceTemplateService service;
    public CommerceTemplateController(CommerceTemplateService service) { this.service=service; }
    @GetMapping
    public ApiResponse<List<CommerceTemplateService.View>> list(@AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.list(user));
    }
    @GetMapping("/{id}/versions")
    public ApiResponse<List<CommerceTemplateService.View>> history(@PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.history(id,user)); }
    @PostMapping
    public ApiResponse<CommerceTemplateService.View> save(@RequestBody CommerceTemplateService.SaveRequest request,
            @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.created(service.save(request,user)); }
}
