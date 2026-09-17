package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchDetectionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/commerce-batches/{batch}/detection")
public class CommerceBatchDetectionController {
 private final CommerceBatchDetectionService service;
 public CommerceBatchDetectionController(CommerceBatchDetectionService service){this.service=service;}
 @GetMapping public ApiResponse<CommerceBatchDetectionService.Report> get(@PathVariable String batch,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.check(batch,user));}
}
