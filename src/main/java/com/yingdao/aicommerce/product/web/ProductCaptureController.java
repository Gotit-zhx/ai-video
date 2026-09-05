package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.ExtensionAuthService;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductCaptureService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/product-captures")
public class ProductCaptureController {
    private final ProductCaptureService service;
    private final ExtensionAuthService extensionAuthService;
    private final ObjectMapper objectMapper;

    public ProductCaptureController(ProductCaptureService service,
                                    ExtensionAuthService extensionAuthService,
                                    ObjectMapper objectMapper) {
        this.service = service;
        this.extensionAuthService = extensionAuthService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<ProductCapturePageResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        int requestedSize = size != null ? size : limit != null ? limit : 30;
        return ApiResponse.ok(ProductCapturePageResponse.from(
                service.list(principal.organizationId(), status, query, page, requestedSize)
        ));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductCaptureResponse> create(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.created(ProductCaptureResponse.from(
                service.create(request, idempotencyKey, principal), objectMapper));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductCaptureResponse> get(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(ProductCaptureResponse.from(service.get(id, principal), objectMapper));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ProductCaptureResponse> patch(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(ProductCaptureResponse.from(
                service.patch(id, request, principal), objectMapper));
    }

    @PostMapping("/{id}/images/process")
    public ApiResponse<ImageProcessResponse> processImages(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        ProductCaptureService.ImageProcessResult result = service.processImages(
                id, request, idempotencyKey, principal
        );
        return ApiResponse.ok(new ImageProcessResponse(
                ProductCaptureResponse.ImageJobResponse.from(result.job(), result.dryRun(), objectMapper),
                ProductCaptureResponse.from(result.capture(), objectMapper),
                result.dryRun(), result.provider()
        ));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ProductCaptureResponse> approve(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(ProductCaptureResponse.from(service.approve(id, principal), objectMapper));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<PublishResponse> publish(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        ProductCaptureService.PublishResult result = service.publish(
                id, request, idempotencyKey, principal
        );
        return ApiResponse.ok(new PublishResponse(
                ProductCaptureResponse.PublishAuditResponse.from(result.audit(), objectMapper),
                ProductCaptureResponse.from(result.capture(), objectMapper),
                result.audit().isDryRun(), result.audit().getProvider()
        ));
    }

    @PostMapping("/extension/pairing-code")
    public ApiResponse<ExtensionAuthService.PairingCodeResult> pairingCode(
            @AuthenticationPrincipal PlatformPrincipal principal,
            Authentication authentication
    ) {
        if (authentication != null
                && authentication.getDetails() instanceof ExtensionAuthService.AuthenticatedToken) {
            throw new com.yingdao.aicommerce.common.exception.BusinessException(
                    HttpStatus.FORBIDDEN, "绑定码只能由已登录的平台网页生成"
            );
        }
        return ApiResponse.ok(extensionAuthService.createPairingCode(principal));
    }

    public record ImageProcessResponse(ProductCaptureResponse.ImageJobResponse job,
                                       ProductCaptureResponse capture,
                                       boolean dryRun, String provider) {}

    public record PublishResponse(ProductCaptureResponse.PublishAuditResponse audit,
                                  ProductCaptureResponse capture,
                                  boolean dryRun, String provider) {}
}
