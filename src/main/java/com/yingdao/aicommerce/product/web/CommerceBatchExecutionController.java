package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchExecutionService;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/commerce-batches/{batch}/execution") public class CommerceBatchExecutionController {
 public record Start(boolean confirmed,int maxModelCalls,int creativePlanVersion){}
 private final CommerceBatchExecutionService service;
 public CommerceBatchExecutionController(CommerceBatchExecutionService service){this.service=service;}
 @GetMapping public ApiResponse<CommerceBatchExecution> get(@PathVariable String batch,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.get(batch,user));}
 @GetMapping("/limits") public ApiResponse<com.yingdao.aicommerce.product.application.CommerceBatchModelLimitService.Limit> limits(@PathVariable String batch,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.limit(batch,user));}
 @PostMapping public ApiResponse<CommerceBatchExecution> start(@PathVariable String batch,@RequestBody Start request,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.start(batch,request.confirmed(),request.maxModelCalls(),request.creativePlanVersion(),user));}
 @PostMapping("/{action}") public ApiResponse<CommerceBatchExecution> control(@PathVariable String batch,@PathVariable String action,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.control(batch,action,user));}
}
