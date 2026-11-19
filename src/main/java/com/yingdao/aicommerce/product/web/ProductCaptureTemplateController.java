package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductCaptureTemplateService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Base64;

@RestController
@RequestMapping("/api/v1/product-capture-templates")
public class ProductCaptureTemplateController {
    private final ProductCaptureTemplateService service;
    public ProductCaptureTemplateController(ProductCaptureTemplateService service) { this.service = service; }
    @GetMapping public ApiResponse<ProductCaptureTemplateService.Page> list(@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="50") int limit, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.list(offset, limit, user));
    }
    @GetMapping("/{id}") public ApiResponse<ProductCaptureTemplateService.View> get(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.get(id, user));
    }
    @PutMapping("/{id}") public ApiResponse<ProductCaptureTemplateService.View> save(@PathVariable String id,
            @RequestBody ProductCaptureTemplateService.Save body, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.save(id, body, user));
    }
    @PostMapping(value="/images", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProductCaptureTemplateService.Image> upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.created(service.upload(file, user));
    }
    @GetMapping("/images/{id}") public ResponseEntity<byte[]> image(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) {
        var value = service.image(id, user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(value.mimeType())).body(Base64.getDecoder().decode(value.dataBase64()));
    }
}
