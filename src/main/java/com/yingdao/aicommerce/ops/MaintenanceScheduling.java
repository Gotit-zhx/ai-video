package com.yingdao.aicommerce.ops;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Preserve the default one-thread Spring scheduler, gate new scheduled work. */
@Configuration
public class MaintenanceScheduling {
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("heyi-scheduled-");
        scheduler.setTaskDecorator(task -> () -> MaintenanceGate.runIfOpen(task));
        return scheduler;
    }
}
