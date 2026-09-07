package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchReviewService;
import com.yingdao.aicommerce.product.domain.CommerceBatchReview;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/commerce-batches/{id}/review")
public class CommerceBatchReviewController {
    private final CommerceBatchReviewService service;
    public CommerceBatchReviewController(CommerceBatchReviewService service){this.service=service;}
    @GetMapping public ApiResponse<CommerceBatchReview> get(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.get(id,u));}
    @PostMapping public ApiResponse<CommerceBatchReview> submit(@PathVariable String id,@RequestBody CommerceBatchReviewService.Request r,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.submit(id,r,u));}
}
