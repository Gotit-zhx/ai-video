package com.yingdao.aicommerce.workflow.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.workflow.domain.WorkflowJob;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobStatus;
import com.yingdao.aicommerce.workflow.domain.WorkflowRun;
import com.yingdao.aicommerce.workflow.mapper.WorkflowRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkflowExecutionCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowExecutionCoordinator.class);
    private static final int MAX_ATTEMPTS = 3;

    private final WorkflowJobService jobService;
    private final WorkflowRunMapper runMapper;
    private final WorkflowQueuePublisher queuePublisher;
    private final RedisConcurrencyGate concurrencyGate;
    private final List<WorkflowSkillExecutor> executors;

    public WorkflowExecutionCoordinator(
            WorkflowJobService jobService,
            WorkflowRunMapper runMapper,
            WorkflowQueuePublisher queuePublisher,
            RedisConcurrencyGate concurrencyGate,
            List<WorkflowSkillExecutor> executors
    ) {
        this.jobService = jobService;
        this.runMapper = runMapper;
        this.queuePublisher = queuePublisher;
        this.concurrencyGate = concurrencyGate;
        this.executors = executors;
    }

    public void dispatch(
            String jobId,
            String organizationId,
            String streamRecordId,
            String workerId
    ) {
        WorkflowJob job = jobService.get(jobId, organizationId);
        if (job.getStatus() != WorkflowJobStatus.QUEUED) {
            return;
        }

        WorkflowSkillExecutor executor = executors.stream()
                .filter(candidate -> candidate.supports(job.getType()))
                .findFirst()
                .orElse(null);
        int attempt = nextAttempt(jobId);

        if (executor == null) {
            WorkflowJob waiting = jobService.internalTransition(
                    jobId,
                    organizationId,
                    WorkflowJobStatus.QUEUED,
                    WorkflowJobStatus.WAITING_EXECUTOR,
                    "WAITING_EXECUTOR",
                    "对应工作流的内置 Skill 尚未接入，任务已安全暂停"
            );
            if (waiting != null) {
                WorkflowRun run = new WorkflowRun(
                        jobId,
                        organizationId,
                        job.getCreatedBy(),
                        attempt,
                        workerId,
                        streamRecordId
                );
                run.waitingForExecutor("等待内置 Skill 执行器");
                runMapper.insert(run);
            }
            return;
        }

        RedisConcurrencyGate.Permit permit = concurrencyGate.tryAcquireTask();
        if (permit == null) {
            queuePublisher.publish(job);
            return;
        }

        try (permit) {
            WorkflowJob running = jobService.internalTransition(
                    jobId,
                    organizationId,
                    WorkflowJobStatus.QUEUED,
                    WorkflowJobStatus.RUNNING,
                    "EXECUTION_STARTED",
                    "任务已由 " + workerId + " 开始执行"
            );
            if (running == null) {
                return;
            }

            WorkflowRun run = new WorkflowRun(
                    jobId,
                    organizationId,
                    job.getCreatedBy(),
                    attempt,
                    workerId,
                    streamRecordId
            );
            run.running();
            runMapper.insert(run);
            try {
                WorkflowSkillExecutor.ExecutionResult result =
                        executor.execute(running, concurrencyGate);
                WorkflowJob completed = jobService.internalTransition(
                        jobId,
                        organizationId,
                        WorkflowJobStatus.RUNNING,
                        result.targetStatus(),
                        "EXECUTION_FINISHED",
                        result.message()
                );
                if (completed != null) {
                    run.succeeded();
                    runMapper.updateById(run);
                }
            } catch (Exception exception) {
                handleFailure(running, run, exception);
            }
        }
    }

    private void handleFailure(
            WorkflowJob job,
            WorkflowRun run,
            Exception exception
    ) {
        String message = readableMessage(exception);
        run.failed(message);
        runMapper.updateById(run);
        if (run.getAttempt() < MAX_ATTEMPTS) {
            jobService.internalTransition(
                    job.getId(),
                    job.getOrganizationId(),
                    WorkflowJobStatus.RUNNING,
                    WorkflowJobStatus.QUEUED,
                    "RETRY_SCHEDULED",
                    "执行失败，系统将自动重试（"
                            + run.getAttempt() + "/" + MAX_ATTEMPTS + "）"
            );
        } else {
            jobService.internalTransition(
                    job.getId(),
                    job.getOrganizationId(),
                    WorkflowJobStatus.RUNNING,
                    WorkflowJobStatus.FAILED,
                    "EXECUTION_FAILED",
                    message
            );
        }
        log.warn("Workflow {} attempt {} failed: {}",
                job.getId(), run.getAttempt(), message);
    }

    private int nextAttempt(String jobId) {
        Long count = runMapper.selectCount(
                new LambdaQueryWrapper<WorkflowRun>()
                        .eq(WorkflowRun::getWorkflowJobId, jobId)
        );
        return Math.toIntExact(count + 1);
    }

    private String readableMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "工作流执行失败";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
