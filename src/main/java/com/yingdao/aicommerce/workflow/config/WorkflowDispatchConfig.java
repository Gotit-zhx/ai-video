package com.yingdao.aicommerce.workflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableScheduling
public class WorkflowDispatchConfig {

    @Bean("workflowDispatchExecutor")
    Executor workflowDispatchExecutor(
            @Value("${app.dispatch.worker-threads:8}") int workerThreads
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workerThreads);
        executor.setMaxPoolSize(workerThreads);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("workflow-dispatch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        com.yingdao.aicommerce.ops.MaintenanceGate.initialize(executor);
        return executor;
    }
}
