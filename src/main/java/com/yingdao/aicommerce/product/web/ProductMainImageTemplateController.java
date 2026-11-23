package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductCaptureTemplateService;
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
    private final ProductCaptureTemplateService captureTemplates;

    public ProductMainImageTemplateController(ProductMainImageTemplateService service, ProductCaptureTemplateService captureTemplates) {
        this.service = service;
        this.captureTemplates = captureTemplates;
    }

    @GetMapping
    public ApiResponse<List<ProductMainImageTemplateService.View>> list(@RequestParam(required = false) String captureTemplateId,
            @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.list(user, captureTemplates.settingsScope(captureTemplateId, user, false)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductMainImageTemplateService.View> get(@PathVariable String id,
            @RequestParam(required = false) String captureTemplateId, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.get(id, user, captureTemplates.settingsScope(captureTemplateId, user, false)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProductMainImageTemplateService.View> create(@RequestParam String name, @RequestParam("files") List<MultipartFile> files,
            @RequestHeader("Idempotency-Key") String key, @RequestParam(required = false) String captureTemplateId,
            @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.create(name, files, key, user, captureTemplates.settingsScope(captureTemplateId, user, true)));
    }

    @PostMapping("/{id}/copy-to-capture")
    public ApiResponse<ProductMainImageTemplateService.View> copy(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestParam String captureTemplateId,
            @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.copyToCapture(id, key, user, captureTemplates.settingsScope(captureTemplateId, user, true)));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<ProductMainImageTemplateService.View> retry(@PathVariable String id,
            @RequestParam(required = false) String captureTemplateId, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.retry(id, user, captureTemplates.settingsScope(captureTemplateId, user, true)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Boolean>> archive(@PathVariable String id,
            @RequestParam(required = false) String captureTemplateId, @AuthenticationPrincipal PlatformPrincipal user) {
        service.archive(id, user, captureTemplates.settingsScope(captureTemplateId, user, true));
        return ApiResponse.ok(Map.of("archived", true));
    }

    @PatchMapping("/{id}/images/{imageId}")
    public ApiResponse<ProductMainImageTemplateService.View> prompt(@PathVariable String id, @PathVariable String imageId,
            @RequestBody Map<String, String> body, @RequestParam(required = false) String captureTemplateId,
            @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.savePrompt(id, imageId, body.get("prompt"), user,
                captureTemplates.settingsScope(captureTemplateId, user, true)));
    }

    @GetMapping("/{id}/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable String imageId,
            @RequestParam(required = false) String captureTemplateId, @AuthenticationPrincipal PlatformPrincipal user) {
        var image = service.image(id, imageId, user, captureTemplates.settingsScope(captureTemplateId, user, false));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType())).cacheControl(CacheControl.noStore())
                .body(Base64.getDecoder().decode(image.dataBase64()));
    }
}
