package com.yingdao.aicommerce.workflow.application;

import com.yingdao.aicommerce.workflow.domain.WorkflowJob;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobStatus;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobType;

public interface WorkflowSkillExecutor {

    boolean supports(WorkflowJobType type);

    ExecutionResult execute(
            WorkflowJob job,
            RedisConcurrencyGate concurrencyGate
    ) throws Exception;

    record ExecutionResult(
            WorkflowJobStatus targetStatus,
            String message
    ) {
        public ExecutionResult {
            if (targetStatus != WorkflowJobStatus.REVIEW_REQUIRED
                    && targetStatus != WorkflowJobStatus.COMPLETED) {
                throw new IllegalArgumentException(
                        "Skill executor can only return REVIEW_REQUIRED or COMPLETED"
                );
            }
        }
    }
}
