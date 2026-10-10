package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommercePrintReferenceService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/commerce-print-templates/references")
public class CommercePrintReferenceController {
    private final CommercePrintReferenceService service;
    public CommercePrintReferenceController(CommercePrintReferenceService service) { this.service = service; }
    @PostMapping public ApiResponse<CommercePrintReferenceService.View> start(@RequestBody CommercePrintReferenceService.Request request, @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.start(request, user)); }
    @GetMapping("/{id}") public ApiResponse<CommercePrintReferenceService.View> get(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.get(id, user)); }
    @PostMapping("/{id}/retry") public ApiResponse<CommercePrintReferenceService.View> retry(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.retry(id, user)); }
}
