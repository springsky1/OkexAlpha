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
        Db db,
        Strategy strategy) {

    public record Retry(int maxAttempts, long baseBackoffMs) {
    }

    /**
     * @param enabled          限流总开关
     * @param instrumentsPer2s /public/instruments 每 2 秒配额
     * @param candlesPer2s     K 线三兄弟（history-candles / history-index-candles / history-mark-price-candles）共享的每 2 秒配额
     * @param fundingPer2s     资金费率相关接口每 2 秒配额
     * @param rubikPer2s       rubik 统计接口每 2 秒配额
     * @param globalPer2s      所有 REST 调用合计的每 2 秒配额（保护 IP 级总配额）
     */
    public record RateLimit(boolean enabled, int instrumentsPer2s, int candlesPer2s, int fundingPer2s, int rubikPer2s,
            int globalPer2s) {
    }

    /**
     * @param workerThreads     单 job 内并发执行的 task 数（也是 job 派发线程池大小）
     * @param queueCapacity     派发队列容量
     * @param pageLimit         每页行数（也是单次上游请求的行数上限）
     * @param autoResume        重启后自动恢复未完成的 job
     * @param maxTasksPerJob    单 job 最多 task 数
     * @param coverageSkip      补拉前先查库内覆盖率，只拉缺失区间；关闭后恢复整段重拉（写入仍幂等，只是白费上游配额）
     * @param coverageRefreshMs 尾部强制重拉 horizon：请求窗口末端距 now 不超过该值时，连同最年轻的已落库 K 线
     *                          一起重拉（OKX 的 K 线要过一会儿才 confirm，值会变）；0 = 只补缺口
     */
    public record Backfill(int workerThreads, int queueCapacity, int pageLimit, boolean autoResume,
            int maxTasksPerJob, boolean coverageSkip, long coverageRefreshMs) {
    }

    public record Sync(boolean includeOptions) {
    }

    public record Schedule(boolean enabled) {
    }

    public record ApiLog(boolean enabled) {
    }

    /**
     * @param autoInit         启动时执行 db/schema.sql 建表
     * @param migrate          启动时应用 db/migrations/*.sql 增量迁移
     * @param relaxSyncCommit  批量补拉写入时 SET LOCAL synchronous_commit = off（免每页等待 WAL fsync）
     * @param commitEveryPages 每 N 页合并成一个事务提交（1 = 每页一事务）
     */
    public record Db(boolean autoInit, boolean migrate, boolean relaxSyncCommit, int commitEveryPages) {

        public Db {
            commitEveryPages = commitEveryPages < 1 ? 1 : commitEveryPages;
        }
    }

    /**
     * 策略（阶段 C）配置。
     *
     * @param enabled 总开关；false 时不注册影子引擎与健康度调度
     */
    public record Strategy(boolean enabled, Shadow shadow, Health health, Backtest backtest, Trading trading) {

        /** @param tickIntervalMs 影子引擎每轮间隔（默认 5s） */
        public record Shadow(long tickIntervalMs) {
        }

        /**
         * @param intervalMs        健康度检查间隔（默认 60s）
         * @param autoExit          触发 FAIL 级规则时是否自动清空影子持仓
         * @param minUtilizationPct 资金利用率下限（低于则 WARN）
         * @param maxUtilizationPct 资金利用率上限（高于则 WARN）
         */
        public record Health(long intervalMs, boolean autoExit, int minUtilizationPct, int maxUtilizationPct) {
        }

        /**
         * @param threads          回测并行线程数
         * @param defaultSampleSize 磁盘抽样随机种子样本数（复现实验用）
         * @param maxCombos        单次回测最多参数组合数
         * @param pagesPerSecond   估算用页吞吐（与实测标定一致）
         */
        public record Backtest(int threads, int defaultSampleSize, int maxCombos, double pagesPerSecond) {
        }

        /**
         * 交易配置。默认 mode=SHADOW（纯本地记账，不发单）；DEMO/REAL 需要密钥 + enabled=true + CONFIRM。
         *
         * @param enabled            是否允许真正下单（SHADOW 下无意义）
         * @param mode               SHADOW / DEMO / REAL
         * @param feeTaker           吃单手续费率
         * @param feeMaker           挂单手续费率
         * @param slippageBps        回测/影子撮合的滑点（基点）
         * @param maxPositions       最大同时持仓数
         * @param capitalPerCoinUsd  每币分配资金（0 = 平均分配）
         * @param maxDailyNotionalUsd 每日批次最大名义金额
         */
        public record Trading(boolean enabled, String mode, String apiKey, String secretKey, String passphrase,
                double feeTaker, double feeMaker, double slippageBps, int maxPositions, double capitalPerCoinUsd,
                double maxDailyNotionalUsd, DailyBatch dailyBatch) {

            /** @param cron 每日批次时间（默认 08:30，默认关闭） */
            public record DailyBatch(boolean enabled, String cron) {
            }
        }
    }

    /** 单测/工具用默认配置。 */
    public static OkxProperties defaults() {
        return new OkxProperties(
                "https://www.okx.com", 10000, 20000, "OkexAlpha/0.1 (+https://github.com/springsky1/OkexAlpha)",
                new Retry(3, 500),
                new RateLimit(true, 16, 16, 8, 4, 30),
                new Backfill(8, 64, 300, true, 5000, true, 86_400_000L),
                new Sync(true),
                new Schedule(false),
                new ApiLog(true),
                new Db(true, true, true, 5),
                new Strategy(true,
                        new Strategy.Shadow(5000L),
                        new Strategy.Health(60000L, true, 20, 60),
                        new Strategy.Backtest(2, 400, 2000, 6.3d),
                        new Strategy.Trading(false, "SHADOW", null, null, null, 0.0005d, 0.0002d, 2d, 50, 0d, 500d,
                                new Strategy.Trading.DailyBatch(false, "0 30 8 * * *"))));
    }
}
