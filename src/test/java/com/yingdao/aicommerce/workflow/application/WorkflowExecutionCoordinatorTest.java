package com.yingdao.aicommerce.workflow.application;

import com.yingdao.aicommerce.workflow.domain.WorkflowJob;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobStatus;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobType;
import com.yingdao.aicommerce.workflow.domain.WorkflowRun;
import com.yingdao.aicommerce.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowExecutionCoordinatorTest {

    @Test
    void schedulesRetryWhenExecutorFails() throws Exception {
        WorkflowJobService jobService = mock(WorkflowJobService.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowQueuePublisher publisher = mock(WorkflowQueuePublisher.class);
        RedisConcurrencyGate gate = mock(RedisConcurrencyGate.class);
        RedisConcurrencyGate.Permit permit =
                mock(RedisConcurrencyGate.Permit.class);
        WorkflowSkillExecutor executor = mock(WorkflowSkillExecutor.class);

        WorkflowJob job = new WorkflowJob(
                "JOB-TEST",
                "organization-1",
                WorkflowJobType.IMAGE_TO_VIDEO,
                "重试测试",
                Map.of(),
                BigDecimal.TEN,
                "user-1"
        );
        job.transitionTo(WorkflowJobStatus.QUEUED);

        when(jobService.get(job.getId(), job.getOrganizationId())).thenReturn(job);
        when(executor.supports(WorkflowJobType.IMAGE_TO_VIDEO)).thenReturn(true);
        when(gate.tryAcquireTask()).thenReturn(permit);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(runMapper.insert(any(WorkflowRun.class))).thenReturn(1);
        when(runMapper.updateById(any(WorkflowRun.class))).thenReturn(1);
        when(jobService.internalTransition(
                eq(job.getId()),
                eq(job.getOrganizationId()),
                eq(WorkflowJobStatus.QUEUED),
                eq(WorkflowJobStatus.RUNNING),
                eq("EXECUTION_STARTED"),
                any()
        )).thenAnswer(ignored -> {
            job.transitionTo(WorkflowJobStatus.RUNNING);
            return job;
        });
        when(executor.execute(job, gate))
                .thenThrow(new IllegalStateException("temporary provider failure"));

        WorkflowExecutionCoordinator coordinator =
                new WorkflowExecutionCoordinator(
                        jobService,
                        runMapper,
                        publisher,
                        gate,
                        List.of(executor)
                );

        coordinator.dispatch(
                job.getId(),
                job.getOrganizationId(),
                "1-0",
                "worker-test"
        );

        verify(jobService).internalTransition(
                eq(job.getId()),
                eq(job.getOrganizationId()),
                eq(WorkflowJobStatus.RUNNING),
                eq(WorkflowJobStatus.QUEUED),
                eq("RETRY_SCHEDULED"),
                any()
        );
        verify(permit).close();
    }
}
