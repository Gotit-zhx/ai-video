package com.yingdao.aicommerce.product.application;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

@Component
public class CommercePrintReferenceWorker {
    private static final Logger log = LoggerFactory.getLogger(CommercePrintReferenceWorker.class);
    private static final int CONCURRENCY = 4;
    private record Active(CommercePrintReferenceRepository.Work work, String token) {}
    private final CommercePrintReferenceRepository jobs;
    private final CommercePrintReferenceService service;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String,Active> active = new ConcurrentHashMap<>();
    public CommercePrintReferenceWorker(CommercePrintReferenceRepository jobs, CommercePrintReferenceService service) { this.jobs = jobs; this.service = service; }
    @Scheduled(initialDelay = 3000, fixedDelay = 1000)
    public void dispatch() {
        if (executor.isShutdown()) return;
        int capacity = CONCURRENCY - active.size();
        if (capacity <= 0) return;
        for (var work : jobs.pending(capacity)) {
            var running = new Active(work, UUID.randomUUID().toString());
            if (active.putIfAbsent(work.id(), running) != null) continue;
            try { executor.execute(() -> {
                try { service.process(work, running.token()); }
                catch (RuntimeException error) { log.warn("Print reference task {} interrupted: {}", work.id(), error.getClass().getSimpleName()); }
                finally { active.remove(work.id(), running); }
            }); } catch (RejectedExecutionException error) { active.remove(work.id(), running); }
        }
    }
    @Scheduled(initialDelay = 10000, fixedDelay = 10000)
    public void maintain() {
        for (var running : active.values()) jobs.heartbeat(running.work(), running.token());
        jobs.recoverExpired();
    }
    @PreDestroy public void close() { executor.shutdown(); }
}
