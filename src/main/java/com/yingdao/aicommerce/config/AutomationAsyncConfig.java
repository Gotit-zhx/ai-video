package com.yingdao.aicommerce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class AutomationAsyncConfig {
    private static final int AI_VIDEO_IMAGE_CONCURRENCY = 500;
    private static final int AI_VIDEO_COORDINATOR_CONCURRENCY = 100;
    private static final int AI_VIDEO_GENERATION_CONCURRENCY = 500;
    private static final int AI_VIDEO_TASK_QUEUE_CAPACITY = 10_000;

    @Bean(name = "automationDiagnosisExecutor")
    public Executor automationDiagnosisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("automation-model-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "aiVideoImageExecutor")
    public Executor aiVideoImageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(AI_VIDEO_IMAGE_CONCURRENCY);
        executor.setMaxPoolSize(AI_VIDEO_IMAGE_CONCURRENCY);
        executor.setQueueCapacity(AI_VIDEO_TASK_QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("ai-video-image-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "aiVideoImageCoordinatorExecutor")
    public Executor aiVideoImageCoordinatorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(AI_VIDEO_COORDINATOR_CONCURRENCY);
        executor.setMaxPoolSize(AI_VIDEO_COORDINATOR_CONCURRENCY);
        executor.setQueueCapacity(AI_VIDEO_TASK_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("ai-video-image-coordinator-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "aiVideoGenerationExecutor")
    public Executor aiVideoGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(AI_VIDEO_GENERATION_CONCURRENCY);
        executor.setMaxPoolSize(AI_VIDEO_GENERATION_CONCURRENCY);
        executor.setQueueCapacity(AI_VIDEO_TASK_QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("ai-video-generate-");
        executor.initialize();
        return executor;
    }
}
