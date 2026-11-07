package com.yingdao.aicommerce.ops;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/** Preserve the default one-thread Spring scheduler, gate new scheduled work. */
@Configuration
public class MaintenanceScheduling {
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        return scheduler(MaintenanceGate.instance());
    }

    static ThreadPoolTaskScheduler scheduler(MaintenanceGate gate) {
        var scheduler = new GatedTaskScheduler(gate);
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("heyi-scheduled-");
        return scheduler;
    }

    private static final class GatedTaskScheduler extends ThreadPoolTaskScheduler {
        private final MaintenanceGate gate;
        private GatedTaskScheduler(MaintenanceGate gate) { this.gate = gate; }

        private Runnable gated(Runnable task) {
            return () -> {
                try (var permit = gate.enter(true)) {
                    if (permit != null) task.run();
                }
            };
        }

        // Gate the business callback, never the executor's ScheduledFuture:
        // its run method must still execute to requeue recurring work.
        @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return super.schedule(gated(task), trigger);
        }
        @Override public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return super.schedule(gated(task), startTime);
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return super.scheduleAtFixedRate(gated(task), startTime, period);
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return super.scheduleAtFixedRate(gated(task), period);
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return super.scheduleWithFixedDelay(gated(task), startTime, delay);
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return super.scheduleWithFixedDelay(gated(task), delay);
        }
    }
}
