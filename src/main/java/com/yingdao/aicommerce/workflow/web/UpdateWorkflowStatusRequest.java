package com.yingdao.aicommerce.workflow.web;

import com.yingdao.aicommerce.workflow.domain.WorkflowJobStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateWorkflowStatusRequest(
        @NotNull WorkflowJobStatus status
) {
}

