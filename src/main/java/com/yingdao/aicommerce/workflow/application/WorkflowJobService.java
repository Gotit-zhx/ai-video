package com.yingdao.aicommerce.workflow.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.task.application.ActiveUserTaskService;
import com.yingdao.aicommerce.workflow.domain.WorkflowJob;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobStatus;
import com.yingdao.aicommerce.workflow.mapper.WorkflowJobMapper;
import com.yingdao.aicommerce.workflow.web.CreateWorkflowJobRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class WorkflowJobService {

    private static final DateTimeFormatter JOB_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final WorkflowJobMapper mapper;
    private final ActiveUserTaskService activeTaskService;
    private final WorkflowQueuePublisher queuePublisher;
    private final WorkflowEventService eventService;
    private final Clock clock;

    @Autowired
    public WorkflowJobService(
            WorkflowJobMapper mapper,
            ActiveUserTaskService activeTaskService,
            WorkflowQueuePublisher queuePublisher,
            WorkflowEventService eventService
    ) {
        this(
                mapper,
                activeTaskService,
                queuePublisher,
                eventService,
                Clock.systemUTC()
        );
    }

    WorkflowJobService(
            WorkflowJobMapper mapper,
            ActiveUserTaskService activeTaskService,
            WorkflowQueuePublisher queuePublisher,
            WorkflowEventService eventService,
            Clock clock
    ) {
        this.mapper = mapper;
        this.activeTaskService = activeTaskService;
        this.queuePublisher = queuePublisher;
        this.eventService = eventService;
        this.clock = clock;
    }

    @Transactional
    public WorkflowJob create(
            CreateWorkflowJobRequest request,
            PlatformPrincipal principal
    ) {
        String jobNo = "JOB-" + LocalDateTime.now(clock).format(JOB_TIME_FORMAT)
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        WorkflowJob job = new WorkflowJob(
                jobNo,
                principal.organizationId(),
                request.type(),
                request.title(),
                request.inputPayload(),
                request.budgetLimit(),
                principal.userId()
        );
        activeTaskService.acquire(
                principal.organizationId(),
                principal.userId(),
                job.getId(),
                "WORKFLOW",
                Duration.ofHours(12)
        );
        mapper.insert(job);
        eventService.record(job, "CREATED", "任务已创建");
        return job;
    }

    @Transactional(readOnly = true)
    public WorkflowJob get(String id, String organizationId) {
        WorkflowJob job = mapper.selectOne(new LambdaQueryWrapper<WorkflowJob>()
                .eq(WorkflowJob::getId, id)
                .eq(WorkflowJob::getOrganizationId, organizationId));
        if (job == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "Workflow job not found: " + id);
        }
        return job;
    }

    @Transactional(readOnly = true)
    public IPage<WorkflowJob> list(
            String organizationId,
            WorkflowJobStatus status,
            int page,
            int size
    ) {
        Page<WorkflowJob> pageable = new Page<>(page + 1L, Math.min(size, 100));
        LambdaQueryWrapper<WorkflowJob> query = new LambdaQueryWrapper<WorkflowJob>()
                .eq(WorkflowJob::getOrganizationId, organizationId)
                .eq(status != null, WorkflowJob::getStatus, status)
                .orderByDesc(WorkflowJob::getCreatedAt);
        return mapper.selectPage(pageable, query);
    }

    @Transactional
    public WorkflowJob updateStatus(
            String id,
            WorkflowJobStatus targetStatus,
            PlatformPrincipal principal
    ) {
        WorkflowJob job = get(id, principal.organizationId());
        validateTransition(job.getStatus(), targetStatus);
        if (job.getStatus() == WorkflowJobStatus.FAILED
                && targetStatus == WorkflowJobStatus.QUEUED) {
            activeTaskService.acquire(
                    principal.organizationId(),
                    job.getCreatedBy(),
                    job.getId(),
                    "WORKFLOW",
                    Duration.ofHours(12)
            );
        }
        job.transitionTo(targetStatus);
        if (mapper.updateById(job) == 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "Workflow job was updated by another request"
            );
        }
        if (targetStatus == WorkflowJobStatus.QUEUED) {
            queuePublisher.publish(job);
        }
        eventService.record(job, "STATUS_CHANGED", statusMessage(targetStatus));
        if (targetStatus == WorkflowJobStatus.COMPLETED
                || targetStatus == WorkflowJobStatus.FAILED
                || targetStatus == WorkflowJobStatus.CANCELLED) {
            activeTaskService.release(
                    job.getOrganizationId(),
                    job.getCreatedBy(),
                    job.getId()
            );
        }
        return job;
    }

    @Transactional
    public WorkflowJob internalTransition(
            String id,
            String organizationId,
            WorkflowJobStatus expectedStatus,
            WorkflowJobStatus targetStatus,
            String eventType,
            String message
    ) {
        WorkflowJob job = get(id, organizationId);
        if (job.getStatus() != expectedStatus) {
            return null;
        }
        if (targetStatus == WorkflowJobStatus.FAILED) {
            job.fail(message);
        } else {
            job.transitionTo(targetStatus);
        }
        if (mapper.updateById(job) == 0) {
            return null;
        }
        if (targetStatus == WorkflowJobStatus.QUEUED) {
            queuePublisher.publish(job);
        }
        eventService.record(job, eventType, message);
        if (targetStatus == WorkflowJobStatus.COMPLETED
                || targetStatus == WorkflowJobStatus.FAILED
                || targetStatus == WorkflowJobStatus.CANCELLED) {
            activeTaskService.release(
                    job.getOrganizationId(),
                    job.getCreatedBy(),
                    job.getId()
            );
        }
        return job;
    }

    private String statusMessage(WorkflowJobStatus status) {
        return switch (status) {
            case DRAFT -> "任务处于草稿状态";
            case QUEUED -> "任务已进入执行队列";
            case WAITING_EXECUTOR -> "等待对应的内置 Skill 执行器";
            case RUNNING -> "任务正在执行";
            case REVIEW_REQUIRED -> "任务等待人工审核";
            case APPROVED -> "任务已审核通过";
            case COMPLETED -> "任务已完成";
            case FAILED -> "任务执行失败";
            case CANCELLED -> "任务已取消";
        };
    }

    private void validateTransition(WorkflowJobStatus current, WorkflowJobStatus target) {
        if (current == target) {
            return;
        }
        boolean allowed = switch (current) {
            case DRAFT -> target == WorkflowJobStatus.QUEUED
                    || target == WorkflowJobStatus.CANCELLED;
            case QUEUED -> target == WorkflowJobStatus.RUNNING
                    || target == WorkflowJobStatus.WAITING_EXECUTOR
                    || target == WorkflowJobStatus.CANCELLED
                    || target == WorkflowJobStatus.FAILED;
            case WAITING_EXECUTOR -> target == WorkflowJobStatus.QUEUED
                    || target == WorkflowJobStatus.CANCELLED
                    || target == WorkflowJobStatus.FAILED;
            case RUNNING -> target == WorkflowJobStatus.REVIEW_REQUIRED
                    || target == WorkflowJobStatus.QUEUED
                    || target == WorkflowJobStatus.COMPLETED
                    || target == WorkflowJobStatus.FAILED
                    || target == WorkflowJobStatus.CANCELLED;
            case REVIEW_REQUIRED -> target == WorkflowJobStatus.APPROVED
                    || target == WorkflowJobStatus.FAILED
                    || target == WorkflowJobStatus.CANCELLED;
            case APPROVED -> target == WorkflowJobStatus.RUNNING
                    || target == WorkflowJobStatus.COMPLETED
                    || target == WorkflowJobStatus.FAILED;
            case FAILED -> target == WorkflowJobStatus.QUEUED
                    || target == WorkflowJobStatus.CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
        if (!allowed) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "Illegal workflow transition: " + current + " -> " + target
            );
        }
    }
}
