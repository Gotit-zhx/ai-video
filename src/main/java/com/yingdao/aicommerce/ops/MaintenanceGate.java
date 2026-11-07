package com.yingdao.aicommerce.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Host-owned gate. It never changes durable schedules, leases or checkpoints. */
public final class MaintenanceGate {
    private static final MaintenanceGate INSTANCE = new MaintenanceGate(Path.of(
            System.getenv().getOrDefault("HEYI_MAINTENANCE_FILE", "/run/heyi-ops/maintenance.json")));
    private final Path marker;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger workers = new AtomicInteger();

    MaintenanceGate(Path marker) { this.marker = marker; }
    public static MaintenanceGate instance() { return INSTANCE; }
    public boolean closed() { return Files.exists(marker); }
    public int activeRequests() { return requests.get(); }
    public int activeWorkers() { return workers.get(); }

    public synchronized Permit enter(boolean worker) {
        if (closed()) return null;
        AtomicInteger counter = worker ? workers : requests;
        counter.incrementAndGet();
        return new Permit(counter);
    }

    public static void runIfOpen(Runnable task) {
        try (Permit permit = INSTANCE.enter(true)) {
            if (permit != null) task.run();
        }
    }

    /** Accepted work may finish after the gate closes. Count before enqueueing,
     * including queued futures and work whose caller has already disconnected. */
    public static TrackedTask accepted(Runnable task) { return new TrackedTask(task); }

    public static void execute(Executor executor,Runnable task) {
        TrackedTask tracked=accepted(task);
        try { executor.execute(tracked); }
        catch (RuntimeException | Error rejected) { tracked.cancelIfNotStarted(); throw rejected; }
    }

    public static void initialize(ThreadPoolTaskExecutor executor) {
        executor.setTaskDecorator(MaintenanceGate::accepted);
        executor.initialize();
        var pool=executor.getThreadPoolExecutor();
        var original=pool.getRejectedExecutionHandler();
        if (!(original instanceof ThreadPoolExecutor.AbortPolicy || original instanceof ThreadPoolExecutor.CallerRunsPolicy))
            throw new IllegalStateException("Maintenance tracking requires an explicit rejection policy");
        java.util.concurrent.RejectedExecutionHandler guarded=(task,owner)->{
            try { original.rejectedExecution(task,owner); }
            finally { if(task instanceof TrackedTask tracked)tracked.cancelIfNotStarted(); }
        };
        executor.setRejectedExecutionHandler(guarded); // retained if Spring initializes the bean again
        pool.setRejectedExecutionHandler(guarded);
    }

    public static final class TrackedTask implements Runnable {
        private final Runnable task;
        private final Permit permit;
        private boolean started;
        private boolean cancelled;
        private TrackedTask(Runnable task) {
            this.task=task;INSTANCE.workers.incrementAndGet();permit=new Permit(INSTANCE.workers);
        }
        @Override public void run() {
            synchronized(this) { if(cancelled || started)return;started=true; }
            try { task.run(); } finally { permit.close(); }
        }
        synchronized void cancelIfNotStarted() {
            if(!started) { cancelled=true;permit.close(); }
        }
        public void start(Thread thread) {
            try { thread.start(); }
            catch(RuntimeException | Error failure) { cancelIfNotStarted();throw failure; }
        }
    }

    public static final class Permit implements AutoCloseable {
        private final AtomicInteger counter;
        private boolean finished;
        private Permit(AtomicInteger counter) { this.counter = counter; }
        @Override public synchronized void close() {
            if (!finished) { counter.decrementAndGet(); finished = true; }
        }
    }
}
