package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/commerce-batches/creative-only")
public class CommerceCreativeOnlyBatchController {
 private final CommerceCreativeOnlyBatchService service;
 public CommerceCreativeOnlyBatchController(CommerceCreativeOnlyBatchService service){this.service=service;}
 @PostMapping public ApiResponse<CommerceBatchService.View> create(@RequestBody CommerceCreativeOnlyBatchService.Request request,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.create(request,key,user));}
}
