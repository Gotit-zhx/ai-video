package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductPublishWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/product-captures/{captureId}/publish-workflows")
public class ProductPublishWorkflowController {
    private final ProductPublishWorkflowService service;

    public ProductPublishWorkflowController(ProductPublishWorkflowService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductPublishWorkflowService.WorkflowView>> create(
            @PathVariable String captureId,
            @RequestBody Map<String, Object> request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        ProductPublishWorkflowService.CreateResult result = service.create(
                captureId, request, idempotencyKey, principal);
        ApiResponse<ProductPublishWorkflowService.WorkflowView> response = result.created()
                ? ApiResponse.created(result.workflow()) : ApiResponse.ok(result.workflow());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }

    @GetMapping
    public ApiResponse<List<ProductPublishWorkflowService.WorkflowView>> list(
            @PathVariable String captureId,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.list(captureId, limit, principal));
    }

    @GetMapping("/{workflowId}")
    public ApiResponse<ProductPublishWorkflowService.WorkflowView> get(
            @PathVariable String captureId,
            @PathVariable String workflowId,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.get(captureId, workflowId, principal));
    }
}
