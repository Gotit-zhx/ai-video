package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchPreflightService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class CommerceBatchPreflightController {
    private final CommerceBatchPreflightService service;
    public CommerceBatchPreflightController(CommerceBatchPreflightService service){this.service=service;}
    @GetMapping("/api/v1/commerce-batches/{id}/preflight")
    public ApiResponse<CommerceBatchPreflightService.Report> check(@PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.check(id,user));}
}
