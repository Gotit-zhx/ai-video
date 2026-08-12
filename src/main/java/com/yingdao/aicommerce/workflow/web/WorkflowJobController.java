package com.yingdao.aicommerce.workflow.web;

import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.common.api.PageResponse;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.workflow.application.WorkflowJobService;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/workflow-jobs")
public class WorkflowJobController {

    private final WorkflowJobService service;

    public WorkflowJobController(WorkflowJobService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkflowJobResponse> create(
            @Valid @RequestBody CreateWorkflowJobRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.created(
                WorkflowJobResponse.from(service.create(request, principal))
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkflowJobResponse> get(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(WorkflowJobResponse.from(
                service.get(id, principal.organizationId())
        ));
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkflowJobResponse>> list(
            @RequestParam(required = false) WorkflowJobStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        var jobs = service.list(
                principal.organizationId(),
                status,
                page,
                size
        );
        return ApiResponse.ok(PageResponse.from(jobs, WorkflowJobResponse::from));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<WorkflowJobResponse> updateStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateWorkflowStatusRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(
                WorkflowJobResponse.from(
                        service.updateStatus(id, request.status(), principal)
                )
        );
    }
}
