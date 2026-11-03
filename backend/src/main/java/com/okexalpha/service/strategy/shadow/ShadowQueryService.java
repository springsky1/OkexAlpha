package com.okexalpha.service.strategy.shadow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.okexalpha.client.OkxApiClient;
import com.okexalpha.domain.HealthCheckRow;
import com.okexalpha.domain.MinOrderRow;
import com.okexalpha.domain.PortfolioSnapshotRow;
import com.okexalpha.domain.ShadowOrderRow;
import com.okexalpha.domain.ShadowPositionRow;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.repository.StrategyRepository;
import com.okexalpha.repository.StrategyRepository.StrategyRow;
import com.okexalpha.strategy.engine.StrategyParams;
import com.okexalpha.web.dto.ShadowOpenRequest;

import tools.jackson.databind.JsonNode;

/** 影子订单 / 持仓 / 组合 / 健康度的查询与手动操作（阶段 C / P2、P3 的只读与运维面）。 */
@Service
public class ShadowQueryService {

    private final ShadowRepository repository;
    private final ShadowEngine shadowEngine;
    private final HealthCheckEngine healthCheckEngine;
    private final OkxApiClient client;
    private final StrategyRepository strategyRepository;

    public ShadowQueryService(ShadowRepository repository, ShadowEngine shadowEngine,
            HealthCheckEngine healthCheckEngine, OkxApiClient client, StrategyRepository strategyRepository) {
        this.repository = repository;
        this.shadowEngine = shadowEngine;
        this.healthCheckEngine = healthCheckEngine;
        this.client = client;
        this.strategyRepository = strategyRepository;
    }

    public List<ShadowPositionRow> positions(String status, int limit, int offset) {
        return repository.positions(status, limit, offset);
    }

    public List<ShadowOrderRow> orders(String status, String instId, int limit, int offset) {
        return repository.orders(status, instId, limit, offset);
    }

    public List<PortfolioSnapshotRow> portfolio(int limit, int offset) {
        return repository.portfolio(limit, offset);
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openPositions", repository.countOpenPositions());
        out.put("strategies", shadowEngine.activeStrategies().size());
        out.put("lastTickAtMs", shadowEngine.lastTickAtMs());
        out.put("lastTickDurationMs", shadowEngine.lastTickDurationMs());
        out.put("lastTickNote", shadowEngine.lastNote());
        out.put("lastHealthCheckAtMs", healthCheckEngine.lastCheckAtMs());
        out.put("lastHealthSummary", healthCheckEngine.lastSummary());
        out.put("latestPortfolio", repository.latestPortfolio());
        out.put("latestHealth", repository.latestHealthChecks());
        return out;
    }

    public ShadowEngine.TickResult tick() {
        return shadowEngine.tick();
    }

    /** 手动平掉一个影子持仓（按最新价，含滑点方向与引擎一致）。 */
    public Map<String, Object> closePosition(long id) {
        ShadowPositionRow pos = repository.findPosition(id);
        if (pos == null) {
            throw new NotFoundException("shadow position not found: " + id);
        }
        if (!"OPEN".equals(pos.status())) {
            throw new com.okexalpha.error.BadRequestException("shadow position " + id + " is not OPEN");
        }
        Double price = enginePrice(pos.instId());
        if (price == null) {
            throw new com.okexalpha.error.BadRequestException("no mark price for " + pos.instId()
                    + "; run POST /api/v1/strategy/shadow/tick first");
        }
        boolean longSide = "long".equals(pos.side());
        repository.insertOrder(pos.strategyId(), pos.instId(), longSide ? "sell" : "buy", "market", pos.sz(), price,
                pos.notionalUsd(), "FILLED", "MANUAL_CLOSE", System.currentTimeMillis());
        double pnl = pos.entryPx() == null ? 0d : (pos.unrealizedPnl() == null ? 0d : pos.unrealizedPnl());
        repository.closePosition(id, price, pnl);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("instId", pos.instId());
        out.put("closed", true);
        out.put("exitPx", price);
        out.put("unrealizedPnl", pnl);
        return out;
    }

    // ---------------------------------------------------------------- 复制开仓

    /**
     * 复制开仓预检：读最小下单量快照 + 最新价，给出建议张数与建议保证金（只读，不写库）。
     *
     * <p>minOrder 缺失或不可交易时返回 tradable=false、minSz=null 与 notice，前端据此提示先刷新。</p>
     */
    public Map<String, Object> openPreflight(String instId) {
        String id = instId == null ? "" : instId.trim();
        if (id.isEmpty()) {
            throw new BadRequestException("instId is required");
        }
        MinOrderRow minOrder = repository.findMinOrder(id);
        boolean tradable = ShadowEngine.usable(minOrder);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instId", id);
        out.put("tradable", tradable);
        out.put("minSz", tradable ? minOrder.minSz() : null);
        out.put("lotSz", minOrder == null ? null : minOrder.lotSz());
        out.put("notionalPerContractUsd", minOrder == null ? null : minOrder.notionalPerContractUsd());
        out.put("minNotionalUsd", minNotional(minOrder));
        out.put("lastPx", enginePrice(id));
        out.put("suggestedSz", tradable ? minOrder.minSz() : null);
        out.put("suggestedMarginUsd", tradable
                ? minOrder.notionalPerContractUsd() * minOrder.minSz() / ShadowEngine.DEFAULT_LEVERAGE
                : null);
        out.put("leverageMax", ShadowEngine.LEVERAGE_MAX);
        if (!tradable) {
            out.put("notice", "该合约还没有可用的最小下单量快照，请先刷新最小下单量"
                    + "（POST /api/v1/strategy/min-order/refresh）");
        }
        return out;
    }

    /**
     * 复制开仓：校验参数 → 取策略参数/交易默认 → 取价 → 由引擎写 FILLED 影子订单（COPY_OPEN）与 OPEN 持仓。
     *
     * <p>整个过程只写本地影子账本，<b>不会向 OKX 发任何订单</b>。</p>
     */
    public Map<String, Object> open(ShadowOpenRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        String instId = request.instId() == null ? "" : request.instId().trim();
        if (instId.isEmpty()) {
            throw new BadRequestException("instId is required");
        }
        String side = request.side() == null ? "" : request.side().trim().toLowerCase(Locale.ROOT);
        if (!StrategyParams.LONG.equals(side) && !StrategyParams.SHORT.equals(side)) {
            throw new BadRequestException("side must be long or short");
        }
        if (request.sz() != null && !(request.sz() > 0d)) {
            throw new BadRequestException("sz must be > 0");
        }
        if (request.px() != null && !(request.px() > 0d)) {
            throw new BadRequestException("px must be > 0");
        }
        StrategyParams base = baseParams(request.strategyId());
        int leverage = request.leverage() != null
                ? request.leverage()
                : (request.params() != null && request.params().leverage() != null
                        ? request.params().leverage()
                        : base.leverage());
        checkLeverage(leverage);
        StrategyParams params = mergeParams(base, request, side, leverage);
        Double px = request.px() != null ? request.px() : enginePrice(instId);
        if (px == null) {
            throw new BadRequestException("no mark price for " + instId
                    + "; run POST /api/v1/strategy/shadow/tick first or pass px");
        }
        ShadowEngine.ManualOpenResult result = shadowEngine.openManual(new ShadowEngine.ManualOpenSpec(instId,
                StrategyParams.LONG.equals(side), request.sz(), px, params.leverage(), request.strategyId(), params));
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("id", result.positionId());
        position.put("strategyId", request.strategyId());
        position.put("instId", result.instId());
        position.put("side", result.side());
        position.put("sz", result.sz());
        position.put("entryPx", result.entryPx());
        position.put("markPx", px);
        position.put("notionalUsd", result.notionalUsd());
        position.put("unrealizedPnl", 0d);
        position.put("leverage", params.leverage());
        position.put("liqPx", result.liqPx());
        position.put("marginUsd", result.marginUsd());
        position.put("status", "OPEN");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("position", position);
        out.put("orderId", result.orderId());
        out.put("usedMinSz", result.usedMinSz());
        out.put("sz", result.sz());
        return out;
    }

    private void checkLeverage(int leverage) {
        if (leverage < 1 || leverage > ShadowEngine.LEVERAGE_MAX) {
            throw new BadRequestException("leverage must be within 1.." + ShadowEngine.LEVERAGE_MAX);
        }
    }

    /** strategyId 非空时必须存在，其 params JSON 作为缺省参数；为空则用 Trading 默认参数。 */
    private StrategyParams baseParams(Long strategyId) {
        if (strategyId == null) {
            return shadowEngine.defaultParams();
        }
        StrategyRow row = strategyRepository.find(strategyId)
                .orElseThrow(() -> new BadRequestException("strategy not found: " + strategyId));
        try {
            return shadowEngine.paramsFromJson(row.paramsJson());
        } catch (RuntimeException ex) {
            throw new BadRequestException("strategy " + strategyId + " params invalid: " + ex.getMessage());
        }
    }

    /** 用请求里的 params 覆盖策略参数（未给的字段沿用 base），方向与杠杆由外层显式给出。 */
    private StrategyParams mergeParams(StrategyParams base, ShadowOpenRequest request, String side, int leverage) {
        ShadowOpenRequest.Params p = request.params() == null ? ShadowOpenRequest.Params.empty() : request.params();
        try {
            return new StrategyParams(side,
                    pickDouble(p.initialMarginUsd(), base.initialMarginUsd()),
                    leverage,
                    pickInt(p.maxLayers(), base.maxLayers()),
                    pickDouble(p.layerStepPct(), base.layerStepPct()),
                    pickDouble(p.layerScale(), base.layerScale()),
                    pickDouble(p.takeProfitPct(), base.takeProfitPct()),
                    pickDouble(p.stopLossPct(), base.stopLossPct()),
                    pickInt(p.maxHoldHours(), base.maxHoldHours()),
                    pickDouble(p.feeTaker(), base.feeTaker()),
                    pickDouble(p.fundingPer8hPct(), base.fundingPer8hPct()),
                    pickDouble(p.slippageBps(), base.slippageBps()));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("invalid params: " + ex.getMessage());
        }
    }

    private static double pickDouble(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static int pickInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static Double minNotional(MinOrderRow minOrder) {
        if (minOrder == null) {
            return null;
        }
        if (minOrder.minNotionalUsd() != null) {
            return minOrder.minNotionalUsd();
        }
        if (minOrder.notionalPerContractUsd() != null && minOrder.minSz() != null) {
            return minOrder.notionalPerContractUsd() * minOrder.minSz();
        }
        return null;
    }

    /** 最新价：先用上一轮 tick 缓存的行情，再回退到 /tickers 现查。 */
    private Double enginePrice(String instId) {
        Double price = shadowEngine.lastPrice(instId);
        if (price == null || price <= 0d) {
            price = fetchPrice(instId);
        }
        return price == null || price <= 0d ? null : price;
    }

    private Double fetchPrice(String instId) {
        for (JsonNode node : client.getTickers("SWAP")) {
            JsonNode id = node.get("instId");
            if (id == null || !instId.equals(id.asString())) {
                continue;
            }
            for (String field : new String[] {"last", "bidPx", "askPx"}) {
                JsonNode v = node.get(field);
                if (v != null && !v.isNull()) {
                    double px = v.asDouble();
                    if (px > 0d) {
                        return px;
                    }
                }
            }
        }
        return null;
    }

    public List<HealthCheckRow> healthChecks(String level, int limit, int offset) {
        return repository.healthChecks(level, limit, offset);
    }

    public List<HealthCheckRow> latestHealth() {
        return repository.latestHealthChecks();
    }

    public HealthCheckEngine.CheckResult runHealthCheck() {
        return healthCheckEngine.check();
    }
}
