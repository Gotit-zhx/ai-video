package com.yingdao.aicommerce.workflow.application;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Component
@ConditionalOnProperty(
        prefix = "app.dispatch",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class WorkflowStreamConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowStreamConsumer.class);
    private static final String GROUP = "heyi-workflow-workers";

    private final StringRedisTemplate redisTemplate;
    private final WorkflowExecutionCoordinator coordinator;
    private final Executor executor;
    private final String workerId;
    private final int batchSize;

    public WorkflowStreamConsumer(
            StringRedisTemplate redisTemplate,
            WorkflowExecutionCoordinator coordinator,
            @Qualifier("workflowDispatchExecutor") Executor executor,
            @Value("${app.dispatch.worker-id:heyi-app-worker}") String workerId,
            @Value("${app.dispatch.worker-threads:8}") int batchSize
    ) {
        this.redisTemplate = redisTemplate;
        this.coordinator = coordinator;
        this.executor = executor;
        this.workerId = workerId;
        this.batchSize = batchSize;
    }

    @PostConstruct
    void initialize() {
        StreamOperations<String, Object, Object> streams =
                redisTemplate.opsForStream();
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(WorkflowQueuePublisher.STREAM_KEY))) {
            streams.add(
                    WorkflowQueuePublisher.STREAM_KEY,
                    Map.of("_bootstrap", "true")
            );
        }
        try {
            streams.createGroup(
                    WorkflowQueuePublisher.STREAM_KEY,
                    ReadOffset.from("0-0"),
                    GROUP
            );
        } catch (RuntimeException exception) {
            if (!hasBusyGroupCause(exception)) {
                throw exception;
            }
        }
        recoverOwnPending();
    }

    private boolean hasBusyGroupCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Scheduled(fixedDelayString = "${app.dispatch.poll-delay:500}")
    void poll() {
        submit(read(ReadOffset.lastConsumed()));
    }

    private void recoverOwnPending() {
        submit(read(ReadOffset.from("0-0")));
    }

    private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
        List<MapRecord<String, Object, Object>> records =
                redisTemplate.opsForStream().read(
                        Consumer.from(GROUP, workerId),
                        StreamReadOptions.empty().count(batchSize),
                        StreamOffset.create(WorkflowQueuePublisher.STREAM_KEY, offset)
                );
        return records == null ? List.of() : records;
    }

    private void submit(List<MapRecord<String, Object, Object>> records) {
        for (MapRecord<String, Object, Object> record : records) {
            executor.execute(() -> process(record));
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        Object jobId = record.getValue().get("jobId");
        Object organizationId = record.getValue().get("organizationId");
        try {
            if (jobId != null && organizationId != null) {
                coordinator.dispatch(
                        String.valueOf(jobId),
                        String.valueOf(organizationId),
                        record.getId().getValue(),
                        workerId
                );
            }
            redisTemplate.opsForStream().acknowledge(
                    WorkflowQueuePublisher.STREAM_KEY,
                    GROUP,
                    record.getId()
            );
        } catch (Exception exception) {
            log.error("Failed to process workflow stream record {}",
                    record.getId(), exception);
        }
    }
}
