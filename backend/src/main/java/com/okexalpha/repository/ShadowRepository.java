package com.okexalpha.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.HealthCheckRow;
import com.okexalpha.domain.MinOrderRow;
import com.okexalpha.domain.PortfolioSnapshotRow;
import com.okexalpha.domain.ShadowOrderRow;
import com.okexalpha.domain.ShadowPositionRow;

/**
 * 影子订单 / 影子持仓 / 组合快照 / 健康度体检的读写（阶段 C / P2、P3）。
 *
 * <p>全部为本地账本，不发真实订单。持仓与订单都按策略隔离，同一 (策略, 合约) 只开一个影子持仓。</p>
 */
@Repository
public class ShadowRepository {

    private final JdbcTemplate jdbcTemplate;

    public ShadowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 影子引擎要处理的策略：mode=SHADOW 且状态为 CANDIDATE/PROMOTED。 */
    public record ShadowStrategyRow(long id, String name, String status, String paramsJson, String universeJson,
            Double capitalPerCoinUsd) {
    }

    private static final RowMapper<ShadowPositionRow> POSITION_MAPPER = (ResultSet rs, int rowNum) -> new ShadowPositionRow(
            rs.getLong("id"),
            (Long) rs.getObject("strategy_id", Long.class),
            rs.getString("inst_id"),
            rs.getString("side"),
            rs.getDouble("sz"),
            rs.getObject("entry_px", Double.class),
            rs.getObject("mark_px", Double.class),
            rs.getObject("notional_usd", Double.class),
            rs.getObject("unrealized_pnl", Double.class),
            rs.getObject("leverage", Double.class),
            rs.getObject("liq_px", Double.class),
            rs.getObject("margin_usd", Double.class),
            rs.getString("status"),
            rs.getObject("opened_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("closed_at", OffsetDateTime.class));

    private static final String POSITION_COLUMNS = """
            SELECT id, strategy_id, inst_id, side, sz, entry_px, mark_px, notional_usd, unrealized_pnl, leverage,
                   liq_px, margin_usd, status, opened_at, updated_at, closed_at
            FROM okx_shadow_position
            """;

    private static final String ORDER_COLUMNS = """
            SELECT id, strategy_id, inst_id, side, ord_type, sz, px, notional_usd, status, reason, signal_ts_ms,
                   decided_at
            FROM okx_shadow_order
            """;

    private static final RowMapper<ShadowOrderRow> ORDER_MAPPER = (ResultSet rs, int rowNum) -> new ShadowOrderRow(
            rs.getLong("id"),
            (Long) rs.getObject("strategy_id", Long.class),
            rs.getString("inst_id"),
            rs.getString("side"),
            rs.getString("ord_type"),
            rs.getObject("sz", Double.class),
            rs.getObject("px", Double.class),
            rs.getObject("notional_usd", Double.class),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getObject("signal_ts_ms", Long.class),
            rs.getObject("decided_at", OffsetDateTime.class));

    private static final RowMapper<HealthCheckRow> HEALTH_MAPPER = (ResultSet rs, int rowNum) -> new HealthCheckRow(
            rs.getLong("id"),
            rs.getObject("checked_at", OffsetDateTime.class),
            rs.getString("rule"),
            rs.getString("level"),
            rs.getString("scope"),
            rs.getString("inst_id"),
            rs.getString("message"),
            rs.getString("metric"));

    private static final RowMapper<PortfolioSnapshotRow> PORTFOLIO_MAPPER = (ResultSet rs, int rowNum) -> new PortfolioSnapshotRow(
            rs.getLong("id"),
            rs.getLong("ts_ms"),
            rs.getObject("equity_usd", Double.class),
            rs.getObject("available_usd", Double.class),
            rs.getObject("position_count", Integer.class),
            rs.getObject("gross_notional_usd", Double.class),
            rs.getObject("net_notional_usd", Double.class),
            rs.getObject("unrealized_pnl_usd", Double.class),
            rs.getObject("created_at", OffsetDateTime.class));

    // ---------------------------------------------------------------- 策略

    public List<ShadowStrategyRow> activeShadowStrategies() {
        return jdbcTemplate.query("""
                SELECT id, name, status, params::text AS params_json, universe::text AS universe_json,
                       capital_per_coin_usd
                FROM okx_strategy
                WHERE mode = 'SHADOW' AND status IN ('CANDIDATE', 'PROMOTED')
                ORDER BY id
                """, (ResultSet rs, int rowNum) -> new ShadowStrategyRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("params_json"),
                rs.getString("universe_json"),
                rs.getObject("capital_per_coin_usd", Double.class)));
    }

    // ---------------------------------------------------------------- 持仓

    public List<ShadowPositionRow> openPositions() {
        return jdbcTemplate.query(POSITION_COLUMNS + " WHERE status = 'OPEN' ORDER BY id", POSITION_MAPPER);
    }

    public List<ShadowPositionRow> openPositions(long strategyId) {
        return jdbcTemplate.query(POSITION_COLUMNS + " WHERE status = 'OPEN' AND strategy_id = ? ORDER BY id",
                POSITION_MAPPER, strategyId);
    }

    /**
     * 手工复制开仓的持仓（strategy_id IS NULL）：不属于任何策略，由 tick 用 Trading 默认参数管理
     * （止盈/止损/强平/超时/逆势加仓都与策略持仓同一套逻辑）。
     */
    public List<ShadowPositionRow> manualOpenPositions() {
        return jdbcTemplate.query(POSITION_COLUMNS + " WHERE status = 'OPEN' AND strategy_id IS NULL ORDER BY id",
                POSITION_MAPPER);
    }

    public List<ShadowPositionRow> positions(String status, int limit, int offset) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query(POSITION_COLUMNS + " ORDER BY id DESC LIMIT ? OFFSET ?", POSITION_MAPPER,
                    limit, offset);
        }
        return jdbcTemplate.query(POSITION_COLUMNS + " WHERE status = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                POSITION_MAPPER, status, limit, offset);
    }

    public ShadowPositionRow findPosition(long id) {
        List<ShadowPositionRow> rows = jdbcTemplate.query(POSITION_COLUMNS + " WHERE id = ?", POSITION_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long countOpenPositions() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM okx_shadow_position WHERE status = 'OPEN'", Long.class);
        return count == null ? 0L : count;
    }

    public long insertPosition(Long strategyId, String instId, String side, double sz, double entryPx, double markPx,
            double notionalUsd, double unrealizedPnl, double leverage, double liqPx, double marginUsd) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO okx_shadow_position (
                    strategy_id, inst_id, side, sz, entry_px, mark_px, notional_usd, unrealized_pnl, leverage,
                    liq_px, margin_usd, status, opened_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', now(), now())
                RETURNING id
                """, Long.class, strategyId, instId, side, sz, entryPx, markPx, notionalUsd, unrealizedPnl,
                leverage, liqPx, marginUsd);
        if (id == null) {
            throw new IllegalStateException("insert okx_shadow_position returned no id");
        }
        return id;
    }

    /** 更新持仓（加仓后重算均价/张数/名义/保证金/强平价，或仅刷新标记价与浮盈）。 */
    public void updatePosition(long id, double sz, double entryPx, double markPx, double unrealizedPnl,
            double notionalUsd, double marginUsd, double liqPx) {
        jdbcTemplate.update("""
                UPDATE okx_shadow_position
                SET sz = ?, entry_px = ?, mark_px = ?, unrealized_pnl = ?, notional_usd = ?, margin_usd = ?,
                    liq_px = ?, updated_at = now()
                WHERE id = ?
                """, sz, entryPx, markPx, unrealizedPnl, notionalUsd, marginUsd, liqPx, id);
    }

    public void markPosition(long id, double markPx, double unrealizedPnl) {
        jdbcTemplate.update(
                "UPDATE okx_shadow_position SET mark_px = ?, unrealized_pnl = ?, updated_at = now() WHERE id = ?",
                markPx, unrealizedPnl, id);
    }

    public void closePosition(long id, double markPx, double unrealizedPnl) {
        jdbcTemplate.update("""
                UPDATE okx_shadow_position
                SET mark_px = ?, unrealized_pnl = ?, status = 'CLOSED', closed_at = now(), updated_at = now()
                WHERE id = ?
                """, markPx, unrealizedPnl, id);
    }

    // ---------------------------------------------------------------- 订单

    public long insertOrder(Long strategyId, String instId, String side, String ordType, double sz, double px,
            double notionalUsd, String status, String reason, Long signalTsMs) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO okx_shadow_order (
                    strategy_id, inst_id, side, ord_type, sz, px, notional_usd, status, reason, signal_ts_ms,
                    decided_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                RETURNING id
                """, Long.class, strategyId, instId, side, ordType, sz, px, notionalUsd, status, reason, signalTsMs);
        if (id == null) {
            throw new IllegalStateException("insert okx_shadow_order returned no id");
        }
        return id;
    }

    public List<ShadowOrderRow> orders(String status, String instId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, strategy_id, inst_id, side, ord_type, sz, px, notional_usd, status, reason, signal_ts_ms,
                       decided_at
                FROM okx_shadow_order WHERE 1 = 1
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (instId != null && !instId.isBlank()) {
            sql.append(" AND inst_id = ?");
            args.add(instId);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ORDER_MAPPER, args.toArray());
    }

    /** 账户页：某策略的订单（status 为空表示全部状态）。 */
    public List<ShadowOrderRow> ordersByStrategy(long strategyId, String status, int limit, int offset) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query(ORDER_COLUMNS + " WHERE strategy_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                    ORDER_MAPPER, strategyId, limit, offset);
        }
        return jdbcTemplate.query(ORDER_COLUMNS
                + " WHERE strategy_id = ? AND status = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                ORDER_MAPPER, strategyId, status.trim().toUpperCase(java.util.Locale.ROOT), limit, offset);
    }

    /** 账户页：某策略的持仓（status=OPEN/CLOSED，空表示全部）。 */
    public List<ShadowPositionRow> positionsByStrategy(long strategyId, String status, int limit, int offset) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query(POSITION_COLUMNS + " WHERE strategy_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                    POSITION_MAPPER, strategyId, limit, offset);
        }
        return jdbcTemplate.query(POSITION_COLUMNS
                + " WHERE strategy_id = ? AND status = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                POSITION_MAPPER, strategyId, status.trim().toUpperCase(java.util.Locale.ROOT), limit, offset);
    }

    /** 某策略某合约已成交的加仓层数（OPEN + LAYER 订单数）。 */
    public int countLayers(Long strategyId, String instId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM okx_shadow_order
                WHERE inst_id = ? AND status = 'FILLED' AND reason IN ('OPEN', 'LAYER')
                  AND (?::bigint IS NULL OR strategy_id = ?::bigint)
                """, Integer.class, instId, strategyId, strategyId);
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- 最小下单量

    public MinOrderRow findMinOrder(String instId) {
        List<MinOrderRow> rows = jdbcTemplate.query("""
                SELECT inst_id, inst_type, settle_ccy, ct_val, ct_val_ccy, ct_mult, lot_sz, min_sz, tick_sz, ref_px,
                       notional_per_contract_usd, min_notional_usd, min_lots, tradable, note
                FROM okx_min_order WHERE inst_id = ?
                """, (ResultSet rs, int rowNum) -> new MinOrderRow(
                rs.getString("inst_id"),
                rs.getString("inst_type"),
                rs.getString("settle_ccy"),
                rs.getObject("ct_val", Double.class),
                rs.getString("ct_val_ccy"),
                rs.getObject("ct_mult", Double.class),
                rs.getObject("lot_sz", Double.class),
                rs.getObject("min_sz", Double.class),
                rs.getObject("tick_sz", Double.class),
                rs.getObject("ref_px", Double.class),
                rs.getObject("notional_per_contract_usd", Double.class),
                rs.getObject("min_notional_usd", Double.class),
                rs.getObject("min_lots", Double.class),
                rs.getBoolean("tradable"),
                rs.getString("note")), instId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------------------------------------------------------- 组合快照

    public void insertPortfolio(long tsMs, double equityUsd, double availableUsd, int positionCount,
            double grossNotionalUsd, double netNotionalUsd, double unrealizedPnlUsd, String detailJson) {
        jdbcTemplate.update("""
                INSERT INTO okx_portfolio_snapshot (
                    ts_ms, equity_usd, available_usd, position_count, gross_notional_usd, net_notional_usd,
                    unrealized_pnl_usd, detail
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (ts_ms) DO NOTHING
                """, tsMs, equityUsd, availableUsd, positionCount, grossNotionalUsd, netNotionalUsd,
                unrealizedPnlUsd, detailJson);
    }

    public List<PortfolioSnapshotRow> portfolio(int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT id, ts_ms, equity_usd, available_usd, position_count, gross_notional_usd, net_notional_usd,
                       unrealized_pnl_usd, created_at
                FROM okx_portfolio_snapshot ORDER BY ts_ms DESC LIMIT ? OFFSET ?
                """, PORTFOLIO_MAPPER, limit, offset);
    }

    public PortfolioSnapshotRow latestPortfolio() {
        List<PortfolioSnapshotRow> rows = jdbcTemplate.query("""
                SELECT id, ts_ms, equity_usd, available_usd, position_count, gross_notional_usd, net_notional_usd,
                       unrealized_pnl_usd, created_at
                FROM okx_portfolio_snapshot ORDER BY ts_ms DESC LIMIT 1
                """, PORTFOLIO_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------------------------------------------------------- 健康度

    public void insertHealth(java.time.OffsetDateTime checkedAt, String rule, String level, String scope,
            String instId, String message, String metricJson) {
        jdbcTemplate.update("""
                INSERT INTO okx_health_check (checked_at, rule, level, scope, inst_id, message, metric)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, checkedAt, rule, level, scope, instId, message, metricJson);
    }

    public List<HealthCheckRow> healthChecks(String level, int limit, int offset) {
        if (level == null || level.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT id, checked_at, rule, level, scope, inst_id, message, metric::text AS metric
                    FROM okx_health_check ORDER BY id DESC LIMIT ? OFFSET ?
                    """, HEALTH_MAPPER, limit, offset);
        }
        return jdbcTemplate.query("""
                SELECT id, checked_at, rule, level, scope, inst_id, message, metric::text AS metric
                FROM okx_health_check WHERE level = ? ORDER BY id DESC LIMIT ? OFFSET ?
                """, HEALTH_MAPPER, level, limit, offset);
    }

    /** 最近一轮（同一 checked_at 批次）的结论。 */
    public List<HealthCheckRow> latestHealthChecks() {
        return jdbcTemplate.query("""
                SELECT id, checked_at, rule, level, scope, inst_id, message, metric::text AS metric
                FROM okx_health_check
                WHERE checked_at = (SELECT max(checked_at) FROM okx_health_check)
                ORDER BY CASE level WHEN 'FAIL' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END, rule
                """, HEALTH_MAPPER);
    }

    // ---------------------------------------------------------------- 参考数据

    public Double latestFundingRate(String instId) {
        List<Double> rows = jdbcTemplate.queryForList(
                "SELECT funding_rate FROM okx_funding_rate WHERE inst_id = ? ORDER BY funding_time_ms DESC LIMIT 1",
                Double.class, instId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long latestKlineTs(String bar) {
        return jdbcTemplate.queryForObject("SELECT max(ts_ms) FROM okx_kline WHERE bar = ?", Long.class, bar);
    }

    /** 最近 N 分钟 API 成功/失败计数。 */
    public long[] apiOutcomeCounts(int minutes) {
        return jdbcTemplate.query("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE okx_code IS NOT NULL AND okx_code <> '0') + count(*) FILTER (WHERE http_status >= 400) AS failed
                FROM okx_api_call_log WHERE requested_at > now() - (? || ' minutes')::interval
                """, rs -> {
            if (!rs.next()) {
                return new long[] {0L, 0L};
            }
            return new long[] {rs.getLong("total"), rs.getLong("failed")};
        }, String.valueOf(minutes));
    }

    public long countRunningBackfills() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM okx_backfill_job WHERE status = 'RUNNING'", Long.class);
        return count == null ? 0L : count;
    }

    // ---------------------------------------------------------------- 实盘门禁（阶段 C / P4）

    /** [sinceMs, now] 内影子账本累计成交的名义金额（每日上限用）。 */
    public double filledNotionalSince(long sinceMs) {
        Double sum = jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(notional_usd), 0) FROM okx_shadow_order
                WHERE status = 'FILLED' AND signal_ts_ms >= ?
                """, Double.class, sinceMs);
        return sum == null ? 0d : sum;
    }

    /** 最近一轮体检里 FAIL 级结论的条数（0 表示全绿）。 */
    public long latestHealthFailCount() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM okx_health_check
                WHERE level = 'FAIL' AND checked_at = (SELECT max(checked_at) FROM okx_health_check)
                """, Long.class);
        return count == null ? 0L : count;
    }

    /** 最近一轮体检里 WARN 级结论的条数。 */
    public long latestHealthWarnCount() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM okx_health_check
                WHERE level = 'WARN' AND checked_at = (SELECT max(checked_at) FROM okx_health_check)
                """, Long.class);
        return count == null ? 0L : count;
    }
}
