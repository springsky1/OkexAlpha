package com.okexalpha.service.strategy.shadow;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.okexalpha.client.OkxApiClient;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.MinOrderRow;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.domain.ShadowPositionRow;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.repository.ShadowRepository.ShadowStrategyRow;
import com.okexalpha.strategy.engine.ShadowMath;
import com.okexalpha.strategy.engine.StrategyParams;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 影子引擎（阶段 C / P2）：把已被提升为 CANDIDATE/PROMOTED 的策略放进本地账本跑起来，<b>不发任何真实订单</b>。
 *
 * <p>每轮（默认 5s）：拉一次 SWAP 行情 → 对每个影子策略的 universe 逐币判断开仓/加仓/止盈/止损/强平/超时 →
 * 写 okx_shadow_order + okx_shadow_position → 最后写一条 okx_portfolio_snapshot。</p>
 *
 * <p>另支持「手工复制开仓」（{@link #openManual}）：把一笔真实订单按当前价写进影子账本，strategy_id 为空；
 * 这类持仓在 tick 末尾用 Trading 默认参数复用同一套管理逻辑（止盈/止损/强平/超时/加仓），同样占用 maxPositions 名额。</p>
 *
 * <p>开仓金额口径（R5）：默认取平台允许的<b>最小下单量</b>（okx_min_order.min_sz 张，名义 ≈ minNotionalUsd），
 * 这样每天可以开一批单笔最小仓，避免单个大持仓造成大幅亏损；只有显式给 okx_strategy.capital_per_coin_usd
 * 或 okx.strategy.trading.capital-per-coin-usd 时才按该金额放大。</p>
 */
@Service
public class ShadowEngine {

    private static final Logger log = LoggerFactory.getLogger(ShadowEngine.class);

    private static final String REASON_OPEN = "OPEN";
    private static final String REASON_LAYER = "LAYER";
    /** 手工复制开仓写入的影子订单原因（strategy_id 为空，不与策略开仓混在一起）。 */
    private static final String REASON_COPY_OPEN = "COPY_OPEN";

    /** OKX 合约杠杆上限（手工开仓的校验边界）。 */
    public static final int LEVERAGE_MAX = 125;
    /** 手工开仓的缺省杠杆（与策略参数缺省值一致）。 */
    public static final int DEFAULT_LEVERAGE = 5;

    private final ShadowRepository repository;
    private final OkxApiClient client;
    private final OkxProperties properties;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long lastTickAtMs;
    private volatile long lastTickDurationMs;
    private volatile String lastNote = "尚未运行";
    private volatile Map<String, Double> lastPrices = Map.of();

    public ShadowEngine(ShadowRepository repository, OkxApiClient client, OkxProperties properties,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 调度入口：固定间隔触发（默认 5s），上一轮没跑完就跳过本轮。 */
    @Scheduled(fixedDelayString = "${okx.strategy.shadow.tick-interval-ms:5000}", initialDelayString = "20000")
    public void scheduledTick() {
        tick();
    }

    public long lastTickAtMs() {
        return lastTickAtMs;
    }

    public long lastTickDurationMs() {
        return lastTickDurationMs;
    }

    public String lastNote() {
        return lastNote;
    }

    public Double lastPrice(String instId) {
        return lastPrices.get(instId);
    }

    /** 当前参与影子运行的策略（供健康度检查复用）。 */
    public List<ShadowStrategyRow> activeStrategies() {
        return repository.activeShadowStrategies();
    }

    /** 策略 id → 参数（解析失败的不放进来）。 */
    public Map<Long, StrategyParams> strategyParams() {
        Map<Long, StrategyParams> out = new LinkedHashMap<>();
        for (ShadowStrategyRow strategy : activeStrategies()) {
            try {
                out.put(strategy.id(), paramsOf(strategy));
            } catch (RuntimeException ex) {
                log.warn("策略 {} 参数解析失败：{}", strategy.name(), ex.toString());
            }
        }
        return out;
    }

    /**
     * Trading 默认参数：手工复制开仓（未指定策略时）与手工持仓的管理口径。
     * 层数/步长/止盈止损沿用策略缺省（5 层 / 2.5% / 1.0 / 2% / 25% / 48h），费率与滑点取 okx.strategy.trading。
     */
    public StrategyParams defaultParams() {
        OkxProperties.Strategy.Trading trading = properties.strategy().trading();
        return new StrategyParams(StrategyParams.LONG, 100d, DEFAULT_LEVERAGE, 5, 2.5d, 1.0d, 2.0d, 25.0d, 48,
                trading.feeTaker(), 0.01d, trading.slippageBps());
    }

    /**
     * 解析参数 JSON（字段缺省值与 paramsOf 完全一致）；手工复制开仓要沿用已有策略参数时复用。
     *
     * <p>字段非法（如 stopLossPct 越界）时由 StrategyParams 的校验抛 IllegalArgumentException，调用方转 400。</p>
     */
    public StrategyParams paramsFromJson(String paramsJson) {
        JsonNode node = paramsJson == null ? null : objectMapper.readTree(paramsJson);
        String side = firstText(node, StrategyParams.LONG, "side");
        if (!StrategyParams.LONG.equals(side) && !StrategyParams.SHORT.equals(side)) {
            side = StrategyParams.LONG;
        }
        double margin = firstNumber(node, 100d, "initialMarginUsd");
        return new StrategyParams(side, margin,
                (int) Math.round(firstNumber(node, 5d, "leverage")),
                (int) Math.round(firstNumber(node, 5d, "maxLayers")),
                firstNumber(node, 2.5d, "layerStepPct"),
                firstNumber(node, 1.0d, "layerScale"),
                firstNumber(node, 2.0d, "takeProfitPct"),
                firstNumber(node, 25.0d, "stopLossPct"),
                (int) Math.round(firstNumber(node, 48d, "maxHoldHours")),
                firstNumber(node, 0.0005d, "feeTaker"),
                firstNumber(node, 0.01d, "fundingPer8hPct"),
                firstNumber(node, 2d, "slippageBps"));
    }

    /** 一轮 tick 的结果汇总。 */
    public record TickResult(int strategies, int opened, int closed, int layered, int marked, int skipped,
            long durationMs, String note) {
    }

    /** 执行一轮（手动触发与调度共用）。 */
    public TickResult tick() {
        long start = System.currentTimeMillis();
        if (!properties.strategy().enabled()) {
            lastNote = "okx.strategy.enabled=false，跳过影子引擎";
            return new TickResult(0, 0, 0, 0, 0, 0, 0L, lastNote);
        }
        if (!running.compareAndSet(false, true)) {
            lastNote = "上一轮 tick 尚未结束，跳过";
            return new TickResult(0, 0, 0, 0, 0, 0, 0L, lastNote);
        }
        int strategies = 0;
        int opened = 0;
        int closed = 0;
        int layered = 0;
        int marked = 0;
        int skipped = 0;
        try {
            List<ShadowStrategyRow> active = repository.activeShadowStrategies();
            strategies = active.size();
            // 手工复制开仓的持仓（strategy_id IS NULL）也要盯盘，所以它们存在时同样拉一次行情
            List<ShadowPositionRow> manual = repository.manualOpenPositions();
            Map<String, Double> prices = active.isEmpty() && manual.isEmpty() ? Map.of() : prices();
            lastPrices = prices;
            int maxPositions = Math.max(1, properties.strategy().trading().maxPositions());
            for (ShadowStrategyRow strategy : active) {
                StrategyParams params;
                try {
                    params = paramsOf(strategy);
                } catch (RuntimeException ex) {
                    log.warn("影子策略 {} 参数不可用，跳过：{}", strategy.name(), ex.toString());
                    skipped++;
                    continue;
                }
                boolean longSide = StrategyParams.LONG.equals(params.side());
                List<String> universe = universeOf(strategy);
                Map<String, ShadowPositionRow> byInst = new HashMap<>();
                for (ShadowPositionRow pos : repository.openPositions(strategy.id())) {
                    byInst.put(pos.instId(), pos);
                }
                for (String instId : universe) {
                    Double px = prices.get(instId);
                    if (px == null || px <= 0d) {
                        skipped++;
                        continue;
                    }
                    ShadowPositionRow pos = byInst.get(instId);
                    if (pos == null) {
                        if (repository.countOpenPositions() >= maxPositions) {
                            skipped++;
                            continue;
                        }
                        if (openPosition(strategy, params, instId, px, longSide)) {
                            opened++;
                        } else {
                            skipped++;
                        }
                    } else {
                        Outcome outcome = manage(strategy.id(), params, pos, px, longSide);
                        if (outcome.closed()) {
                            closed++;
                        } else {
                            marked++;
                        }
                        if (outcome.layered()) {
                            layered++;
                        }
                    }
                }
            }
            // 手工持仓：不受策略 universe 限制，统一用 Trading 默认参数管理（同样计入 maxPositions 名额）
            if (!manual.isEmpty()) {
                StrategyParams manualParams = defaultParams();
                for (ShadowPositionRow pos : manual) {
                    Double px = prices.get(pos.instId());
                    if (px == null || px <= 0d) {
                        skipped++;
                        continue;
                    }
                    Outcome outcome = manage(null, manualParams, pos, px, StrategyParams.LONG.equals(pos.side()));
                    if (outcome.closed()) {
                        closed++;
                    } else {
                        marked++;
                    }
                    if (outcome.layered()) {
                        layered++;
                    }
                }
            }
            snapshot(strategies);
            long duration = System.currentTimeMillis() - start;
            lastNote = "strategies=" + strategies + " opened=" + opened + " closed=" + closed + " layered=" + layered
                    + " marked=" + marked + " skipped=" + skipped;
            log.debug("shadow tick {}", lastNote);
            return new TickResult(strategies, opened, closed, layered, marked, skipped, duration, lastNote);
        } catch (RuntimeException ex) {
            lastNote = "影子引擎异常：" + ex;
            log.warn("shadow tick failed", ex);
            return new TickResult(strategies, opened, closed, layered, marked, skipped,
                    System.currentTimeMillis() - start, lastNote);
        } finally {
            lastTickAtMs = System.currentTimeMillis();
            lastTickDurationMs = lastTickAtMs - start;
            running.set(false);
        }
    }

    // ---------------------------------------------------------------- 开仓 / 管理

    /** 手工复制开仓的记账入参（instId/side/sz/px 的校验与取价在 QueryService，这里只负责落账）。 */
    public record ManualOpenSpec(String instId, boolean longSide, Double sz, double px, int leverage, Long strategyId,
            StrategyParams params) {
    }

    /** 手工复制开仓的记账结果（positionId/orderId 便于前端回显）。 */
    public record ManualOpenResult(long positionId, long orderId, String instId, String side, double sz,
            boolean usedMinSz, double entryPx, double notionalUsd, double marginUsd, double liqPx) {
    }

    /**
     * 手工复制开仓：把一笔真实订单（instId/side/sz/px/leverage）按当前价写进影子账本，<b>不向交易所发单</b>。
     *
     * <p>sz 为空取平台最小下单量（okx_min_order.min_sz）；最小下单量快照缺失或不可交易时抛 BadRequest，
     * 提示先刷新最小下单量（POST /api/v1/strategy/min-order/refresh）。</p>
     */
    public ManualOpenResult openManual(ManualOpenSpec spec) {
        MinOrderRow minOrder = repository.findMinOrder(spec.instId());
        if (!usable(minOrder)) {
            throw new BadRequestException("no min-order snapshot for " + spec.instId()
                    + "; refresh it first (POST /api/v1/strategy/min-order/refresh)");
        }
        double perContract = minOrder.notionalPerContractUsd();
        double sz = spec.sz() == null ? minOrder.minSz() : spec.sz();
        if (!(sz > 0d) || !Double.isFinite(sz)) {
            throw new BadRequestException("sz must be > 0");
        }
        boolean usedMinSz = spec.sz() == null;
        boolean longSide = spec.longSide();
        double notional = perContract * sz;
        double slip = spec.params().slippageBps() / 10000d;
        double entry = longSide ? spec.px() * (1d + slip) : spec.px() * (1d - slip);
        double margin = notional / spec.leverage();
        double liq = ShadowMath.liqPrice(entry, longSide, spec.leverage());
        String side = longSide ? StrategyParams.LONG : StrategyParams.SHORT;
        long now = System.currentTimeMillis();
        long orderId = repository.insertOrder(spec.strategyId(), spec.instId(), longSide ? "buy" : "sell", "market", sz,
                entry, notional, "FILLED", REASON_COPY_OPEN, now);
        long positionId = repository.insertPosition(spec.strategyId(), spec.instId(), side, sz, entry, spec.px(),
                notional, 0d, spec.leverage(), liq, margin);
        return new ManualOpenResult(positionId, orderId, spec.instId(), side, sz, usedMinSz, entry, notional, margin,
                liq);
    }

    /** 最小下单量快照是否可用于记账（张数与每张名义都必须为正）。 */
    public static boolean usable(MinOrderRow minOrder) {
        return minOrder != null && minOrder.tradable() && minOrder.minSz() != null && minOrder.minSz() > 0d
                && minOrder.notionalPerContractUsd() != null && minOrder.notionalPerContractUsd() > 0d;
    }

    private boolean openPosition(ShadowStrategyRow strategy, StrategyParams params, String instId, double px,
            boolean longSide) {
        MinOrderRow minOrder = repository.findMinOrder(instId);
        if (!usable(minOrder)) {
            return false;
        }
        double perContract = minOrder.notionalPerContractUsd();
        double sz = minOrder.minSz();
        double notional = perContract * sz;
        double targetNotional = targetNotional(strategy, params);
        if (targetNotional > notional) {
            double lot = minOrder.lotSz() == null || minOrder.lotSz() <= 0d ? sz : minOrder.lotSz();
            double lots = Math.ceil(targetNotional / perContract / lot) * lot;
            sz = Math.max(sz, lots);
            notional = perContract * sz;
        }
        double slip = params.slippageBps() / 10000d;
        double entry = longSide ? px * (1d + slip) : px * (1d - slip);
        double margin = notional / params.leverage();
        double liq = ShadowMath.liqPrice(entry, longSide, params.leverage());
        long now = System.currentTimeMillis();
        repository.insertOrder(strategy.id(), instId, longSide ? "buy" : "sell", "market", sz, entry, notional,
                "FILLED", REASON_OPEN, now);
        repository.insertPosition(strategy.id(), instId, longSide ? StrategyParams.LONG : StrategyParams.SHORT, sz,
                entry, px, notional, 0d, params.leverage(), liq, margin);
        return true;
    }

    /** 目标名义金额：显式资金优先，否则用最小下单量（0 表示不放大）。 */
    private double targetNotional(ShadowStrategyRow strategy, StrategyParams params) {
        double configured = strategy.capitalPerCoinUsd() != null && strategy.capitalPerCoinUsd() > 0d
                ? strategy.capitalPerCoinUsd()
                : properties.strategy().trading().capitalPerCoinUsd();
        if (configured <= 0d) {
            return 0d;
        }
        return configured * Math.max(1, params.leverage());
    }

    private record Outcome(boolean closed, boolean layered) {
    }

    /** strategyId 为空表示手工复制开仓的持仓（按 Trading 默认参数管理）。 */
    private Outcome manage(Long strategyId, StrategyParams params, ShadowPositionRow pos, double px, boolean longSide) {
        double entry = pos.entryPx() == null ? px : pos.entryPx();
        double sz = pos.sz();
        double notional = pos.notionalUsd() == null ? entry * sz : pos.notionalUsd();
        double leverage = pos.leverage() == null ? params.leverage() : pos.leverage();
        double margin = pos.marginUsd() == null ? notional / leverage : pos.marginUsd();
        double liq = pos.liqPx() == null ? ShadowMath.liqPrice(entry, longSide, leverage) : pos.liqPx();
        double slip = params.slippageBps() / 10000d;
        double stop = ShadowMath.stopPrice(entry, longSide, params.stopLossPct(), margin, sz);
        double takeProfit = ShadowMath.takeProfitPrice(entry, longSide, params.takeProfitPct());

        // 1) 强平（逐仓：最多亏掉该仓位保证金）
        if (longSide ? px <= liq : px >= liq) {
            close(strategyId, pos, "LIQUIDATION", px, longSide, slip, true);
            return new Outcome(true, false);
        }
        // 2) 止损
        if (longSide ? px <= stop : px >= stop) {
            close(strategyId, pos, "SL", px, longSide, slip, false);
            return new Outcome(true, false);
        }
        // 3) 逆势加仓（马丁）：自首层入场价的不利幅度达到 layerStepPct 且未达最大层数
        int layers = repository.countLayers(strategyId, pos.instId());
        double adversePct = longSide ? (entry - px) / entry * 100d : (px - entry) / entry * 100d;
        if (layers < params.maxLayers() && adversePct >= params.layerStepPct()) {
            double base = ShadowMath.baseNotional(notional, params.layerScale(), layers);
            double addNotional = ShadowMath.layerNotional(base, params.layerScale(), layers + 1);
            double addPx = longSide ? px * (1d + slip) : px * (1d - slip);
            double addSz = addNotional / addPx;
            if (addSz > 0d && Double.isFinite(addSz)) {
                double newSz = sz + addSz;
                double newNotional = notional + addNotional;
                double newAvg = ShadowMath.mergeAverage(notional, entry, addNotional, addPx);
                double newMargin = newNotional / leverage;
                double newLiq = ShadowMath.liqPrice(newAvg, longSide, leverage);
                long now = System.currentTimeMillis();
                repository.insertOrder(strategyId, pos.instId(), longSide ? "buy" : "sell", "market", addSz,
                        addPx, addNotional, "FILLED", REASON_LAYER, now);
                repository.updatePosition(pos.id(), newSz, newAvg, px,
                        ShadowMath.unrealized(newAvg, px, newSz, longSide), newNotional, newMargin, newLiq);
                return new Outcome(false, true);
            }
        }
        // 4) 止盈
        if (longSide ? px >= takeProfit : px <= takeProfit) {
            close(strategyId, pos, "TP", px, longSide, slip, false);
            return new Outcome(true, false);
        }
        // 5) 超时
        if (pos.openedAt() != null) {
            long heldHours = Duration.between(pos.openedAt(), OffsetDateTime.now()).toHours();
            if (heldHours >= params.maxHoldHours()) {
                close(strategyId, pos, "TIMEOUT", px, longSide, slip, false);
                return new Outcome(true, false);
            }
        }
        repository.markPosition(pos.id(), px, ShadowMath.unrealized(entry, px, sz, longSide));
        return new Outcome(false, false);
    }

    private void close(Long strategyId, ShadowPositionRow pos, String reason, double px, boolean longSide,
            double slip, boolean liquidated) {
        double exit = longSide ? px * (1d - slip) : px * (1d + slip);
        double pnl = liquidated
                ? -Math.max(0d, pos.marginUsd() == null ? 0d : pos.marginUsd())
                : ShadowMath.unrealized(pos.entryPx() == null ? exit : pos.entryPx(), exit, pos.sz(), longSide);
        long now = System.currentTimeMillis();
        repository.insertOrder(strategyId, pos.instId(), longSide ? "sell" : "buy", "market", pos.sz(), exit,
                pos.notionalUsd(), "FILLED", reason, now);
        repository.closePosition(pos.id(), exit, pnl);
    }

    // ---------------------------------------------------------------- 行情 / 快照

    /** 一次拿全部 SWAP 最新价（/api/v5/market/tickers?instType=SWAP）。 */
    private Map<String, Double> prices() {
        Map<String, Double> out = new LinkedHashMap<>(600);
        for (JsonNode node : client.getTickers("SWAP")) {
            String instId = text(node, "instId");
            if (instId == null) {
                continue;
            }
            Double px = number(node, "last");
            if (px == null || px <= 0d) {
                px = number(node, "bidPx");
            }
            if (px == null || px <= 0d) {
                px = number(node, "askPx");
            }
            if (px != null && px > 0d) {
                out.put(instId, px);
            }
        }
        return out;
    }

    private void snapshot(int strategies) {
        List<ShadowPositionRow> open = repository.openPositions();
        double gross = 0d;
        double net = 0d;
        double unrealized = 0d;
        double margin = 0d;
        List<Map<String, Object>> detail = new ArrayList<>(open.size());
        for (ShadowPositionRow pos : open) {
            double notional = pos.notionalUsd() == null ? 0d : pos.notionalUsd();
            gross += notional;
            net += StrategyParams.LONG.equals(pos.side()) ? notional : -notional;
            unrealized += pos.unrealizedPnl() == null ? 0d : pos.unrealizedPnl();
            margin += pos.marginUsd() == null ? 0d : pos.marginUsd();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instId", pos.instId());
            item.put("side", pos.side());
            item.put("notionalUsd", notional);
            detail.add(item);
        }
        double equity = margin + unrealized;
        double available = Math.max(0d, margin);
        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(Map.of("strategies", strategies, "positions", detail));
        } catch (RuntimeException ex) {
            detailJson = "{\"strategies\":" + strategies + "}";
        }
        repository.insertPortfolio(System.currentTimeMillis(), equity, available, open.size(), gross, net, unrealized,
                detailJson);
    }

    // ---------------------------------------------------------------- 参数解析

    private StrategyParams paramsOf(ShadowStrategyRow strategy) {
        return paramsFromJson(strategy.paramsJson());
    }

    private List<String> universeOf(ShadowStrategyRow strategy) {
        if (strategy.universeJson() == null || strategy.universeJson().isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(strategy.universeJson());
            JsonNode ids = node == null ? null : node.get("instIds");
            if (ids != null && ids.isArray()) {
                for (JsonNode id : ids) {
                    if (id != null && id.isTextual() && !id.asString().isBlank()) {
                        out.add(id.asString().trim());
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("影子策略 {} 的 universe 解析失败：{}", strategy.name(), ex.toString());
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            double parsed = value.asDouble();
            return Double.isFinite(parsed) ? parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String fallback, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private static double firstNumber(JsonNode node, double fallback, String... fields) {
        for (String field : fields) {
            Double value = number(node, field);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }
}
