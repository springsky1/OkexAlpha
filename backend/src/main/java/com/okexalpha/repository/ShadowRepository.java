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

    /** 行情新鲜度只看最近 7 天（-> 只扫最近的 chunk），足够判定「数据是否还在进」。 */
    private static final long LATEST_KLINE_WINDOW_MS = 7L * 24L * 3600_000L;
    /** 最新 K 线时间戳缓存 180 秒：健康检查（60s 一轮）与实盘门禁共用，冷查一次约 2 秒。 */
    private static final long LATEST_KLINE_CACHE_MS = 180_000L;

    private final JdbcTemplate jdbcTemplate;
    /** bar -> [tsMs, fetchedAtMs]；0 表示窗口内没有数据。 */
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> latestKlineCache =
            new java.util.concurrent.ConcurrentHashMap<>();

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

    /**
     * 全部开放影子持仓的已用保证金合计（+ 每一层的名义敞口），用于「共享账户」闸门。
     *
     * <p>策略层原先各自只看自己的 {@code accountCapitalUsd}：4 个币各给 3385U 权益，合计 13.5K，
     * 而真实账户只有 942U —— 每个币单独看都"开得出来"，一起看就超配。这道求和就是补上这一层。</p>
     */
    public double totalOpenMargin() {
        Double sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(sum(margin_usd), 0) FROM okx_shadow_position WHERE status = 'OPEN'", Double.class);
        return sum == null ? 0d : sum;
    }

    /** 活跃策略声明的每币资金之和：真实账户读不到时的权益兜底（配置口径，不是市场口径）。 */
    public double configuredCapitalSum() {
        Double sum = jdbcTemplate.queryForObject("""
                SELECT COALESCE(sum(capital_per_coin_usd), 0) FROM okx_strategy
                WHERE mode = 'SHADOW' AND status IN ('CANDIDATE', 'PROMOTED') AND capital_per_coin_usd > 0
                """, Double.class);
        return sum == null ? 0d : sum;
    }

    /**
     * 该策略在**这个合约上**已平仓仓位的累计盈亏（平仓时已扣手续费与资金费）。
     *
     * <p>加仓闸门把它并进可用权益：亏过钱的账户不该继续放大名义。按合约分组是为了与回测严格同口径——
     * 回测的每个币是一条独立的 {@code Sim}（自己的账户权益与已实现盈亏），手工持仓（strategy_id 为空）算 0。</p>
     */
    public double realizedPnl(Long strategyId, String instId) {
        if (strategyId == null || instId == null) {
            return 0d;
        }
        Double sum = jdbcTemplate.queryForObject("""
                SELECT COALESCE(sum(unrealized_pnl), 0) FROM okx_shadow_position
                WHERE strategy_id = ? AND inst_id = ? AND status = 'CLOSED'
                """, Double.class, strategyId, instId);
        return sum == null ? 0d : sum;
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

    /**
     * 某策略某合约<b>当前这笔持仓</b>已成交的加仓层数（OPEN + LAYER 订单数）。
     *
     * <p>必须带上 {@code since}（持仓 opened_at）：早期实现不带时间窗，历史所有平仓的 LAYER 订单都会累加，
     * 几笔交易之后 {@code layers} 恒等于 maxLayers，马丁加仓直接失效。</p>
     */
    public int countLayers(Long strategyId, String instId, OffsetDateTime since) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FROM okx_shadow_order
                WHERE inst_id = ? AND status = 'FILLED' AND reason IN ('OPEN', 'LAYER')
                  AND (?::bigint IS NULL OR strategy_id = ?::bigint)
                """);
        List<Object> args = new java.util.ArrayList<>(3);
        args.add(instId);
        args.add(strategyId);
        args.add(strategyId);
        if (since != null) {
            sql.append(" AND decided_at >= ?");
            args.add(since);
        }
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
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

    /**
     * 该周期最新一根 K 线的时间戳（行情新鲜度探针）。
     *
     * <p>okx_kline 是 3 亿行级的 hypertable，而 {@code WHERE bar = ?} 没有 inst_id 前缀，走不了
     * {@code pk (kline_type, inst_id, bar, ts_ms)} 也走不了 {@code (inst_id, bar, kline_type, ts_ms DESC)}，
     * 实测一次 max() 全表并行扫要 33 秒；健康检查每 60 秒问一次，等于把库按在地上摩擦。</p>
     *
     * <p>这里加 {@code ts_ms} 下界：ts_ms 是 hypertable 的分区列，带下界后 TimescaleDB 只扫窗口内的
     * chunk（chunk_time_interval = 7 天）；再叠一层 60 秒缓存，让健康检查 / 实盘门禁共用同一次查询。</p>
     */
    public Long latestKlineTs(String bar) {
        long now = System.currentTimeMillis();
        long[] cached = latestKlineCache.get(bar);
        if (cached != null && now - cached[1] < LATEST_KLINE_CACHE_MS) {
            return cached[0] > 0L ? cached[0] : null;
        }
        // 7 天窗口：比健康检查的 2 小时超时阈值宽，行情真断了仍能拿到旧值来判定「多久没更新」。
        Long ts = jdbcTemplate.queryForObject(
                "SELECT max(ts_ms) FROM okx_kline WHERE bar = ? AND ts_ms >= ?", Long.class, bar,
                now - LATEST_KLINE_WINDOW_MS);
        latestKlineCache.put(bar, new long[] {ts == null ? 0L : ts, now});
        return ts;
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
