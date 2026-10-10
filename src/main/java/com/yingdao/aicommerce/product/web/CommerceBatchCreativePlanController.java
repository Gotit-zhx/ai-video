package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/commerce-batches/{batch}/creative-plan")
public class CommerceBatchCreativePlanController {
    private final CommerceBatchCreativePlanService service;
    public CommerceBatchCreativePlanController(CommerceBatchCreativePlanService service) { this.service=service; }
    @GetMapping
    public ApiResponse<CommerceBatchCreativePlanService.View> get(@PathVariable String batch,
            @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.get(batch,user)); }
    @PostMapping
    public ApiResponse<CommerceBatchCreativePlanService.View> save(@PathVariable String batch,
            @RequestBody CommerceBatchCreativePlanService.Request request,
            @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.save(batch,request,user)); }
}
