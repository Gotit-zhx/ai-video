package com.yingdao.aicommerce.product.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ProductPublishWorkflowConfig {
    public static final int PRINT_IMAGE_CONCURRENCY = 20;

    @Bean(name = "productPublishWorkflowExecutor")
    public Executor productPublishWorkflowExecutor(
            @Value("${app.product-publish-workflow.concurrency:2}") int configuredConcurrency) {
        int concurrency = Math.max(1, Math.min(configuredConcurrency, 8));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(concurrency * 10);
        executor.setThreadNamePrefix("product-publish-workflow-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        com.yingdao.aicommerce.ops.MaintenanceGate.initialize(executor);
        return executor;
    }

    @Bean(name = "productPrintImageExecutor")
    public ThreadPoolTaskExecutor productPrintImageExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(PRINT_IMAGE_CONCURRENCY);
        executor.setMaxPoolSize(PRINT_IMAGE_CONCURRENCY);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("product-print-image-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        com.yingdao.aicommerce.ops.MaintenanceGate.initialize(executor);
        return executor;
    }
}
