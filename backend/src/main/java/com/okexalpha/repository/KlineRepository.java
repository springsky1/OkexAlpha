package com.okexalpha.repository;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.KlineRow;

@Repository
public class KlineRepository {

    /** 近似行数缓存时长：状态页 5 秒轮询，30 秒内复用同一个估算值即可。 */
    private static final long COUNT_CACHE_MS = 30_000L;

    private volatile long countCache = -1L;
    private volatile long countCachedAt = 0L;

    /**
     * 幂等写入：
     * <ul>
     *   <li>ON CONFLICT 主键冲突时覆盖为新值，重复补拉不会新增行</li>
     *   <li>WHERE (... ) IS DISTINCT FROM (...) ：内容完全相同的行直接跳过 UPDATE，
     *       避免重复补拉把表写膨胀（死元组）——这是长时序表最容易被忽略的膨胀来源</li>
     * </ul>
     */
    private static final String UPSERT = """
            INSERT INTO okx_kline (kline_type, inst_id, bar, ts_ms, inst_type, open, high, low, close,
                vol, vol_ccy, vol_ccy_quote, confirm, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (kline_type, inst_id, bar, ts_ms) DO UPDATE SET
                inst_type = EXCLUDED.inst_type,
                open = EXCLUDED.open,
                high = EXCLUDED.high,
                low = EXCLUDED.low,
                close = EXCLUDED.close,
                vol = EXCLUDED.vol,
                vol_ccy = EXCLUDED.vol_ccy,
                vol_ccy_quote = EXCLUDED.vol_ccy_quote,
                confirm = EXCLUDED.confirm,
                updated_at = now()
            WHERE (okx_kline.inst_type, okx_kline.open, okx_kline.high, okx_kline.low, okx_kline.close,
                   okx_kline.vol, okx_kline.vol_ccy, okx_kline.vol_ccy_quote, okx_kline.confirm)
                IS DISTINCT FROM
                  (EXCLUDED.inst_type, EXCLUDED.open, EXCLUDED.high, EXCLUDED.low, EXCLUDED.close,
                   EXCLUDED.vol, EXCLUDED.vol_ccy, EXCLUDED.vol_ccy_quote, EXCLUDED.confirm)
            """;

    private static final RowMapper<KlineRow> ROW_MAPPER = (ResultSet rs, int rowNum) -> new KlineRow(
            rs.getString("kline_type"),
            rs.getString("inst_id"),
            rs.getString("bar"),
            rs.getLong("ts_ms"),
            rs.getString("inst_type"),
            rs.getObject("open", Double.class),
            rs.getObject("high", Double.class),
            rs.getObject("low", Double.class),
            rs.getObject("close", Double.class),
            rs.getObject("vol", Double.class),
            rs.getObject("vol_ccy", Double.class),
            rs.getObject("vol_ccy_quote", Double.class),
            Boolean.TRUE.equals(rs.getObject("confirm", Boolean.class)));

    private final JdbcTemplate jdbcTemplate;

    public KlineRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int upsertAll(List<KlineRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (KlineRow row : rows) {
            batch.add(new Object[] { row.klineType(), row.instId(), row.bar(), row.tsMs(), row.instType(),
                    row.open(), row.high(), row.low(), row.close(), row.vol(), row.volCcy(), row.volCcyQuote(),
                    row.confirm() });
        }
        jdbcTemplate.batchUpdate(UPSERT, batch);
        return rows.size();
    }

    public List<KlineRow> query(String instId, String bar, String klineType, Long startMs, Long endMs,
            int limit, boolean descending) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM okx_kline WHERE inst_id = ? AND bar = ? AND kline_type = ?");
        List<Object> args = new ArrayList<>();
        args.add(instId);
        args.add(bar);
        args.add(klineType);
        if (startMs != null) {
            sql.append(" AND ts_ms >= ?");
            args.add(startMs);
        }
        if (endMs != null) {
            sql.append(" AND ts_ms <= ?");
            args.add(endMs);
        }
        sql.append(" ORDER BY ts_ms ").append(descending ? "DESC" : "ASC").append(" LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /** 窗口内已存在 K 线的覆盖率：最早/最晚 ts 与行数（无数据时 minTs/maxTs 为 null、rows 为 0）。 */
    public record Coverage(Long minTs, Long maxTs, long rows) {
    }

    /**
     * 查某个品种某个周期在 [fromMs, toMs] 内已有哪些 K 线。
     *
     * <p>给补拉前判断「还缺哪些区间」用（见 {@code KlineCoverage#plan}）。聚合全部落在主键
     * (kline_type, inst_id, bar, ts_ms) 的范围扫描里，每个 task 只查一次，代价远小于它省下的翻页；
     * 与 {@link #count()} 不同，这里必须带时间窗口，否则就是上亿行的全表扫描。</p>
     */
    public Coverage coverage(String klineType, String instId, String bar, long fromMs, long toMs) {
        List<Coverage> rows = jdbcTemplate.query("""
                SELECT MIN(ts_ms) AS min_ts, MAX(ts_ms) AS max_ts, COUNT(*) AS row_count
                  FROM okx_kline
                 WHERE kline_type = ? AND inst_id = ? AND bar = ? AND ts_ms >= ? AND ts_ms <= ?
                """,
                (rs, rowNum) -> new Coverage(rs.getObject("min_ts", Long.class), rs.getObject("max_ts", Long.class),
                        rs.getLong("row_count")),
                klineType, instId, bar, fromMs, toMs);
        return rows.isEmpty() ? new Coverage(null, null, 0L) : rows.get(0);
    }

    /**
     * 近似总行数。
     *
     * <p>okx_kline 是 TimescaleDB hypertable，装载期已达上亿行，{@code count(*)} 要全表扫（分钟级），
     * 把 Hikari 连接和磁盘 IO 长期占满，会连带拖死 {@code /api/v1/sync/status} 之类的轻量接口
     * （前端 5 秒轮询一次，几分钟内就能堆出 20+ 条并发全表扫描）。这里改用 TimescaleDB 的
     * {@code approximate_row_count()}（读取分块 reltuples，毫秒级），并做 30 秒缓存：
     * 状态页展示用估算值足够，精确行数请用 {@code SELECT count(*) FROM okx_kline} 或
     * 存储页的精确统计。</p>
     */
    public long count() {
        long now = System.currentTimeMillis();
        long cached = countCache;
        if (cached >= 0L && now - countCachedAt < COUNT_CACHE_MS) {
            return cached;
        }
        long value = estimateCount();
        countCache = value;
        countCachedAt = now;
        return value;
    }

    private long estimateCount() {
        try {
            Long approx = jdbcTemplate.queryForObject("SELECT approximate_row_count('okx_kline')", Long.class);
            if (approx != null && approx >= 0L) {
                return approx;
            }
        } catch (DataAccessException ignored) {
            // timescaledb_toolkit 未安装时退回分块 reltuples 求和
        }
        try {
            Long chunkSum = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(sum(c.reltuples), 0)::bigint
                      FROM timescaledb_information.chunks ch
                      JOIN pg_namespace n ON n.nspname = ch.chunk_schema
                      JOIN pg_class c ON c.relname = ch.chunk_name AND c.relnamespace = n.oid
                     WHERE ch.hypertable_name = 'okx_kline'
                    """, Long.class);
            if (chunkSum != null && chunkSum > 0L) {
                return chunkSum;
            }
        } catch (DataAccessException ignored) {
            // 不是 hypertable 时退回父表 reltuples
        }
        Long estimate = jdbcTemplate.queryForObject(
                "SELECT reltuples::bigint FROM pg_class WHERE relname = 'okx_kline' AND relkind = 'r'", Long.class);
        return estimate == null || estimate < 0L ? 0L : estimate;
    }

    // 注意：这里曾经有一个 summary()（对整张 hypertable 做 GROUP BY kline_type, bar），
    // 无调用方且在上亿行时是分钟级全表扫描，属于随时会被误用的地雷，已删除。
    // 需要按 bar 的分布统计时，请带时间窗口（例如最近 30 天）并走 (kline_type, inst_id, bar, ts_ms) 索引。
}
