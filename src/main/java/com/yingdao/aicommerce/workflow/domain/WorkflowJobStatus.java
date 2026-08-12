package com.yingdao.aicommerce.workflow.domain;

public enum WorkflowJobStatus {
    DRAFT,
    QUEUED,
    WAITING_EXECUTOR,
    RUNNING,
    REVIEW_REQUIRED,
    APPROVED,
    COMPLETED,
    FAILED,
    CANCELLED
}
