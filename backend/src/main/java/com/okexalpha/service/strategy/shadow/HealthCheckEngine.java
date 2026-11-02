package com.okexalpha.service.strategy.shadow;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.PortfolioSnapshotRow;
import com.okexalpha.domain.ShadowPositionRow;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.repository.ShadowRepository.ShadowStrategyRow;
import com.okexalpha.security.OkxCredentials;
import com.okexalpha.strategy.engine.StrategyParams;

import tools.jackson.databind.ObjectMapper;

/**
 * 健康度检查（阶段 C / P3）：默认每 60s 一轮，每轮每规则写一行 okx_health_check。
 *
 * <p>覆盖用户 R4 明确要求的三件事：</p>
 * <ul>
 *   <li><b>影子订单运行状态是否需要退出</b>：持仓超时（TIMEOUT_RISK）、距止损/强平过近（NEAR_EXIT）；</li>
 *   <li><b>资金利用率</b>：保证金/权益低于 min-utilization-pct 或高于 max-utilization-pct（UTILIZATION）；</li>
 *   <li><b>后期真实订单</b>：mode 不是 SHADOW 时校验开关与密钥是否就绪（REAL_TRADING_GATE）。</li>
 * </ul>
 *
 * <p>另含数据新鲜度、影子引擎心跳、API 失败率、回补作业占用与「有策略但没开影子仓」等工程性规则。
 * 当 {@code okx.strategy.health.auto-exit=true} 且出现 FAIL 级的 NEAR_EXIT 时，会自动平掉影子持仓
 * （只动本地账本，不发真实订单）。</p>
 */
@Service
public class HealthCheckEngine {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckEngine.class);

    public static final String LEVEL_OK = "OK";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_FAIL = "FAIL";

    private final ShadowRepository repository;
    private final ShadowEngine shadowEngine;
    private final OkxProperties properties;
    private final OkxCredentials credentials;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile long lastCheckAtMs;
    private volatile String lastSummary = "尚未运行";
    private int ok;
    private int warn;
    private int fail;

    public HealthCheckEngine(ShadowRepository repository, ShadowEngine shadowEngine, OkxProperties properties,
            OkxCredentials credentials, ObjectMapper objectMapper) {
        this.repository = repository;
        this.shadowEngine = shadowEngine;
        this.properties = properties;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${okx.strategy.health.interval-ms:60000}", initialDelayString = "30000")
    public void scheduledCheck() {
        check();
    }

    public long lastCheckAtMs() {
        return lastCheckAtMs;
    }

    public String lastSummary() {
        return lastSummary;
    }

    /** 一轮检查的结果汇总。 */
    public record CheckResult(int ok, int warn, int fail, int autoExited, long durationMs, String summary) {
    }

    public CheckResult check() {
        long start = System.currentTimeMillis();
        if (!properties.strategy().enabled()) {
            lastSummary = "okx.strategy.enabled=false，跳过健康度检查";
            return new CheckResult(0, 0, 0, 0, 0L, lastSummary);
        }
        if (!running.compareAndSet(false, true)) {
            lastSummary = "上一轮健康度检查尚未结束，跳过";
            return new CheckResult(0, 0, 0, 0, 0L, lastSummary);
        }
        OffsetDateTime batch = OffsetDateTime.now();
        ok = 0;
        warn = 0;
        fail = 0;
        int autoExited = 0;
        try {
            List<ShadowStrategyRow> strategies = shadowEngine.activeStrategies();
            Map<Long, StrategyParams> paramsById = shadowEngine.strategyParams();
            List<ShadowPositionRow> positions = repository.openPositions();
            PortfolioSnapshotRow portfolio = repository.latestPortfolio();

            // 1) 影子引擎心跳
            long tickInterval = Math.max(1000L, properties.strategy().shadow().tickIntervalMs());
            long lastTick = shadowEngine.lastTickAtMs();
            long ageMs = lastTick == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - lastTick;
            String level = lastTick == 0L ? LEVEL_WARN : ageMs > tickInterval * 6 ? LEVEL_FAIL : LEVEL_OK;
            String message = lastTick == 0L ? "影子引擎从未运行（策略数=" + strategies.size() + "）"
                    : "影子引擎最近一轮 " + seconds(ageMs) + " 秒前（间隔 " + tickInterval + "ms）";
            count(record(batch, "SHADOW_SCHEDULER", level, "GLOBAL", null, message,
                    map("ageMs", ageMs, "tickIntervalMs", tickInterval, "strategies", strategies.size())));

            // 2) 行情新鲜度（1m K 线）
            Long latestKline = repository.latestKlineTs("1m");
            long staleMs = latestKline == null ? Long.MAX_VALUE : System.currentTimeMillis() - latestKline;
            String marketLevel = staleMs > 2L * 3_600_000L ? LEVEL_WARN : LEVEL_OK;
            count(record(batch, "MARKET_DATA_FRESH", marketLevel, "GLOBAL", null,
                    latestKline == null ? "库内没有 1m K 线" : "最新 1m K 线 " + seconds(staleMs) + " 秒前",
                    map("staleMs", staleMs, "latestKlineTs", latestKline)));

            // 3) 资金利用率
            if (portfolio == null || portfolio.equityUsd() == null || portfolio.equityUsd() <= 0d) {
                count(record(batch, "UTILIZATION", LEVEL_WARN, "PORTFOLIO", null, "还没有组合快照或权益为 0",
                        map("equityUsd", 0)));
            } else {
                double equity = portfolio.equityUsd();
                double gross = portfolio.grossNotionalUsd() == null ? 0d : portfolio.grossNotionalUsd();
                double margin = 0d;
                for (ShadowPositionRow pos : positions) {
                    margin += pos.marginUsd() == null ? 0d : pos.marginUsd();
                }
                double utilPct = equity <= 0d ? 0d : margin / equity * 100d;
                int min = properties.strategy().health().minUtilizationPct();
                int max = properties.strategy().health().maxUtilizationPct();
                String utilLevel = utilPct < min || utilPct > max ? LEVEL_WARN : LEVEL_OK;
                count(record(batch, "UTILIZATION", utilLevel, "PORTFOLIO", null,
                        String.format(java.util.Locale.ROOT, "资金利用率 %.1f%%（区间 %d%%~%d%%）", utilPct, min, max),
                        map("utilizationPct", utilPct, "marginUsd", margin, "grossNotionalUsd", gross,
                                "equityUsd", equity, "minPct", min, "maxPct", max)));
            }

            // 4) 逐仓退出风险（超时 / 距止损 / 距强平）
            boolean nearExitFail = false;
            for (ShadowPositionRow pos : positions) {
                StrategyParams params = paramsById.get(pos.strategyId() == null ? -1L : pos.strategyId());
                double mark = pos.markPx() == null ? (pos.entryPx() == null ? 0d : pos.entryPx()) : pos.markPx();
                boolean longSide = StrategyParams.LONG.equals(pos.side());
                if (mark <= 0d) {
                    continue;
                }
                double liq = pos.liqPx() == null ? 0d : pos.liqPx();
                double liqDistancePct = liq <= 0d ? 100d : Math.abs(mark - liq) / mark * 100d;
                long heldHours = pos.openedAt() == null ? 0L
                        : Duration.between(pos.openedAt(), OffsetDateTime.now()).toHours();
                int maxHoldHours = params == null ? 48 : params.maxHoldHours();
                List<String> reasons = new ArrayList<>(3);
                String posLevel = LEVEL_OK;
                if (liqDistancePct < 15d) {
                    reasons.add(String.format(java.util.Locale.ROOT, "距强平 %.1f%%", liqDistancePct));
                    posLevel = LEVEL_FAIL;
                    nearExitFail = true;
                }
                if (heldHours >= maxHoldHours) {
                    reasons.add("持仓 " + heldHours + "h ≥ 上限 " + maxHoldHours + "h");
                    if (LEVEL_OK.equals(posLevel)) {
                        posLevel = LEVEL_WARN;
                    }
                }
                if (params != null && pos.entryPx() != null && pos.marginUsd() != null && pos.sz() > 0d) {
                    double stop = com.okexalpha.strategy.engine.ShadowMath.stopPrice(pos.entryPx(), longSide,
                            params.stopLossPct(), pos.marginUsd(), pos.sz());
                    double stopDistancePct = Math.abs(mark - stop) / mark * 100d;
                    if (stopDistancePct < 2d) {
                        reasons.add(String.format(java.util.Locale.ROOT, "距止损 %.1f%%", stopDistancePct));
                        if (!LEVEL_FAIL.equals(posLevel)) {
                            posLevel = LEVEL_WARN;
                        }
                    }
                }
                String posMessage = reasons.isEmpty() ? "正常（无退出信号）" : String.join("；", reasons);
                count(record(batch, "POSITION_EXIT", posLevel, "STRATEGY:" + pos.strategyId(), pos.instId(),
                        posMessage, map("heldHours", heldHours, "maxHoldHours", maxHoldHours,
                                "liqDistancePct", liqDistancePct, "markPx", mark, "side", pos.side())));
            }

            // 5) 资金费逆风（持仓方向与资金费收取方向相反且费率偏大）
            for (ShadowPositionRow pos : positions) {
                Double rate = repository.latestFundingRate(pos.instId());
                if (rate == null) {
                    continue;
                }
                boolean longSide = StrategyParams.LONG.equals(pos.side());
                double headwind = longSide ? rate : -rate;
                String fundLevel = headwind > 0.0005d ? LEVEL_WARN : LEVEL_OK;
                count(record(batch, "FUNDING_HEADWIND", fundLevel, "STRATEGY:" + pos.strategyId(), pos.instId(),
                        String.format(java.util.Locale.ROOT, "资金费率 %.4f%%，持仓方向%s", rate * 100d,
                                headwind > 0d ? "不利（付资金费）" : "有利（收资金费）"),
                        map("fundingRate", rate, "headwind", headwind)));
            }

            // 6) 有策略但没有影子持仓
            String noPosLevel = strategies.isEmpty() ? LEVEL_OK
                    : positions.isEmpty() ? LEVEL_WARN : LEVEL_OK;
            count(record(batch, "NO_SHADOW_POSITIONS", noPosLevel, "GLOBAL", null,
                    strategies.isEmpty() ? "没有 CANDIDATE/PROMOTED 影子策略（先在回测页提升一个）"
                            : positions.isEmpty() ? "有 " + strategies.size() + " 个影子策略但 0 个持仓（检查 universe/最小下单量/行情）"
                                    : "影子持仓 " + positions.size() + " 个",
                    map("strategies", strategies.size(), "positions", positions.size())));

            // 7) 真实下单门禁
            OkxProperties.Strategy.Trading trading = properties.strategy().trading();
            boolean realMode = trading.mode() != null && !"SHADOW".equalsIgnoreCase(trading.mode());
            boolean credentialsReady = credentials.current().ready();
            String gateLevel = realMode && !trading.enabled() ? LEVEL_WARN
                    : realMode && !credentialsReady ? LEVEL_FAIL : LEVEL_OK;
            count(record(batch, "REAL_TRADING_GATE", gateLevel, "GLOBAL", null,
                    realMode ? "mode=" + trading.mode() + "，enabled=" + trading.enabled() + "，密钥来源="
                            + credentials.current().source() + "，" + (credentialsReady ? "齐全" : "缺失")
                            : "mode=SHADOW，仅本地记账（不发真实订单）",
                    map("mode", trading.mode(), "enabled", trading.enabled())));

            // 8) API 失败率（近 10 分钟）
            long[] api = repository.apiOutcomeCounts(10);
            double failPct = api[0] == 0 ? 0d : api[1] * 100d / api[0];
            String apiLevel = failPct > 20d ? LEVEL_WARN : LEVEL_OK;
            count(record(batch, "API_ERROR_RATE", apiLevel, "GLOBAL", null,
                    String.format(java.util.Locale.ROOT, "近 10 分钟 %d 次调用，失败 %d 次（%.1f%%）", api[0], api[1], failPct),
                    map("total", api[0], "failed", api[1], "failPct", failPct)));

            // 9) 回补作业占用（大批补拉期间影子行情可能被限流拖慢）
            long runningBackfills = repository.countRunningBackfills();
            count(record(batch, "BACKFILL_LOAD", runningBackfills > 0 ? LEVEL_WARN : LEVEL_OK, "GLOBAL", null,
                    runningBackfills > 0 ? runningBackfills + " 个补拉作业在跑，行情/下单可能被限流"
                            : "没有运行中的补拉作业",
                    map("runningJobs", runningBackfills)));

            // FAIL 级退出风险 → 按开关自动平仓（只动影子账本）
            if (nearExitFail && properties.strategy().health().autoExit()) {
                autoExited = autoExit(batch);
            }

            long duration = System.currentTimeMillis() - start;
            lastSummary = "ok=" + ok + " warn=" + warn + " fail=" + fail + " autoExited=" + autoExited;
            log.info("health check {} in {}ms", lastSummary, duration);
            return new CheckResult(ok, warn, fail, autoExited, duration, lastSummary);
        } catch (RuntimeException ex) {
            lastSummary = "健康度检查异常：" + ex;
            log.warn("health check failed", ex);
            return new CheckResult(ok, warn, fail, autoExited, System.currentTimeMillis() - start, lastSummary);
        } finally {
            lastCheckAtMs = System.currentTimeMillis();
            running.set(false);
        }

    }

    /** 兼容旧调用点的无操作形参（计数在 record 内完成）。 */
    private void count(Object ignored) {
    }

    private Object record(OffsetDateTime batch, String rule, String level, String scope, String instId,
            String message, Map<String, Object> metric) {
        if (LEVEL_OK.equals(level)) {
            ok++;
        } else if (LEVEL_WARN.equals(level)) {
            warn++;
        } else if (LEVEL_FAIL.equals(level)) {
            fail++;
        }
        String metricJson;
        try {
            metricJson = objectMapper.writeValueAsString(metric);
        } catch (RuntimeException ex) {
            metricJson = null;
        }
        repository.insertHealth(batch, rule, level, scope, instId, message, metricJson);
        return null;
    }

    private int autoExit(OffsetDateTime batch) {
        int closed = 0;
        for (ShadowPositionRow pos : repository.openPositions()) {
            double mark = pos.markPx() == null ? (pos.entryPx() == null ? 0d : pos.entryPx()) : pos.markPx();
            if (mark <= 0d) {
                continue;
            }
            repository.insertOrder(pos.strategyId(), pos.instId(), StrategyParams.LONG.equals(pos.side()) ? "sell" : "buy",
                    "market", pos.sz(), mark, pos.notionalUsd(), "FILLED", "AUTO_EXIT", System.currentTimeMillis());
            repository.closePosition(pos.id(), mark, pos.unrealizedPnl() == null ? 0d : pos.unrealizedPnl());
            count(record(batch, "AUTO_EXIT", LEVEL_WARN, "STRATEGY:" + pos.strategyId(), pos.instId(),
                    "自动平仓（距强平过近）", map("markPx", mark)));
            closed++;
        }
        return closed;
    }

    private static long seconds(long millis) {
        return millis == Long.MAX_VALUE ? -1L : millis / 1000L;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }
}
