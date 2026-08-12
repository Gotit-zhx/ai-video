package com.yingdao.aicommerce.workflow.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.workflow.domain.WorkflowJob;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobEvent;
import com.yingdao.aicommerce.workflow.mapper.WorkflowJobEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class WorkflowEventService {

    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final WorkflowJobEventMapper mapper;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    public WorkflowEventService(WorkflowJobEventMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public WorkflowJobEvent record(
            WorkflowJob job,
            String eventType,
            String message
    ) {
        WorkflowJobEvent event = new WorkflowJobEvent(
                job.getId(),
                job.getOrganizationId(),
                job.getCreatedBy(),
                eventType,
                job.getStatus(),
                message
        );
        mapper.insert(event);
        publish(event);
        return event;
    }

    @Transactional(readOnly = true)
    public List<WorkflowJobEvent> list(
            String jobId,
            String organizationId,
            long afterId
    ) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowJobEvent>()
                .eq(WorkflowJobEvent::getWorkflowJobId, jobId)
                .eq(WorkflowJobEvent::getOrganizationId, organizationId)
                .gt(WorkflowJobEvent::getId, afterId)
                .orderByAsc(WorkflowJobEvent::getId)
                .last("LIMIT 200"));
    }

    public SseEmitter subscribe(
            String jobId,
            String organizationId,
            long afterId
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        CopyOnWriteArrayList<SseEmitter> jobEmitters =
                emitters.computeIfAbsent(jobId, ignored -> new CopyOnWriteArrayList<>());
        jobEmitters.add(emitter);
        Runnable cleanup = () -> {
            jobEmitters.remove(emitter);
            if (jobEmitters.isEmpty()) {
                emitters.remove(jobId, jobEmitters);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        try {
            for (WorkflowJobEvent event : list(jobId, organizationId, afterId)) {
                send(emitter, event);
            }
            emitter.send(SseEmitter.event().name("ready").data(Map.of("jobId", jobId)));
        } catch (IOException exception) {
            cleanup.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    private void publish(WorkflowJobEvent event) {
        for (SseEmitter emitter : emitters.getOrDefault(
                event.getWorkflowJobId(),
                new CopyOnWriteArrayList<>()
        )) {
            try {
                send(emitter, event);
            } catch (IOException exception) {
                emitter.complete();
            }
        }
    }

    private void send(SseEmitter emitter, WorkflowJobEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.getId()))
                .name("workflow")
                .data(new WorkflowEventResponse(
                        event.getId(),
                        event.getWorkflowJobId(),
                        event.getEventType(),
                        event.getJobStatus(),
                        event.getMessage(),
                        event.getCreatedAt()
                )));
    }

    public record WorkflowEventResponse(
            Long id,
            String jobId,
            String eventType,
            com.yingdao.aicommerce.workflow.domain.WorkflowJobStatus status,
            String message,
            java.time.Instant createdAt
    ) {
    }
}
