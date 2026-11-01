package com.okexalpha.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class BackfillExecutorConfig {

    @Bean(name = "backfillExecutor")
    public ThreadPoolTaskExecutor backfillExecutor(OkxProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.backfill().workerThreads());
        executor.setMaxPoolSize(properties.backfill().workerThreads());
        executor.setQueueCapacity(properties.backfill().queueCapacity());
        executor.setThreadNamePrefix("backfill-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
