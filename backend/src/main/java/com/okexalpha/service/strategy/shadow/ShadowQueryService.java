package com.okexalpha.service.strategy.shadow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.okexalpha.client.OkxApiClient;
import com.okexalpha.domain.HealthCheckRow;
import com.okexalpha.domain.PortfolioSnapshotRow;
import com.okexalpha.domain.ShadowOrderRow;
import com.okexalpha.domain.ShadowPositionRow;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.repository.ShadowRepository;

import tools.jackson.databind.JsonNode;

/** 影子订单 / 持仓 / 组合 / 健康度的查询与手动操作（阶段 C / P2、P3 的只读与运维面）。 */
@Service
public class ShadowQueryService {

    private final ShadowRepository repository;
    private final ShadowEngine shadowEngine;
    private final HealthCheckEngine healthCheckEngine;
    private final OkxApiClient client;

    public ShadowQueryService(ShadowRepository repository, ShadowEngine shadowEngine,
            HealthCheckEngine healthCheckEngine, OkxApiClient client) {
        this.repository = repository;
        this.shadowEngine = shadowEngine;
        this.healthCheckEngine = healthCheckEngine;
        this.client = client;
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
        Double price = shadowEngine.lastPrice(pos.instId());
        if (price == null || price <= 0d) {
            price = fetchPrice(pos.instId());
        }
        if (price == null || price <= 0d) {
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
