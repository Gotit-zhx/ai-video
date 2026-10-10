package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchLocalRebuildService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/commerce-batches/{batch}/execution/rebuild-local")
public class CommerceBatchLocalRebuildController {
 private final CommerceBatchLocalRebuildService service;
 public CommerceBatchLocalRebuildController(CommerceBatchLocalRebuildService service){this.service=service;}
 @PostMapping
 public ApiResponse<CommerceBatchLocalRebuildService.Rebuilt> rebuild(@PathVariable String batch,
   @RequestHeader(value="Idempotency-Key",required=false) String key,@AuthenticationPrincipal PlatformPrincipal user){
  return ApiResponse.ok(service.rebuild(batch,key,user));
 }
 @PostMapping("/neckline")
 public ApiResponse<CommerceBatchLocalRebuildService.Rebuilt> repairNeckline(@PathVariable String batch,
   @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal PlatformPrincipal user){
  return ApiResponse.ok(service.repairNeckline(batch,key,user));
 }
}
