package com.okexalpha.repository;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * okx_strategy 注册表读取（策略清单 + 影子运行统计）。
 *
 * <p>「运行中」的口径与影子引擎严格一致：{@code mode='SHADOW' AND status IN ('CANDIDATE','PROMOTED')}
 * —— 见 {@link ShadowRepository#activeShadowStrategies()}。其余（DRAFT/ARCHIVED、以及未来 mode=REAL 的）
 * 都算「历史」。这样账户页的"当前运行的策略"和引擎实际会跑的集合不会漂移。</p>
 */
@Repository
public class StrategyRepository {

    /** 与影子引擎一致的可运行集合谓词。 */
    public static final String RUNNING_PREDICATE = "(mode = 'SHADOW' AND status IN ('CANDIDATE', 'PROMOTED'))";

    private static final String BASE_COLUMNS = """
            SELECT id, name, status, mode, params::text AS params_json, universe::text AS universe_json,
                   capital_per_coin_usd, source_run_id, note, created_at, updated_at
            FROM okx_strategy
            """;

    private static final RowMapper<StrategyRow> MAPPER = (ResultSet rs, int rowNum) -> new StrategyRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("mode"),
            rs.getString("params_json"),
            rs.getString("universe_json"),
            rs.getObject("capital_per_coin_usd", Double.class),
            rs.getObject("source_run_id", Long.class),
            rs.getString("note"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public StrategyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record StrategyRow(long id, String name, String status, String mode, String paramsJson,
            String universeJson, Double capitalPerCoinUsd, Long sourceRunId, String note,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** 影子账本聚合（一次查询覆盖一页策略，避免 N+1）。 */
    public record StrategyStats(int openPositions, double openNotionalUsd, double unrealizedPnlUsd,
            int closedPositions, int orderCount, OffsetDateTime lastOrderAt) {

        public static final StrategyStats EMPTY = new StrategyStats(0, 0d, 0d, 0, 0, null);
    }

    /**
     * 订单中心（策略 tab）聚合：累计投入 / 已实现 / 未实现 / 仓位数 / 订单数与最近下单时间。
     *
     * <p>investedUsd 为 null 表示"没有可加总的持仓行"，由调用方回落到
     * {@code capital_per_coin_usd * universe 数量}。</p>
     */
    public record StrategyCenterStats(Double investedUsd, double realizedPnlUsd, double unrealizedPnlUsd,
            int openPositions, int closedPositions, int orderCount, OffsetDateTime lastOrderAt) {

        public static final StrategyCenterStats EMPTY = new StrategyCenterStats(null, 0d, 0d, 0, 0, 0, null);
    }

    public List<StrategyRow> list(String state, String status, String mode, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String sql = BASE_COLUMNS + where(state, status, mode, args) + " ORDER BY id DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql, MAPPER, args.toArray());
    }

    public long count(String state, String status, String mode) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT count(*) FROM okx_strategy" + where(state, status, mode, args);
        Long total = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public Optional<StrategyRow> find(long id) {
        List<StrategyRow> rows = jdbcTemplate.query(BASE_COLUMNS + " WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 一页策略的影子聚合；缺失的策略按 {@link StrategyStats#EMPTY} 处理。 */
    public Map<Long, StrategyStats> stats(Collection<Long> strategyIds) {
        if (strategyIds == null || strategyIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(strategyIds.size(), "?"));
        Object[] args = strategyIds.toArray();

        Map<Long, int[]> positionCounts = new HashMap<>();
        Map<Long, Double> openNotional = new HashMap<>();
        Map<Long, Double> upl = new HashMap<>();
        Map<Long, Integer> closedCounts = new HashMap<>();
        jdbcTemplate.query("""
                SELECT strategy_id,
                       count(*) FILTER (WHERE status = 'OPEN') AS open_cnt,
                       coalesce(sum(notional_usd) FILTER (WHERE status = 'OPEN'), 0) AS open_notional,
                       coalesce(sum(unrealized_pnl) FILTER (WHERE status = 'OPEN'), 0) AS upl,
                       count(*) FILTER (WHERE status = 'CLOSED') AS closed_cnt
                FROM okx_shadow_position
                WHERE strategy_id IN (%s)
                GROUP BY strategy_id
                """.formatted(placeholders), (ResultSet rs) -> {
            long id = rs.getLong("strategy_id");
            positionCounts.put(id, new int[] { rs.getInt("open_cnt") });
            openNotional.put(id, rs.getDouble("open_notional"));
            upl.put(id, rs.getDouble("upl"));
            closedCounts.put(id, rs.getInt("closed_cnt"));
        }, args);

        Map<Long, Integer> orderCounts = new HashMap<>();
        Map<Long, OffsetDateTime> lastOrderAt = new HashMap<>();
        jdbcTemplate.query("""
                SELECT strategy_id, count(*) AS order_cnt, max(decided_at) AS last_decided_at
                FROM okx_shadow_order
                WHERE strategy_id IN (%s)
                GROUP BY strategy_id
                """.formatted(placeholders), (ResultSet rs) -> {
            long id = rs.getLong("strategy_id");
            orderCounts.put(id, rs.getInt("order_cnt"));
            lastOrderAt.put(id, rs.getObject("last_decided_at", OffsetDateTime.class));
        }, args);

        Map<Long, StrategyStats> out = new HashMap<>();
        for (Long id : strategyIds) {
            int[] open = positionCounts.get(id);
            out.put(id, new StrategyStats(
                    open == null ? 0 : open[0],
                    openNotional.getOrDefault(id, 0d),
                    upl.getOrDefault(id, 0d),
                    closedCounts.getOrDefault(id, 0),
                    orderCounts.getOrDefault(id, 0),
                    lastOrderAt.get(id)));
        }
        return out;
    }

    /**
     * 一页策略的订单中心聚合：单条 SQL 覆盖策略表 + 影子持仓 + 影子订单，避免逐策略 N+1。
     *
     * <p>盈亏口径与影子账本一致：okx_shadow_position 没有 realized 列，平仓时把已实现写进
     * {@code unrealized_pnl}，所以 CLOSED 行的该列就是已实现盈亏、OPEN 行的就是浮动盈亏。</p>
     */
    public Map<Long, StrategyCenterStats> centerStats(Collection<Long> strategyIds) {
        if (strategyIds == null || strategyIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(strategyIds.size(), "?"));

        Map<Long, StrategyCenterStats> out = new HashMap<>();
        // 显式声明 RowCallbackHandler：表达式体 lambda 同时满足 RowMapper/ResultSetExtractor，
        // 靠推断选重载会让 mock 测试命不中真正调用的方法。
        RowCallbackHandler handler = (ResultSet rs) -> {
            out.put(rs.getLong("strategy_id"), new StrategyCenterStats(
                    rs.getObject("invested", Double.class),
                    rs.getDouble("realized"),
                    rs.getDouble("unrealized"),
                    rs.getInt("open_cnt"),
                    rs.getInt("closed_cnt"),
                    rs.getInt("order_cnt"),
                    rs.getObject("last_decided_at", OffsetDateTime.class)));
        };
        jdbcTemplate.query("""
                SELECT s.id AS strategy_id,
                       sum(p.margin_usd) AS invested,
                       coalesce(sum(p.unrealized_pnl) FILTER (WHERE p.status = 'CLOSED'), 0) AS realized,
                       coalesce(sum(p.unrealized_pnl) FILTER (WHERE p.status = 'OPEN'), 0) AS unrealized,
                       count(p.id) FILTER (WHERE p.status = 'OPEN') AS open_cnt,
                       count(p.id) FILTER (WHERE p.status = 'CLOSED') AS closed_cnt,
                       (SELECT count(*) FROM okx_shadow_order o WHERE o.strategy_id = s.id) AS order_cnt,
                       (SELECT max(o.decided_at) FROM okx_shadow_order o WHERE o.strategy_id = s.id) AS last_decided_at
                FROM okx_strategy s
                LEFT JOIN okx_shadow_position p ON p.strategy_id = s.id
                WHERE s.id IN (%s)
                GROUP BY s.id
                """.formatted(placeholders), handler, strategyIds.toArray());
        return out;
    }

    /**
     * 状态归一化：去空白 + 大写。STOPPED 自迁移 010 起是一等状态（ck_okx_strategy_status 允许它），
     * 不再折成 ARCHIVED —— {@link #RUNNING_PREDICATE} 只认 SHADOW + CANDIDATE/PROMOTED，
     * 停掉的策略同样不会被影子引擎调度。
     */
    public static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** 改策略状态；返回受影响行数（0 表示策略不存在）。 */
    public int updateStatus(long id, String status) {
        return jdbcTemplate.update("UPDATE okx_strategy SET status = ?, updated_at = now() WHERE id = ?",
                normalizeStatus(status), id);
    }

    private static String where(String state, String status, String mode, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (mode != null && !mode.isBlank()) {
            sql.append(" AND mode = ?");
            args.add(mode.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if ("running".equalsIgnoreCase(state)) {
            sql.append(" AND ").append(RUNNING_PREDICATE);
        } else if ("history".equalsIgnoreCase(state)) {
            sql.append(" AND NOT ").append(RUNNING_PREDICATE);
        }
        return sql.toString();
    }
}
