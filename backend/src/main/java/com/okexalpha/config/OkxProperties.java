package com.okexalpha.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * okx.* 配置。全部以 record 做构造器绑定（application.yml 中每个键都有默认值）。
 */
@ConfigurationProperties(prefix = "okx")
public record OkxProperties(
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        String userAgent,
        Retry retry,
        RateLimit rateLimit,
        Backfill backfill,
        Sync sync,
        Schedule schedule,
        ApiLog apiLog,
        Db db) {

    public record Retry(int maxAttempts, long baseBackoffMs) {
    }

    public record RateLimit(boolean enabled, int instrumentsPer2s, int candlesPer2s, int fundingPer2s, int rubikPer2s) {
    }

    public record Backfill(int workerThreads, int queueCapacity, int pageLimit, boolean autoResume, int maxTasksPerJob) {
    }

    public record Sync(boolean includeOptions) {
    }

    public record Schedule(boolean enabled) {
    }

    public record ApiLog(boolean enabled) {
    }

    public record Db(boolean autoInit) {
    }

    /** 单测/工具用默认配置。 */
    public static OkxProperties defaults() {
        return new OkxProperties(
                "https://www.okx.com", 10000, 20000, "OkexAlpha/0.1 (+https://github.com/springsky1/OkexAlpha)",
                new Retry(3, 500),
                new RateLimit(true, 16, 16, 10, 4),
                new Backfill(8, 64, 300, true, 5000),
                new Sync(true),
                new Schedule(false),
                new ApiLog(true),
                new Db(true));
    }
}
