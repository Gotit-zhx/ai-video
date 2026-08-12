package com.yingdao.aicommerce.workflow.web;

import com.yingdao.aicommerce.workflow.domain.WorkflowJobType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public record CreateWorkflowJobRequest(
        @NotNull WorkflowJobType type,
        @NotBlank @Size(max = 200) String title,
        Map<String, Object> inputPayload,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal budgetLimit
) {
}
