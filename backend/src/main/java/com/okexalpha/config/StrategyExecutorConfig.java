package com.okexalpha.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 回测执行线程池：与补拉线程池隔离，避免回测把 HTTP 抓取任务挤掉。
 *
 * <p>回测是纯 CPU + 本地 PG 读，线程数由 {@code okx.strategy.backtest.threads} 控制（默认 2）。</p>
 */
@Configuration
public class StrategyExecutorConfig {

    @Bean(name = "strategyBacktestExecutor")
    public ThreadPoolTaskExecutor strategyBacktestExecutor(OkxProperties properties) {
        int threads = Math.max(1, properties.strategy().backtest().threads());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("backtest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
