package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceMockupGuideService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/commerce-mockup-guides")
public class CommerceMockupGuideController {
 private final CommerceMockupGuideService service;
 public CommerceMockupGuideController(CommerceMockupGuideService service){this.service=service;}
 @GetMapping public ApiResponse<List<CommerceMockupGuideService.View>> list(@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.list(u));}
 @PostMapping public ApiResponse<CommerceMockupGuideService.View> create(@RequestBody CommerceMockupGuideService.Request request,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.create(request,key,u));}
 @GetMapping("/{id}/image") public ResponseEntity<byte[]> image(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal u){var image=service.image(id,u);return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(Base64.getDecoder().decode(image.dataBase64()));}
}
