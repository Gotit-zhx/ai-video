package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceSampleService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/commerce-samples")
public class CommerceSampleController {
 private final CommerceSampleService service;
 public CommerceSampleController(CommerceSampleService service){this.service=service;}
 @GetMapping public ApiResponse<List<CommerceSampleService.View>> list(@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.list(u));}
 @GetMapping("/{id}/position-check") public ApiResponse<com.yingdao.aicommerce.product.application.CommerceMockupPositionCheck.Report> check(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.checkMockup(id,u));}
 @PostMapping public ApiResponse<CommerceSampleService.View> generate(@RequestBody CommerceSampleService.Request r,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.generate(r,key,u));}
 @GetMapping("/{id}/image") public ResponseEntity<byte[]> image(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal u){var r=service.image(id,u);return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.mimeType())).cacheControl(CacheControl.noStore()).body(Base64.getDecoder().decode(r.dataBase64()));}
}
