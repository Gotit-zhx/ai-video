package com.yingdao.aicommerce.workflow.web;

import com.yingdao.aicommerce.workflow.domain.WorkflowJob;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobStatus;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record WorkflowJobResponse(
        String id,
        String jobNo,
        WorkflowJobType type,
        WorkflowJobStatus status,
        String title,
        Map<String, Object> inputPayload,
        BigDecimal budgetLimit,
        BigDecimal estimatedCost,
        BigDecimal actualCost,
        String errorMessage,
        String createdBy,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static WorkflowJobResponse from(WorkflowJob job) {
        return new WorkflowJobResponse(
                job.getId(),
                job.getJobNo(),
                job.getType(),
                job.getStatus(),
                job.getTitle(),
                job.getInputPayload(),
                job.getBudgetLimit(),
                job.getEstimatedCost(),
                job.getActualCost(),
                job.getErrorMessage(),
                job.getCreatedBy(),
                job.getVersion(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
