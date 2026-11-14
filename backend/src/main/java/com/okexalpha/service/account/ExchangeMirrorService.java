package com.okexalpha.service.account;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.okexalpha.client.OkxApiException;
import com.okexalpha.client.OkxTradeClient;
import com.okexalpha.domain.ExchangeFillRow;
import com.okexalpha.domain.ExchangeOrderRow;
import com.okexalpha.domain.ExchangePositionRow;
import com.okexalpha.repository.ExchangeMirrorRepository;
import com.okexalpha.security.OkxCredentials;

import tools.jackson.databind.JsonNode;

/**
 * 订单中心的「平台真实订单」本地镜像：同步 OKX 的挂单 / 历史委托 / 已平仓仓位 / 逐笔成交，
 * 并给页面提供只读查询与勾选汇总的数据源。
 *
 * <p>约束：</p>
 * <ol>
 *   <li>同步是可重入保护的：同一进程同时只允许一次同步，后到请求返回 skipped，不重复打交易所；</li>
 *   <li>失败不破坏已有镜像：抓取阶段任何异常都折成 error 块，本地表保持原样；</li>
 *   <li>只读交易所接口，不下单、不撤单、不改杠杆。</li>
 * </ol>
 */
@Service
public class ExchangeMirrorService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeMirrorService.class);

    public static final String STATE_ALL = "all";
    public static final String STATE_PENDING = "pending";
    public static final String STATE_HISTORY = "history";

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final int OKX_MAX_LIMIT = 100;

    private final OkxTradeClient tradeClient;
    private final ExchangeMirrorRepository repository;
    private final OkxCredentials okxCredentials;
    private final String keyLabel;
    private final ReentrantLock syncLock = new ReentrantLock();

    public ExchangeMirrorService(OkxTradeClient tradeClient, ExchangeMirrorRepository repository,
            OkxCredentials okxCredentials,
            @Value("${okx.account.key-label:OkexAlpha}") String keyLabel) {
        this.tradeClient = tradeClient;
        this.repository = repository;
        this.okxCredentials = okxCredentials;
        this.keyLabel = keyLabel == null || keyLabel.isBlank() ? "OkexAlpha" : keyLabel.trim();
    }

    // ------------------------------------------------------------------ 同步

    /**
     * 从交易所拉一轮数据并 UPSERT 到镜像库。
     *
     * @param force 目前只用于前端区分「自动同步」与「强制刷新」，同步本身总是拉最新一页
     */
    public Map<String, Object> sync(String instType, Integer limit, boolean force) {
        String type = instTypeOrSwap(instType);
        int size = clampOkxLimit(limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instType", type);
        out.put("limit", size);
        out.put("force", force);

        if (!syncLock.tryLock()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("configured", credentialsReady());
            out.put("notice", "同步正在进行中，请稍后重试。");
            out.put("lastSyncedAt", safeLastSyncedAt());
            return out;
        }
        try {
            if (!credentialsReady()) {
                out.put("ok", false);
                out.put("configured", false);
                out.put("error", errorOf("MISSING_CREDENTIALS", null,
                        "未配置完整凭证，无法从交易所同步订单。"));
                return out;
            }
            out.put("configured", true);

            List<JsonNode> pending = tradeClient.pendingOrders(type);
            List<JsonNode> history = tradeClient.ordersHistory(type, null, size);
            boolean archive = false;
            if (history.isEmpty()) {
                // orders-history 只保留最近 7 天；为空时回落到 3 个月归档，避免「明明有单却同步不到」。
                List<JsonNode> fallback = tradeClient.ordersHistoryArchive(type, null, size);
                if (!fallback.isEmpty()) {
                    history = fallback;
                    archive = true;
                }
            }
            List<JsonNode> positions = tradeClient.positionsHistory(type, size);
            List<JsonNode> fills = tradeClient.fills(type, size);

            List<ExchangeOrderRow> orderRows = new ArrayList<>(history.size() + pending.size());
            for (JsonNode node : history) {
                orderRows.add(orderRow(node, archive));
            }
            // 挂单快照最后覆盖：同一 ordId 若同时出现在历史里，以挂单的实时状态为准（随后再由残留清理校正）。
            for (JsonNode node : pending) {
                orderRows.add(orderRow(node, false));
            }
            List<ExchangePositionRow> positionRows = positionRows(positions);
            List<ExchangeFillRow> fillRows = fillRows(fills);
            repository.upsertOrders(orderRows);
            repository.upsertPositions(positionRows);
            repository.upsertFills(fillRows);

            Set<String> liveOrdIds = new HashSet<>();
            for (JsonNode node : pending) {
                String ordId = text(node, "ordId");
                if (ordId != null) {
                    liveOrdIds.add(ordId);
                }
            }
            int pruned = repository.deleteStaleLiveOrders(liveOrdIds);

            out.put("ok", true);
            out.put("archive", archive);
            out.put("fetched", Map.of(
                    "pending", pending.size(),
                    "history", history.size(),
                    "positions", positions.size(),
                    "fills", fills.size()));
            out.put("stored", Map.of(
                    "orders", orderRows.size(),
                    "positions", positionRows.size(),
                    "fills", fillRows.size()));
            out.put("prunedStaleOrders", pruned);
            log.info("exchange mirror synced: instType={} stored orders={} positions={} fills={} pruned={}",
                    type, orderRows.size(), positionRows.size(), fillRows.size(), pruned);
        } catch (OkxApiException ex) {
            out.put("ok", false);
            out.put("error", errorOf(ex.kind().name(), ex.okxCode(), ex.getMessage()));
            out.put("notice", "同步失败，页面继续展示本地已有数据。");
            log.warn("exchange mirror sync failed: {} {}", ex.kind(), ex.getMessage());
        } catch (RuntimeException ex) {
            out.put("ok", false);
            out.put("error", errorOf("INTERNAL", null, ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            out.put("notice", "同步失败，页面继续展示本地已有数据。");
            log.warn("exchange mirror sync failed", ex);
        } finally {
            out.put("syncedAt", Instant.now().toString());
            out.put("lastSyncedAt", safeLastSyncedAt());
            syncLock.unlock();
        }
        return out;
    }

    /** 同步状态：给页面判断「本地有没有货」。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configured", credentialsReady());
        out.put("mode", "OKX_MIRROR");
        out.put("label", keyLabel);
        Instant last = safeLastSyncedAt();
        out.put("lastSyncedAt", last == null ? null : last.toString());
        if (last == null) {
            out.put("notice", "本地还没有同步过订单，点「强制刷新」从交易所拉取。");
        }
        return out;
    }

    // ------------------------------------------------------------------ 查询

    public Map<String, Object> orders(String state, String instType, String instId, Integer limit, Integer offset) {
        String resolved = normalizeState(state);
        int size = clampQueryLimit(limit);
        int from = offset == null ? 0 : Math.max(offset, 0);
        Map<String, Object> out = envelope(resolved, instType, instId, size, from);
        long total = repository.countOrders(resolved, instId);
        out.put("total", total);
        out.put("items", repository.orders(resolved, instId, size, from));
        return withEmptyNotice(out, total);
    }

    public Map<String, Object> positions(String instType, String instId, Integer limit, Integer offset) {
        int size = clampQueryLimit(limit);
        int from = offset == null ? 0 : Math.max(offset, 0);
        Map<String, Object> out = envelope(STATE_ALL, instType, instId, size, from);
        long total = repository.countPositions(instId);
        out.put("total", total);
        out.put("items", repository.positions(instId, size, from));
        return withEmptyNotice(out, total);
    }

    public Map<String, Object> fills(String instType, String instId, Integer limit, Integer offset) {
        int size = clampQueryLimit(limit);
        int from = offset == null ? 0 : Math.max(offset, 0);
        Map<String, Object> out = envelope(STATE_ALL, instType, instId, size, from);
        long total = repository.countFills(instId);
        out.put("total", total);
        out.put("items", repository.fills(instId, size, from));
        return withEmptyNotice(out, total);
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> envelope(String state, String instType, String instId, int limit, int offset) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("state", state);
        out.put("source", "OKX_MIRROR");
        out.put("instType", instTypeOrSwap(instType));
        out.put("instId", instId == null || instId.isBlank() ? null : instId.trim());
        out.put("limit", limit);
        out.put("offset", offset);
        out.put("configured", credentialsReady());
        out.put("items", List.of());
        Instant last = safeLastSyncedAt();
        out.put("lastSyncedAt", last == null ? null : last.toString());
        return out;
    }

    private Map<String, Object> withEmptyNotice(Map<String, Object> out, long total) {
        if (total == 0 && out.get("lastSyncedAt") == null) {
            out.put("notice", "本地还没有同步过数据，点右上角「强制刷新」从交易所拉取。");
        }
        return out;
    }

    private ExchangeOrderRow orderRow(JsonNode node, boolean archived) {
        return new ExchangeOrderRow(
                text(node, "ordId"),
                text(node, "clOrdId"),
                text(node, "instId"),
                text(node, "instType"),
                text(node, "tdMode"),
                text(node, "side"),
                text(node, "posSide"),
                text(node, "ordType"),
                num(node, "sz"),
                num(node, "px"),
                num(node, "avgPx"),
                num(node, "accFillSz"),
                text(node, "state"),
                num(node, "lever"),
                num(node, "fee"),
                text(node, "feeCcy"),
                num(node, "pnl"),
                bool(node, "reduceOnly"),
                text(node, "algoId"),
                text(node, "cancelSource"),
                longVal(node, "cTime"),
                longVal(node, "uTime"),
                archived);
    }

    private List<ExchangePositionRow> positionRows(List<JsonNode> nodes) {
        List<ExchangePositionRow> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            rows.add(new ExchangePositionRow(
                    text(node, "instId"),
                    text(node, "instType"),
                    text(node, "mgnMode"),
                    text(node, "posSide"),
                    text(node, "type"),
                    num(node, "lever"),
                    num(node, "openAvgPx"),
                    num(node, "closeAvgPx"),
                    num(node, "markPx"),
                    num(node, "pnl"),
                    num(node, "pnlRatio"),
                    num(node, "realizedPnl"),
                    num(node, "fee"),
                    num(node, "fundingFee"),
                    num(node, "liqPx"),
                    longVal(node, "cTime"),
                    longVal(node, "uTime")));
        }
        return rows;
    }

    private List<ExchangeFillRow> fillRows(List<JsonNode> nodes) {
        List<ExchangeFillRow> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            rows.add(new ExchangeFillRow(
                    text(node, "tradeId"),
                    text(node, "ordId"),
                    text(node, "instId"),
                    text(node, "instType"),
                    text(node, "side"),
                    text(node, "posSide"),
                    num(node, "fillSz"),
                    num(node, "fillPx"),
                    longVal(node, "fillTime"),
                    num(node, "fee"),
                    text(node, "feeCcy"),
                    text(node, "execType"),
                    text(node, "tag"),
                    num(node, "pnl")));
        }
        return rows;
    }

    private Instant safeLastSyncedAt() {
        try {
            return repository.lastSyncedAt();
        } catch (RuntimeException ex) {
            log.warn("exchange mirror lastSyncedAt unavailable: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> errorOf(String kind, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("kind", kind);
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        String hint = switch (kind) {
            case "MISSING_CREDENTIALS" -> "到账户页检查 Key 的 passphrase / 权限 / IP 白名单。";
            case "RATE_LIMITED" -> "OKX 限流（50011），稍后重试。";
            case "NETWORK" -> "网络不可达或被 Cloudflare 拦截，稍后重试。";
            default -> null;
        };
        if (hint != null) {
            error.put("hint", hint);
        }
        return error;
    }

    private boolean credentialsReady() {
        return okxCredentials.current().ready();
    }

    /** 凭证是否齐全（页面头部展示用）。 */
    public boolean configured() {
        return okxCredentials.current().ready();
    }

    private static String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return STATE_ALL;
        }
        String resolved = state.trim().toLowerCase(Locale.ROOT);
        if (!STATE_ALL.equals(resolved) && !STATE_PENDING.equals(resolved) && !STATE_HISTORY.equals(resolved)) {
            throw new IllegalArgumentException("state must be one of all|pending|history");
        }
        return resolved;
    }

    private static String instTypeOrSwap(String instType) {
        return instType == null || instType.isBlank() ? "SWAP" : instType.trim().toUpperCase(Locale.ROOT);
    }

    private static int clampOkxLimit(Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        return Math.min(Math.max(size, 1), OKX_MAX_LIMIT);
    }

    private static int clampQueryLimit(Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        return Math.min(Math.max(size, 1), MAX_LIMIT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asString();
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static Double num(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long longVal(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean bool(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        return "true".equalsIgnoreCase(raw) || "1".equals(raw);
    }
}
