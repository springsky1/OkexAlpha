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
