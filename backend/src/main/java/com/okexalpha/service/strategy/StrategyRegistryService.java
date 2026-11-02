package com.okexalpha.service.strategy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.okexalpha.domain.ShadowOrderRow;
import com.okexalpha.domain.ShadowPositionRow;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.repository.StrategyRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 策略注册表读模型：账户节点的「当前运行的马丁合约策略 / 历史策略」。
 *
 * <p>纯读：不触发影子引擎 tick、不改 okx_strategy、不发任何交易所请求。运行状态由影子账本
 * （okx_shadow_order / okx_shadow_position）聚合得出，因此与引擎实际行为同源。</p>
 */
@Service
public class StrategyRegistryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final StrategyRepository strategyRepository;
    private final ShadowRepository shadowRepository;
    private final ObjectMapper objectMapper;

    public StrategyRegistryService(StrategyRepository strategyRepository, ShadowRepository shadowRepository,
            ObjectMapper objectMapper) {
        this.strategyRepository = strategyRepository;
        this.shadowRepository = shadowRepository;
        this.objectMapper = objectMapper;
    }

    /** 策略清单。state=running|history|all（默认 all）。 */
    public Map<String, Object> list(String state, String status, String mode, Integer limit, Integer offset) {
        String resolvedState = normalizeState(state);
        int size = clampLimit(limit);
        int skip = offset == null || offset < 0 ? 0 : offset;

        List<StrategyRepository.StrategyRow> rows = strategyRepository.list(resolvedState, status, mode, size, skip);
        long total = strategyRepository.count(resolvedState, status, mode);
        Map<Long, StrategyRepository.StrategyStats> stats = strategyRepository.stats(rows.stream()
                .map(StrategyRepository.StrategyRow::id)
                .toList());

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (StrategyRepository.StrategyRow row : rows) {
            items.add(view(row, stats.getOrDefault(row.id(), StrategyRepository.StrategyStats.EMPTY)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", resolvedState);
        out.put("status", status);
        out.put("mode", mode);
        out.put("total", total);
        out.put("limit", size);
        out.put("offset", skip);
        out.put("runningDefinition", "mode='SHADOW' AND status IN ('CANDIDATE','PROMOTED')（与影子引擎同口径）");
        out.put("items", items);
        return out;
    }

    /** 单个策略详情：基础信息 + 影子聚合 + 最近订单/持仓的计数。 */
    public Map<String, Object> detail(long id) {
        StrategyRepository.StrategyRow row = strategyRepository.find(id)
                .orElseThrow(() -> new NotFoundException("strategy " + id + " not found"));
        StrategyRepository.StrategyStats stats = strategyRepository
                .stats(List.of(id))
                .getOrDefault(id, StrategyRepository.StrategyStats.EMPTY);
        Map<String, Object> out = view(row, stats);
        out.put("recentOrders", orders(id, null, 20, 0).get("items"));
        // 字段名与列表保持一致：openPositions 始终是「开仓数量」计数，持仓明细放 positions 数组。
        out.put("positions", positions(id, "OPEN", 50, 0).get("items"));
        return out;
    }

    public Map<String, Object> orders(long id, String status, Integer limit, Integer offset) {
        requireStrategy(id);
        int size = clampLimit(limit);
        int skip = offset == null || offset < 0 ? 0 : offset;
        List<ShadowOrderRow> rows = shadowRepository.ordersByStrategy(id, status, size, skip);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (ShadowOrderRow order : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", order.id());
            row.put("strategyId", order.strategyId());
            row.put("instId", order.instId());
            row.put("side", order.side());
            row.put("ordType", order.ordType());
            row.put("sz", order.sz());
            row.put("px", order.px());
            row.put("notionalUsd", order.notionalUsd());
            row.put("status", order.status());
            row.put("reason", order.reason());
            row.put("signalTsMs", order.signalTsMs());
            row.put("decidedAt", iso(order.decidedAt()));
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategyId", id);
        out.put("status", status);
        out.put("limit", size);
        out.put("offset", skip);
        out.put("items", items);
        return out;
    }

    public Map<String, Object> positions(long id, String status, Integer limit, Integer offset) {
        requireStrategy(id);
        int size = clampLimit(limit);
        int skip = offset == null || offset < 0 ? 0 : offset;
        List<ShadowPositionRow> rows = shadowRepository.positionsByStrategy(id, status, size, skip);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (ShadowPositionRow position : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", position.id());
            row.put("strategyId", position.strategyId());
            row.put("instId", position.instId());
            row.put("side", position.side());
            row.put("sz", position.sz());
            row.put("entryPx", position.entryPx());
            row.put("markPx", position.markPx());
            row.put("notionalUsd", position.notionalUsd());
            row.put("unrealizedPnl", position.unrealizedPnl());
            row.put("leverage", position.leverage());
            row.put("liqPx", position.liqPx());
            row.put("marginUsd", position.marginUsd());
            row.put("status", position.status());
            row.put("openedAt", iso(position.openedAt()));
            row.put("updatedAt", iso(position.updatedAt()));
            row.put("closedAt", iso(position.closedAt()));
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategyId", id);
        out.put("status", status);
        out.put("limit", size);
        out.put("offset", skip);
        out.put("items", items);
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> view(StrategyRepository.StrategyRow row, StrategyRepository.StrategyStats stats) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.id());
        item.put("name", row.name());
        item.put("status", row.status());
        item.put("mode", row.mode());
        item.put("running", "SHADOW".equalsIgnoreCase(row.mode())
                && ("CANDIDATE".equalsIgnoreCase(row.status()) || "PROMOTED".equalsIgnoreCase(row.status())));
        item.put("capitalPerCoinUsd", row.capitalPerCoinUsd());
        item.put("sourceRunId", row.sourceRunId());
        item.put("note", row.note());
        item.put("params", json(row.paramsJson()));
        item.put("universe", json(row.universeJson()));
        item.put("openPositions", stats.openPositions());
        item.put("closedPositions", stats.closedPositions());
        item.put("openNotionalUsd", stats.openNotionalUsd());
        item.put("unrealizedPnlUsd", stats.unrealizedPnlUsd());
        item.put("orderCount", stats.orderCount());
        item.put("lastOrderAt", iso(stats.lastOrderAt()));
        item.put("createdAt", iso(row.createdAt()));
        item.put("updatedAt", iso(row.updatedAt()));
        return item;
    }

    private void requireStrategy(long id) {
        Optional<StrategyRepository.StrategyRow> row = strategyRepository.find(id);
        if (row.isEmpty()) {
            throw new NotFoundException("strategy " + id + " not found");
        }
    }

    private Object json(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node == null || node.isNull() ? null : node;
        } catch (RuntimeException ex) {
            return raw;
        }
    }

    private static String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return "all";
        }
        String normalized = state.trim().toLowerCase(Locale.ROOT);
        if (!"running".equals(normalized) && !"history".equals(normalized) && !"all".equals(normalized)) {
            throw new IllegalArgumentException("state must be one of running|history|all");
        }
        return normalized;
    }

    private static int clampLimit(Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return Math.min(size, MAX_LIMIT);
    }

    private static String iso(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
