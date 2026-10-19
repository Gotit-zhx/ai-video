package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductMainImageTemplateService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/v1/product-main-image-templates")
public class ProductMainImageTemplateController {
    private final ProductMainImageTemplateService service;
    public ProductMainImageTemplateController(ProductMainImageTemplateService service) { this.service = service; }
    @GetMapping public ApiResponse<List<ProductMainImageTemplateService.View>> list(@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.list(user)); }
    @GetMapping("/{id}") public ApiResponse<ProductMainImageTemplateService.View> get(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.get(id, user)); }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProductMainImageTemplateService.View> create(@RequestParam String name, @RequestParam("files") List<MultipartFile> files,
            @RequestHeader("Idempotency-Key") String key, @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.create(name, files, key, user)); }
    @PostMapping("/{id}/retry") public ApiResponse<ProductMainImageTemplateService.View> retry(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.retry(id, user)); }
    @DeleteMapping("/{id}") public ApiResponse<Map<String, Boolean>> archive(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) { service.archive(id, user); return ApiResponse.ok(Map.of("archived", true)); }
    @PatchMapping("/{id}/images/{imageId}")
    public ApiResponse<ProductMainImageTemplateService.View> prompt(@PathVariable String id, @PathVariable String imageId,
            @RequestBody Map<String, String> body, @AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.savePrompt(id, imageId, body.get("prompt"), user)); }
    @GetMapping("/{id}/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable String imageId, @AuthenticationPrincipal PlatformPrincipal user) {
        var image = service.image(id, imageId, user);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType())).cacheControl(CacheControl.noStore()).body(Base64.getDecoder().decode(image.dataBase64()));
    }
}
