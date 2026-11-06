package com.yingdao.aicommerce.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

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

    public static final class Permit implements AutoCloseable {
        private final AtomicInteger counter;
        private boolean finished;
        private Permit(AtomicInteger counter) { this.counter = counter; }
        @Override public synchronized void close() {
            if (!finished) { counter.decrementAndGet(); finished = true; }
        }
    }
}
