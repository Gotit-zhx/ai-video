package com.yingdao.aicommerce.workflow.application;

import com.yingdao.aicommerce.workflow.domain.WorkflowJob;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

@Component
public class WorkflowQueuePublisher {

    static final String STREAM_KEY = "heyi:workflow:dispatch";

    private final StringRedisTemplate redisTemplate;

    public WorkflowQueuePublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(WorkflowJob job) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishNow(job);
                }
            });
            return;
        }
        publishNow(job);
    }

    private void publishNow(WorkflowJob job) {
        redisTemplate.opsForStream().add(STREAM_KEY, Map.of(
                "jobId", job.getId(),
                "organizationId", job.getOrganizationId(),
                "userId", job.getCreatedBy(),
                "jobType", job.getType().name(),
                "status", job.getStatus().name()
        ));
    }
}
