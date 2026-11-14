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
import com.okexalpha.strategy.engine.ShadowMath;

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
 *   <li>REAL_ACCOUNT：能读到真实账户权益且 &gt; 0（fail-closed）</li>
 *   <li>AVAILABLE_MARGIN：真实可用保证金 ≥ capital-per-coin-usd</li>
 *   <li>PORTFOLIO_MARGIN：已用（全部影子持仓）+ 本次单笔 ≤ 真实权益 × 95%（与影子开仓同一判据）</li>
 *   <li>REAL_POSITION_LIMIT：真实持仓数 &lt; max-positions</li>
 *   <li>HEALTH：最近一轮体检没有 FAIL</li>
 *   <li>BACKFILL_LOAD：没有正在跑的补拉作业（数据一致性，仅 WARN）</li>
 *   <li>MARKET_DATA：1m K 线不超过 2 小时（仅 WARN）</li>
 *   <li>API_ERROR_RATE：近 10 分钟上游失败率（仅 WARN）</li>
 * </ol>
 */
@Service
public class TradingGate {

    private static final long DAY_MS = 86_400_000L;

    /** 真实账户资金快照超过这个时长仍未刷新 → WARN（说明交易所调用在退化）。 */
    static final long ACCOUNT_STALE_MS = 120_000L;

    private final OkxProperties properties;
    private final OkxCredentials credentials;
    private final ShadowRepository repository;
    private final Clock clock;
    private final RealAccountSource realAccount;
    /** 与影子引擎同一份账户预算（权益取真实账户，已用保证金为全部开放持仓）。 */
    private final AccountBudget accountBudget;

    @Autowired
    public TradingGate(OkxProperties properties, OkxCredentials credentials, ShadowRepository repository,
            RealAccountSource realAccount) {
        this(properties, credentials, repository, Clock.systemUTC(), realAccount);
    }

    public TradingGate(OkxProperties properties, OkxCredentials credentials, ShadowRepository repository, Clock clock,
            RealAccountSource realAccount) {
        this.properties = properties;
        this.credentials = credentials;
        this.repository = repository;
        this.clock = clock;
        this.realAccount = realAccount;
        this.accountBudget = new AccountBudget(realAccount, repository);
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

        // 6) 真实账户：权益 / 可用保证金 / 真实持仓数
        // 旧版只审影子账本，账户实际权益为 0 或可用保证金不足时同样放行 —— 这是最危险的一处缺口。
        RealAccountSource.Funds funds = realAccount == null ? RealAccountSource.Funds.unavailable("门禁未接入真实账户来源")
                : realAccount.funds();
        Map<String, Object> accountMetric = new LinkedHashMap<>();
        accountMetric.put("equityUsd", round2(funds.equityUsd()));
        accountMetric.put("availableUsd", round2(funds.availableUsd()));
        accountMetric.put("positionCount", funds.positionCount());
        accountMetric.put("fetchedAtMs", funds.fetchedAtMs());
        long accountAgeMs = Math.max(0L, clock.millis() - funds.fetchedAtMs());
        accountMetric.put("ageMs", accountAgeMs);
        if (!funds.available()) {
            checks.add(GateCheck.block("REAL_ACCOUNT", "读不到真实账户资金：" + funds.note(), accountMetric));
        } else if (funds.equityUsd() <= 0d) {
            checks.add(GateCheck.block("REAL_ACCOUNT", "真实账户权益 <= 0，无法下单", accountMetric));
        } else if (accountAgeMs > ACCOUNT_STALE_MS) {
            checks.add(GateCheck.warn("REAL_ACCOUNT", "真实账户资金快照已 " + accountAgeMs / 1000L + " 秒未刷新，可能已变化",
                    accountMetric));
        } else {
            checks.add(GateCheck.pass("REAL_ACCOUNT", "真实权益 " + round2(funds.equityUsd()) + " USD，可用 "
                    + round2(funds.availableUsd()) + " USD，持仓 " + funds.positionCount(), accountMetric));
        }

        // 7) 单笔保证金：可用资金必须覆盖 capital-per-coin-usd（影子引擎按这个额度分币种开仓）
        double requiredMargin = trading.capitalPerCoinUsd();
        Map<String, Object> marginMetric = new LinkedHashMap<>();
        marginMetric.put("availableUsd", round2(funds.availableUsd()));
        marginMetric.put("requiredUsd", round2(requiredMargin));
        if (requiredMargin <= 0d) {
            checks.add(GateCheck.block("AVAILABLE_MARGIN",
                    "okx.strategy.trading.capital-per-coin-usd <= 0，无法确定单笔保证金", marginMetric));
        } else if (!funds.available()) {
            checks.add(GateCheck.block("AVAILABLE_MARGIN", "真实可用资金未知，无法核对单笔保证金 " + round2(requiredMargin)
                    + " USD", marginMetric));
        } else if (funds.availableUsd() < requiredMargin) {
            checks.add(GateCheck.block("AVAILABLE_MARGIN", "真实可用 " + round2(funds.availableUsd())
                    + " USD 不足单笔保证金 " + round2(requiredMargin) + " USD", marginMetric));
        } else {
            checks.add(GateCheck.pass("AVAILABLE_MARGIN",
                    "真实可用 " + round2(funds.availableUsd()) + " USD ≥ 单笔 " + round2(requiredMargin) + " USD",
                    marginMetric));
        }

        // 7.5) 共享账户保证金上限：单笔"可用资金够"不等于"账户还有额度"——
        // 影子账本里 4 个币同时满仓时，已用保证金会超过真实权益，而 AVAILABLE_MARGIN 只比了单笔。
        AccountBudget.Budget budget = accountBudget.portfolio();
        Map<String, Object> budgetMetric = new LinkedHashMap<>();
        budgetMetric.put("source", budget.source());
        budgetMetric.put("equityUsd", round2(budget.equityUsd()));
        budgetMetric.put("usedMarginUsd", round2(budget.usedMarginUsd()));
        budgetMetric.put("gateUsd", round2(budget.gateUsd()));
        budgetMetric.put("requiredUsd", round2(requiredMargin));
        if (!(budget.equityUsd() > 0d)) {
            checks.add(GateCheck.block("PORTFOLIO_MARGIN", "拿不到账户权益（真实账户与策略配置都不可用），无法计算共享额度",
                    budgetMetric));
        } else if (!ShadowMath.allowsLayer(budget.equityUsd(), 0d, budget.usedMarginUsd(), requiredMargin)) {
            checks.add(GateCheck.block("PORTFOLIO_MARGIN",
                    "共享账户已用 " + round2(budget.usedMarginUsd()) + "U + 本次 " + round2(requiredMargin)
                            + "U 超过权益 " + round2(budget.equityUsd()) + "U 的 95%（"
                            + round2(budget.gateUsd()) + "U）", budgetMetric));
        } else {
            checks.add(GateCheck.pass("PORTFOLIO_MARGIN",
                    "已用 " + round2(budget.usedMarginUsd()) + "U + 本次 " + round2(requiredMargin) + "U ≤ "
                            + round2(budget.gateUsd()) + "U（权益来源：" + budget.source() + "）", budgetMetric));
        }

        // 8) 真实持仓数上限（影子持仓数之外，再挡一层真实账户的仓位）
        Map<String, Object> realPosMetric = new LinkedHashMap<>();
        realPosMetric.put("realPositions", funds.positionCount());
        realPosMetric.put("maxPositions", maxPositions);
        if (!funds.available()) {
            checks.add(GateCheck.block("REAL_POSITION_LIMIT", "真实持仓数未知，无法核对上限", realPosMetric));
        } else if (maxPositions > 0 && funds.positionCount() >= maxPositions) {
            checks.add(GateCheck.block("REAL_POSITION_LIMIT",
                    "真实持仓 " + funds.positionCount() + "/" + maxPositions + " 已达上限", realPosMetric));
        } else {
            checks.add(GateCheck.pass("REAL_POSITION_LIMIT", "真实持仓 " + funds.positionCount() + "/" + maxPositions,
                    realPosMetric));
        }

        // 9) 健康度
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
