package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceSampleService;
import com.yingdao.aicommerce.product.application.ProductPrintCaptureService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/product-print-capture")
public class ProductPrintCaptureController {
    public record LookupRequest(String url) {}
    public record ExtractRequest(String imageId, boolean confirmed) {}

    private final ProductPrintCaptureService service;

    public ProductPrintCaptureController(ProductPrintCaptureService service) {
        this.service = service;
    }

    @PostMapping("/lookup")
    public ApiResponse<ProductPrintCaptureService.Lookup> lookup(@RequestBody LookupRequest request,
                                                                 @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.lookup(request.url(), user));
    }

    @GetMapping
    public ApiResponse<java.util.List<com.yingdao.aicommerce.product.application.ProductCaptureService.PrintSourceSummary>> recent(
            @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.recent(user));
    }

    @GetMapping("/{captureId}")
    public ApiResponse<ProductPrintCaptureService.Lookup> fromCapture(@PathVariable String captureId,
            @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.fromCapture(captureId, user));
    }

    @GetMapping("/results/{id}/image")
    public ResponseEntity<byte[]> resultImage(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) {
        var image = service.resultImage(id, user);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(java.util.Base64.getDecoder().decode(image.dataBase64()));
    }

    @PostMapping("/{captureId}/extract")
    public ApiResponse<CommerceSampleService.View> extract(@PathVariable String captureId,
                                                            @RequestBody ExtractRequest request,
                                                            @RequestHeader("Idempotency-Key") String key,
                                                            @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.extract(captureId, request.imageId(), request.confirmed(), key, user));
    }
}
