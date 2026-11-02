package com.okexalpha.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.BacktestResultRow;
import com.okexalpha.domain.BacktestRunRow;
import com.okexalpha.strategy.engine.BacktestTrade;
import com.okexalpha.strategy.engine.EquityPoint;

/**
 * okx_backtest_run / _result / _trade / _equity 与 okx_strategy 的读写。
 *
 * <p>回测是纯本地计算，不触达交易所；写库全部走主键/RETURNING，失败只影响该批次。</p>
 */
@Repository
public class BacktestRepository {

    private static final String COLUMNS = """
            SELECT id, strategy_id, label, status, bar, start_ms, end_ms, phase, request::text AS request,
                   coin_total, coin_done, combos_total, combos_done, best_result_id, error,
                   created_at, started_at, finished_at
            FROM okx_backtest_run
            """;

    private static final RowMapper<BacktestRunRow> RUN_MAPPER = (rs, rowNum) -> new BacktestRunRow(
            rs.getLong("id"),
            getLong(rs, "strategy_id"),
            rs.getString("label"),
            rs.getString("status"),
            rs.getString("bar"),
            getLong(rs, "start_ms"),
            getLong(rs, "end_ms"),
            rs.getString("phase"),
            rs.getString("request"),
            rs.getInt("coin_total"),
            rs.getInt("coin_done"),
            rs.getInt("combos_total"),
            rs.getInt("combos_done"),
            getLong(rs, "best_result_id"),
            rs.getString("error"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class));

    private static final String RESULT_COLUMNS = """
            SELECT id, run_id, param_key, params::text AS params, inst_id, segment, metrics::text AS metrics,
                   coin_count, trade_count, win_rate, net_pnl, net_pnl_pct, max_drawdown_pct, sharpe,
                   liquidation_count, oos_net_pnl_pct, verdict, rank
            FROM okx_backtest_result
            """;

    private static final RowMapper<BacktestResultRow> RESULT_MAPPER = (rs, rowNum) -> new BacktestResultRow(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getString("param_key"),
            rs.getString("params"),
            rs.getString("inst_id"),
            rs.getString("segment"),
            rs.getString("metrics"),
            rs.getInt("coin_count"),
            rs.getInt("trade_count"),
            getDouble(rs, "win_rate"),
            getDouble(rs, "net_pnl"),
            getDouble(rs, "net_pnl_pct"),
            getDouble(rs, "max_drawdown_pct"),
            getDouble(rs, "sharpe"),
            rs.getInt("liquidation_count"),
            getDouble(rs, "oos_net_pnl_pct"),
            rs.getString("verdict"),
            getInteger(rs, "rank"));

    private final JdbcTemplate jdbcTemplate;

    public BacktestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------- run

    public long createRun(Long strategyId, String label, String bar, long startMs, long endMs, String requestJson,
            int coinTotal, int combosTotal) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO okx_backtest_run (strategy_id, label, status, bar, start_ms, end_ms, params, request,
                                              coin_total, combos_total)
                VALUES (?, ?, 'PENDING', ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                RETURNING id
                """, Long.class, strategyId, label, bar, startMs, endMs, requestJson, requestJson, coinTotal,
                combosTotal);
        if (id == null) {
            throw new IllegalStateException("okx_backtest_run insert returned no id");
        }
        return id;
    }

    public void markRunning(long id) {
        jdbcTemplate.update("""
                UPDATE okx_backtest_run
                SET status = 'RUNNING', started_at = COALESCE(started_at, now()), updated_at = now()
                WHERE id = ?
                """, id);
    }

    public void updateProgress(long id, String phase, int coinDone, int combosDone) {
        jdbcTemplate.update("""
                UPDATE okx_backtest_run
                SET phase = ?, coin_done = ?, combos_done = ?, updated_at = now()
                WHERE id = ?
                """, phase, coinDone, combosDone, id);
    }

    public void finish(long id, String status, String error) {
        jdbcTemplate.update("""
                UPDATE okx_backtest_run
                SET status = ?, error = ?, finished_at = now(), updated_at = now()
                WHERE id = ?
                """, status, error, id);
    }

    public void setBestResult(long id, long resultId) {
        jdbcTemplate.update("""
                UPDATE okx_backtest_run SET best_result_id = ?, updated_at = now() WHERE id = ?
                """, resultId, id);
    }

    public String statusOf(long id) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT status FROM okx_backtest_run WHERE id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int countActive() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM okx_backtest_run WHERE status IN ('PENDING', 'RUNNING')", Integer.class);
        return n == null ? 0 : n;
    }

    /** 客户端取消：只有还没跑完的批次能被取消，返回是否真的改动了状态。 */
    public boolean markCanceled(long id) {
        return jdbcTemplate.update("""
                UPDATE okx_backtest_run
                SET status = 'CANCELED', finished_at = now(), updated_at = now()
                WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                """, id) > 0;
    }

    /**
     * 重启恢复：把上一个进程遗留的 PENDING/RUNNING 批次标成 FAILED。
     *
     * <p>回测是纯内存计算，进程没了就没有可续的进度，留着 RUNNING 会让 {@link #countActive()}
     * 永远非 0，新批次一律 409。</p>
     */
    public int markInterrupted(String error) {
        return jdbcTemplate.update("""
                UPDATE okx_backtest_run
                SET status = 'FAILED', error = ?, finished_at = now(), updated_at = now()
                WHERE status IN ('PENDING', 'RUNNING')
                """, error);
    }

    public BacktestRunRow findRun(long id) {
        List<BacktestRunRow> rows = jdbcTemplate.query(COLUMNS + " WHERE id = ?", RUN_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<BacktestRunRow> listRuns(int limit, int offset) {
        return jdbcTemplate.query(COLUMNS + " ORDER BY id DESC LIMIT ? OFFSET ?", RUN_MAPPER, limit, offset);
    }

    public int countRuns() {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_backtest_run", Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------- result

    /** 一个参数组合的聚合结果（写 okx_backtest_result）。 */
    public record ResultInsert(String paramKey, String paramsJson, String instId, String segment, String metricsJson,
            int coinCount, int tradeCount, Double winRate, Double netPnl, Double netPnlPct, Double maxDrawdownPct,
            Double sharpe, int liquidationCount, Double oosNetPnlPct, String verdict, Integer rank) {
    }

    public long insertResult(long runId, ResultInsert row) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO okx_backtest_result (run_id, param_key, params, inst_id, segment, metrics, coin_count,
                                                 trade_count, win_rate, net_pnl, net_pnl_pct, max_drawdown_pct, sharpe,
                                                 liquidation_count, oos_net_pnl_pct, verdict, rank)
                VALUES (?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, runId, row.paramKey(), row.paramsJson(), row.instId(), row.segment(),
                row.metricsJson(), row.coinCount(), row.tradeCount(), row.winRate(), row.netPnl(), row.netPnlPct(),
                row.maxDrawdownPct(), row.sharpe(), row.liquidationCount(), row.oosNetPnlPct(), row.verdict(),
                row.rank());
        if (id == null) {
            throw new IllegalStateException("okx_backtest_result insert returned no id");
        }
        return id;
    }

    public void insertResults(long runId, List<ResultInsert> rows) {
        for (ResultInsert row : rows) {
            insertResult(runId, row);
        }
    }

    public List<BacktestResultRow> listResults(long runId, int limit) {
        return jdbcTemplate.query(RESULT_COLUMNS + """
                 WHERE run_id = ? AND segment <> 'COIN'
                 ORDER BY rank NULLS LAST, id
                 LIMIT ?
                """, RESULT_MAPPER, runId, limit);
    }

    public List<BacktestResultRow> listCoinResults(long runId, long resultId, int limit) {
        return jdbcTemplate.query(RESULT_COLUMNS + """
                 WHERE run_id = ? AND segment = 'COIN'
                   AND COALESCE(inst_id, '') <> '' AND param_key = (SELECT param_key FROM okx_backtest_result WHERE id = ?)
                 ORDER BY net_pnl_pct DESC NULLS LAST
                 LIMIT ?
                """, RESULT_MAPPER, runId, resultId, limit);
    }

    public List<BacktestResultRow> listCoinResultsByKey(long runId, String paramKey, int limit) {
        return jdbcTemplate.query(RESULT_COLUMNS + """
                 WHERE run_id = ? AND segment = 'COIN' AND param_key = ?
                 ORDER BY net_pnl_pct DESC NULLS LAST
                 LIMIT ?
                """, RESULT_MAPPER, runId, paramKey, limit);
    }

    public BacktestResultRow findResult(long resultId) {
        List<BacktestResultRow> rows = jdbcTemplate.query(RESULT_COLUMNS + " WHERE id = ?", RESULT_MAPPER, resultId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int countResults(long runId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM okx_backtest_result WHERE run_id = ?", Integer.class, runId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------- trades

    public void insertTrades(long runId, long resultId, String instId, List<BacktestTrade> trades) {
        if (trades.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO okx_backtest_trade (run_id, result_id, inst_id, side, layers, avg_entry, max_adverse_pct,
                                                entry_ts_ms, exit_ts_ms, entry_px, exit_px, sz, notional_usd, pnl,
                                                pnl_pct, fee, funding_paid, exit_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, trades, trades.size(), (ps, t) -> {
            ps.setLong(1, runId);
            ps.setLong(2, resultId);
            ps.setString(3, instId);
            ps.setString(4, t.side());
            ps.setInt(5, t.layers());
            ps.setDouble(6, t.avgEntry());
            ps.setDouble(7, t.maxAdversePct());
            ps.setLong(8, t.entryTsMs());
            ps.setLong(9, t.exitTsMs());
            ps.setDouble(10, t.avgEntry());
            ps.setDouble(11, t.exitPx());
            ps.setDouble(12, t.qty());
            ps.setDouble(13, t.notionalUsd());
            ps.setDouble(14, t.pnl());
            ps.setDouble(15, t.pnlPct());
            ps.setDouble(16, t.fee());
            ps.setDouble(17, t.funding());
            ps.setString(18, t.exitReason());
        });
    }

    public List<Map<String, Object>> listTrades(long runId, Long resultId, int limit) {
        if (resultId == null) {
            return jdbcTemplate.queryForList("""
                    SELECT id, result_id, inst_id, side, layers, avg_entry, max_adverse_pct, entry_ts_ms, exit_ts_ms,
                           entry_px, exit_px, sz, notional_usd, pnl, pnl_pct, fee, funding_paid, exit_reason
                    FROM okx_backtest_trade WHERE run_id = ? ORDER BY entry_ts_ms DESC LIMIT ?
                    """, runId, limit);
        }
        return jdbcTemplate.queryForList("""
                SELECT id, result_id, inst_id, side, layers, avg_entry, max_adverse_pct, entry_ts_ms, exit_ts_ms,
                       entry_px, exit_px, sz, notional_usd, pnl, pnl_pct, fee, funding_paid, exit_reason
                FROM okx_backtest_trade WHERE run_id = ? AND result_id = ? ORDER BY entry_ts_ms DESC LIMIT ?
                """, runId, resultId, limit);
    }

    // ------------------------------------------------------------- equity

    public void insertEquity(long resultId, List<EquityPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO okx_backtest_equity (result_id, ts_ms, equity, drawdown_pct)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (result_id, ts_ms) DO NOTHING
                """, points, points.size(), (ps, p) -> {
            ps.setLong(1, resultId);
            ps.setLong(2, p.tsMs());
            ps.setDouble(3, p.equity());
            ps.setDouble(4, p.drawdownPct());
        });
    }

    /** 按参数组合取逐笔成交：聚合行（segment='ALL'）本身不挂明细，明细在同 param_key 的单币行上。 */
    public List<Map<String, Object>> listTradesByParamKey(long runId, String paramKey, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT t.id, t.result_id, t.inst_id, t.side, t.layers, t.avg_entry, t.max_adverse_pct,
                       t.entry_ts_ms, t.exit_ts_ms, t.entry_px, t.exit_px, t.sz, t.notional_usd, t.pnl,
                       t.pnl_pct, t.fee, t.funding_paid, t.exit_reason
                FROM okx_backtest_trade t
                JOIN okx_backtest_result r ON r.id = t.result_id
                WHERE t.run_id = ? AND r.param_key = ?
                ORDER BY t.entry_ts_ms DESC LIMIT ?
                """, runId, paramKey, limit);
    }

    public List<Map<String, Object>> listEquity(long resultId, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT ts_ms, equity, drawdown_pct FROM okx_backtest_equity
                WHERE result_id = ? ORDER BY ts_ms LIMIT ?
                """, resultId, limit);
    }

    // ----------------------------------------------------------- strategy

    /** 把某个回测结果提升为候选策略（同名则覆盖参数，已 PROMOTED 的不回退状态）。 */
    public long promoteStrategy(String name, String paramsJson, String universeJson, double capitalPerCoinUsd,
            long sourceRunId, String note) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO okx_strategy (name, status, params, universe, mode, capital_per_coin_usd, source_run_id, note)
                VALUES (?, 'CANDIDATE', ?::jsonb, ?::jsonb, 'SHADOW', ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET
                    params = EXCLUDED.params,
                    universe = EXCLUDED.universe,
                    capital_per_coin_usd = EXCLUDED.capital_per_coin_usd,
                    source_run_id = EXCLUDED.source_run_id,
                    note = EXCLUDED.note,
                    updated_at = now(),
                    status = CASE WHEN okx_strategy.status = 'PROMOTED' THEN 'PROMOTED' ELSE 'CANDIDATE' END
                RETURNING id
                """, Long.class, name, paramsJson, universeJson, capitalPerCoinUsd, sourceRunId, note);
        if (id == null) {
            throw new IllegalStateException("okx_strategy upsert returned no id");
        }
        return id;
    }

    public Map<String, Object> findStrategy(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, name, status, params::text AS params, universe::text AS universe, mode,
                       capital_per_coin_usd, source_run_id, note, created_at, updated_at
                FROM okx_strategy WHERE id = ?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------ helpers

    private static Long getLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double getDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
