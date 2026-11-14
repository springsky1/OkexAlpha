package com.okexalpha.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.ExchangeFillRow;
import com.okexalpha.domain.ExchangeOrderRow;
import com.okexalpha.domain.ExchangePositionRow;

/**
 * 订单中心镜像库的读写（okx_exchange_order / okx_exchange_position / okx_exchange_fill）。
 *
 * <p>写：一律 UPSERT，重复同步只更新状态与盈亏，不产生重复行。读：返回 camelCase 键，
 * 直接喂给前端表格（字段名与交易所原始返回保持一致，方便复用 account/acc.ts 的列定义）。</p>
 */
@Repository
public class ExchangeMirrorRepository {

    private static final String ORDER_UPSERT = """
            INSERT INTO okx_exchange_order (
                ord_id, cl_ord_id, inst_id, inst_type, td_mode, side, pos_side, ord_type,
                sz, px, avg_px, fill_sz, state, lever, fee, fee_ccy, pnl, reduce_only, algo_id,
                cancel_source, c_time_ms, u_time_ms, archived, synced_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (ord_id) DO UPDATE SET
                cl_ord_id = EXCLUDED.cl_ord_id,
                inst_id = EXCLUDED.inst_id,
                inst_type = EXCLUDED.inst_type,
                td_mode = EXCLUDED.td_mode,
                side = EXCLUDED.side,
                pos_side = EXCLUDED.pos_side,
                ord_type = EXCLUDED.ord_type,
                sz = EXCLUDED.sz,
                px = EXCLUDED.px,
                avg_px = EXCLUDED.avg_px,
                fill_sz = EXCLUDED.fill_sz,
                state = EXCLUDED.state,
                lever = EXCLUDED.lever,
                fee = EXCLUDED.fee,
                fee_ccy = EXCLUDED.fee_ccy,
                pnl = EXCLUDED.pnl,
                reduce_only = EXCLUDED.reduce_only,
                algo_id = EXCLUDED.algo_id,
                cancel_source = EXCLUDED.cancel_source,
                c_time_ms = EXCLUDED.c_time_ms,
                u_time_ms = EXCLUDED.u_time_ms,
                archived = okx_exchange_order.archived OR EXCLUDED.archived,
                synced_at = now()
            """;

    private static final String ORDER_COLUMNS = """
            ord_id AS "ordId", cl_ord_id AS "clOrdId", inst_id AS "instId", inst_type AS "instType",
            td_mode AS "tdMode", side, pos_side AS "posSide", ord_type AS "ordType",
            sz, px, avg_px AS "avgPx", fill_sz AS "fillSz", state, lever, fee, fee_ccy AS "feeCcy",
            pnl, reduce_only AS "reduceOnly", algo_id AS "algoId", cancel_source AS "cancelSource",
            c_time_ms AS "cTime", u_time_ms AS "uTime", archived
            """;

    private static final String POSITION_UPSERT = """
            INSERT INTO okx_exchange_position (
                inst_id, inst_type, mgn_mode, pos_side, type, lever, open_avg_px, close_avg_px, mark_px,
                pnl, pnl_ratio, realized_pnl, fee, funding_fee, liq_px, c_time_ms, u_time_ms, synced_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT ON CONSTRAINT uq_okx_exchange_position DO UPDATE SET
                inst_type = EXCLUDED.inst_type,
                type = EXCLUDED.type,
                lever = EXCLUDED.lever,
                open_avg_px = EXCLUDED.open_avg_px,
                close_avg_px = EXCLUDED.close_avg_px,
                mark_px = EXCLUDED.mark_px,
                pnl = EXCLUDED.pnl,
                pnl_ratio = EXCLUDED.pnl_ratio,
                realized_pnl = EXCLUDED.realized_pnl,
                fee = EXCLUDED.fee,
                funding_fee = EXCLUDED.funding_fee,
                liq_px = EXCLUDED.liq_px,
                synced_at = now()
            """;

    private static final String POSITION_COLUMNS = """
            inst_id AS "instId", inst_type AS "instType", mgn_mode AS "mgnMode", pos_side AS "posSide",
            type, lever, open_avg_px AS "openAvgPx", close_avg_px AS "closeAvgPx", mark_px AS "markPx",
            pnl, pnl_ratio AS "pnlRatio", realized_pnl AS "realizedPnl", fee, funding_fee AS "fundingFee",
            liq_px AS "liqPx", c_time_ms AS "cTime", u_time_ms AS "uTime"
            """;

    private static final String FILL_UPSERT = """
            INSERT INTO okx_exchange_fill (
                trade_id, ord_id, inst_id, inst_type, side, pos_side, fill_sz, fill_px, fill_time_ms,
                fee, fee_ccy, exec_type, tag, pnl, synced_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (trade_id) DO UPDATE SET
                ord_id = EXCLUDED.ord_id,
                inst_id = EXCLUDED.inst_id,
                inst_type = EXCLUDED.inst_type,
                side = EXCLUDED.side,
                pos_side = EXCLUDED.pos_side,
                fill_sz = EXCLUDED.fill_sz,
                fill_px = EXCLUDED.fill_px,
                fill_time_ms = EXCLUDED.fill_time_ms,
                fee = EXCLUDED.fee,
                fee_ccy = EXCLUDED.fee_ccy,
                exec_type = EXCLUDED.exec_type,
                tag = EXCLUDED.tag,
                pnl = EXCLUDED.pnl,
                synced_at = now()
            """;

    private static final String FILL_COLUMNS = """
            trade_id AS "tradeId", ord_id AS "ordId", inst_id AS "instId", inst_type AS "instType",
            side, pos_side AS "posSide", fill_sz AS "fillSz", fill_px AS "fillPx", fill_time_ms AS "fillTime",
            fee, fee_ccy AS "feeCcy", exec_type AS "execType", tag, pnl
            """;

    private final JdbcTemplate jdbc;

    public ExchangeMirrorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 写

    public int upsertOrders(List<ExchangeOrderRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (ExchangeOrderRow row : rows) {
            batch.add(new Object[] {
                    row.ordId(), row.clOrdId(), row.instId(), row.instType(), row.tdMode(), row.side(),
                    row.posSide(), row.ordType(), row.sz(), row.px(), row.avgPx(), row.fillSz(), row.state(),
                    row.lever(), row.fee(), row.feeCcy(), row.pnl(), row.reduceOnly(), row.algoId(),
                    row.cancelSource(), row.cTimeMs(), row.uTimeMs(), row.archived()
            });
        }
        int[] updated = jdbc.batchUpdate(ORDER_UPSERT, batch);
        return updated.length;
    }

    public int upsertPositions(List<ExchangePositionRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (ExchangePositionRow row : rows) {
            batch.add(new Object[] {
                    row.instId(), row.instType(), row.mgnMode(), row.posSide(), row.type(), row.lever(),
                    row.openAvgPx(), row.closeAvgPx(), row.markPx(), row.pnl(), row.pnlRatio(),
                    row.realizedPnl(), row.fee(), row.fundingFee(), row.liqPx(), row.cTimeMs(), row.uTimeMs()
            });
        }
        return jdbc.batchUpdate(POSITION_UPSERT, batch).length;
    }

    public int upsertFills(List<ExchangeFillRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (ExchangeFillRow row : rows) {
            batch.add(new Object[] {
                    row.tradeId(), row.ordId(), row.instId(), row.instType(), row.side(), row.posSide(),
                    row.fillSz(), row.fillPx(), row.fillTimeMs(), row.fee(), row.feeCcy(), row.execType(),
                    row.tag(), row.pnl()
            });
        }
        return jdbc.batchUpdate(FILL_UPSERT, batch).length;
    }

    /**
     * 清理「本次挂单快照里已经没有、但本地还标着 live/partially_filled」的行。
     *
     * <p>这些单其实已经成交/撤单，终态会由同一次同步的历史委托 UPSERT 回来；先历史后挂单再删残留，
     * 才不会把刚同步到的终态误删。</p>
     */
    public int deleteStaleLiveOrders(Set<String> keepOrdIds) {
        if (keepOrdIds == null || keepOrdIds.isEmpty()) {
            return jdbc.update("DELETE FROM okx_exchange_order WHERE state IN ('live', 'partially_filled')");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(keepOrdIds.size(), "?"));
        List<Object> args = new ArrayList<>(keepOrdIds);
        return jdbc.update("DELETE FROM okx_exchange_order WHERE state IN ('live', 'partially_filled')"
                + " AND ord_id NOT IN (" + placeholders + ")", args.toArray());
    }

    // ------------------------------------------------------------------ 读

    public List<Map<String, Object>> orders(String state, String instId, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(ORDER_COLUMNS)
                .append(" FROM okx_exchange_order WHERE ").append(stateClause(state));
        if (instId != null && !instId.isBlank()) {
            sql.append(" AND inst_id = ?");
            args.add(instId.trim());
        }
        sql.append(" ORDER BY c_time_ms DESC NULLS LAST, ord_id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long countOrders(String state, String instId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM okx_exchange_order WHERE ")
                .append(stateClause(state));
        if (instId != null && !instId.isBlank()) {
            sql.append(" AND inst_id = ?");
            args.add(instId.trim());
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public List<Map<String, Object>> positions(String instId, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(POSITION_COLUMNS)
                .append(" FROM okx_exchange_position WHERE TRUE");
        if (instId != null && !instId.isBlank()) {
            sql.append(" AND inst_id = ?");
            args.add(instId.trim());
        }
        sql.append(" ORDER BY c_time_ms DESC, u_time_ms DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long countPositions(String instId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM okx_exchange_position WHERE TRUE");
        if (instId != null && !instId.isBlank()) {
            sql.append(" AND inst_id = ?");
            args.add(instId.trim());
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public List<Map<String, Object>> fills(String instId, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(FILL_COLUMNS)
                .append(" FROM okx_exchange_fill WHERE TRUE");
        if (instId != null && !instId.isBlank()) {
            sql.append(" AND inst_id = ?");
            args.add(instId.trim());
        }
        sql.append(" ORDER BY fill_time_ms DESC NULLS LAST, trade_id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long countFills(String instId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM okx_exchange_fill WHERE TRUE");
        if (instId != null && !instId.isBlank()) {
            sql.append(" AND inst_id = ?");
            args.add(instId.trim());
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** 最近一次成功同步的落库时间；空库返回 null。 */
    public Instant lastSyncedAt() {
        Timestamp ts = jdbc.queryForObject("""
                SELECT max(synced_at) FROM (
                    SELECT max(synced_at) AS synced_at FROM okx_exchange_order
                    UNION ALL SELECT max(synced_at) FROM okx_exchange_position
                    UNION ALL SELECT max(synced_at) FROM okx_exchange_fill
                ) t
                """, Timestamp.class);
        return ts == null ? null : ts.toInstant();
    }

    private static String stateClause(String state) {
        String resolved = state == null ? "all" : state.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (resolved) {
            case "pending" -> "state IN ('live', 'partially_filled')";
            case "history" -> "(state IS NULL OR state NOT IN ('live', 'partially_filled'))";
            default -> "TRUE";
        };
    }
}
