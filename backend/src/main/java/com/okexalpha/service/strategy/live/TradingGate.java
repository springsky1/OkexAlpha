package com.okexalpha.service.strategy.live;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.security.OkxCredentials;

/**
 * 实盘门禁（阶段 C / P4）：把"能不能真的下单"拆成一组可审计的检查。
 *
 * <p>设计原则：默认全部不放行。任何一项 BLOCK 都会让
 * {@link #evaluate()} 返回 liveAllowed=false，调用方必须拒绝下单。</p>
 *
 * <p>检查清单（BLOCK 会阻断，WARN 只提示）：</p>
 * <ol>
 *   <li>MODE：SHADOW 阻断（影子模式不接触交易所）；DEMO/REAL 放行</li>
 *   <li>ENABLED：okx.strategy.trading.enabled 必须为 true</li>
 *   <li>CREDENTIALS：api-key / secret-key / passphrase 三者齐全</li>
 *   <li>POSITION_LIMIT：当前影子持仓数 &lt; max-positions</li>
 *   <li>DAILY_NOTIONAL：当日已成交名义 + 上限配置合理</li>
 *   <li>HEALTH：最近一轮体检没有 FAIL</li>
 *   <li>BACKFILL_LOAD：没有正在跑的补拉作业（数据一致性，仅 WARN）</li>
 *   <li>MARKET_DATA：1m K 线不超过 2 小时（仅 WARN）</li>
 *   <li>API_ERROR_RATE：近 10 分钟上游失败率（仅 WARN）</li>
 * </ol>
 */
@Service
public class TradingGate {

    private static final long DAY_MS = 86_400_000L;

    private final OkxProperties properties;
    private final OkxCredentials credentials;
    private final ShadowRepository repository;
    private final Clock clock;

    @Autowired
    public TradingGate(OkxProperties properties, OkxCredentials credentials, ShadowRepository repository) {
        this(properties, credentials, repository, Clock.systemUTC());
    }

    public TradingGate(OkxProperties properties, OkxCredentials credentials, ShadowRepository repository, Clock clock) {
        this.properties = properties;
        this.credentials = credentials;
        this.repository = repository;
        this.clock = clock;
    }

    public GateReport evaluate() {
        OkxProperties.Strategy.Trading trading = properties.strategy().trading();
        String mode = normalizeMode(trading.mode());
        List<GateCheck> checks = new ArrayList<>();

        // 1) 模式
        if ("SHADOW".equals(mode)) {
            checks.add(GateCheck.block("MODE", "mode=SHADOW：只走本地影子账本，不下真实订单；"
                    + "要实盘请把 okx.strategy.trading.mode 改为 DEMO（模拟盘）或 REAL（实盘）", Map.of("mode", mode)));
        } else if ("DEMO".equals(mode) || "REAL".equals(mode)) {
            checks.add(GateCheck.pass("MODE", "mode=" + mode + ("DEMO".equals(mode) ? "（模拟盘，请求头 x-simulated-trading:1）" : "（实盘）"),
                    Map.of("mode", mode)));
        } else {
            checks.add(GateCheck.block("MODE", "未知 mode=" + mode + "（允许 SHADOW / DEMO / REAL）", Map.of("mode", mode)));
        }

        // 2) 总开关
        if (trading.enabled()) {
            checks.add(GateCheck.pass("ENABLED", "okx.strategy.trading.enabled=true", Map.of("enabled", true)));
        } else {
            checks.add(GateCheck.block("ENABLED", "okx.strategy.trading.enabled=false：交易总开关关闭",
                    Map.of("enabled", false)));
        }

        // 3) 密钥
        OkxCredentials.Credential credential = credentials.current();
        boolean hasApiKey = notBlank(credential.apiKey());
        boolean hasSecret = notBlank(credential.secretKey());
        boolean hasPassphrase = notBlank(credential.passphrase());
        Map<String, Object> credentialMetric = Map.of("apiKey", hasApiKey, "secretKey", hasSecret,
                "passphrase", hasPassphrase);
        if (hasApiKey && hasSecret && hasPassphrase) {
            checks.add(GateCheck.pass("CREDENTIALS", "API 密钥三件套齐全（api-key / secret-key / passphrase）",
                    credentialMetric));
        } else {
            checks.add(GateCheck.block("CREDENTIALS", "缺少 API 密钥：需要配置环境变量 OKX_API_KEY / OKX_SECRET_KEY / "
                    + "OKX_PASSPHRASE，或用 POST /api/v1/account/credentials 加密入库", credentialMetric));
        }

        // 4) 持仓数量
        long openPositions = safeCount(() -> repository.countOpenPositions());
        int maxPositions = trading.maxPositions();
        Map<String, Object> positionMetric = new LinkedHashMap<>();
        positionMetric.put("openPositions", openPositions);
        positionMetric.put("maxPositions", maxPositions);
        if (maxPositions <= 0) {
            checks.add(GateCheck.block("POSITION_LIMIT", "okx.strategy.trading.max-positions <= 0，未设置上限",
                    positionMetric));
        } else if (openPositions >= maxPositions) {
            checks.add(GateCheck.block("POSITION_LIMIT", "影子持仓已达上限 " + openPositions + "/" + maxPositions,
                    positionMetric));
        } else {
            checks.add(GateCheck.pass("POSITION_LIMIT", "影子持仓 " + openPositions + "/" + maxPositions,
                    positionMetric));
        }

        // 5) 每日名义上限
        long dayStartMs = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli();
        double usedToday = safeDouble(() -> repository.filledNotionalSince(dayStartMs));
        double dailyCap = trading.maxDailyNotionalUsd();
        Map<String, Object> notionalMetric = new LinkedHashMap<>();
        notionalMetric.put("usedTodayUsd", round2(usedToday));
        notionalMetric.put("dailyCapUsd", dailyCap);
        notionalMetric.put("dayStartMs", dayStartMs);
        if (dailyCap <= 0d) {
            checks.add(GateCheck.block("DAILY_NOTIONAL", "okx.strategy.trading.max-daily-notional-usd <= 0，未设置每日上限",
                    notionalMetric));
        } else if (usedToday >= dailyCap) {
            checks.add(GateCheck.block("DAILY_NOTIONAL",
                    "当日成交名义 " + round2(usedToday) + " USD 已达上限 " + dailyCap + " USD", notionalMetric));
        } else {
            checks.add(GateCheck.pass("DAILY_NOTIONAL",
                    "当日成交 " + round2(usedToday) + "/" + dailyCap + " USD", notionalMetric));
        }

        // 6) 健康度
        long healthFail = safeCount(() -> repository.latestHealthFailCount());
        if (healthFail > 0) {
            checks.add(GateCheck.block("HEALTH", "最近一轮体检有 " + healthFail + " 条 FAIL，先修完再下单",
                    Map.of("failCount", healthFail)));
        } else {
            checks.add(GateCheck.pass("HEALTH", "最近一轮体检无 FAIL", Map.of("failCount", 0)));
        }

        // 7) 补拉负载（只提示）
        long runningBackfills = safeCount(() -> repository.countRunningBackfills());
        if (runningBackfills > 0) {
            checks.add(GateCheck.warn("BACKFILL_LOAD", "有 " + runningBackfills + " 个补拉作业在跑，数据可能仍在变化",
                    Map.of("runningBackfills", runningBackfills)));
        } else {
            checks.add(GateCheck.pass("BACKFILL_LOAD", "无正在运行的补拉作业", Map.of("runningBackfills", 0)));
        }

        // 8) 行情新鲜度（只提示）
        Long latest1m = null;
        try {
            latest1m = repository.latestKlineTs("1m");
        } catch (RuntimeException ex) {
            latest1m = null;
        }
        long nowMs = clock.millis();
        if (latest1m == null) {
            checks.add(GateCheck.warn("MARKET_DATA", "库里没有 1m K 线，先补拉行情", Map.of("latest1mTs", 0)));
        } else {
            long ageMs = nowMs - latest1m;
            if (ageMs > 2L * 3_600_000L) {
                checks.add(GateCheck.warn("MARKET_DATA", "1m K 线已过期 " + (ageMs / 60_000L) + " 分钟",
                        Map.of("latest1mTs", latest1m, "ageMinutes", ageMs / 60_000L)));
            } else {
                checks.add(GateCheck.pass("MARKET_DATA", "1m K 线在 " + (ageMs / 60_000L) + " 分钟前更新",
                        Map.of("latest1mTs", latest1m, "ageMinutes", ageMs / 60_000L)));
            }
        }

        // 9) 上游失败率（只提示）
        long[] calls = new long[] {0L, 0L};
        try {
            calls = repository.apiOutcomeCounts(10);
        } catch (RuntimeException ex) {
            calls = new long[] {0L, 0L};
        }
        double failurePct = calls[0] == 0 ? 0d : calls[1] * 100d / calls[0];
        Map<String, Object> apiMetric = Map.of("calls", calls[0], "failed", calls[1], "failurePct", round2(failurePct));
        if (calls[0] >= 20 && failurePct > 20d) {
            checks.add(GateCheck.warn("API_ERROR_RATE", "近 10 分钟失败率 " + round2(failurePct) + "%（" + calls[1] + "/"
                    + calls[0] + "）", apiMetric));
        } else {
            checks.add(GateCheck.pass("API_ERROR_RATE", "近 10 分钟 " + calls[0] + " 次调用，失败 " + calls[1] + " 次",
                    apiMetric));
        }

        List<String> blockers = checks.stream().filter(GateCheck::blocking)
                .map(check -> check.id() + ": " + check.message())
                .toList();

        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("maxPositions", maxPositions);
        caps.put("maxDailyNotionalUsd", dailyCap);
        caps.put("usedTodayUsd", round2(usedToday));
        caps.put("capitalPerCoinUsd", trading.capitalPerCoinUsd());
        caps.put("feeTaker", trading.feeTaker());
        caps.put("slippageBps", trading.slippageBps());
        caps.put("evaluatedAt", clock.instant().toString());

        return new GateReport(mode, blockers.isEmpty(), blockers, checks, caps);
    }

    public static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SHADOW";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    static long todayStartMs(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private long safeCount(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    private double safeDouble(java.util.function.DoubleSupplier supplier) {
        try {
            return supplier.getAsDouble();
        } catch (RuntimeException ex) {
            return 0d;
        }
    }

    /** 把门禁报告压平成 Map，便于直接做接口响应。 */
    public static Map<String, Object> toMap(GateReport report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", report.mode());
        out.put("liveAllowed", report.liveAllowed());
        out.put("blockers", report.blockers());
        out.put("checks", report.checks());
        out.put("caps", report.caps());
        return out;
    }
}
