package com.okexalpha.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.OpenInterestRow;
import com.okexalpha.domain.OpenInterestVolumeRow;
import com.okexalpha.domain.RatioRow;
import com.okexalpha.domain.TakerVolumeRow;

/** rubik 统计与持仓量快照的仓储。 */
@Repository
public class StatisticsRepository {

    private static final String RATIO_UPSERT = """
            INSERT INTO okx_long_short_ratio (ratio_type, scope, period, ts_ms, scope_kind, long_short_ratio, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (ratio_type, scope, period, ts_ms) DO UPDATE SET
                scope_kind = EXCLUDED.scope_kind,
                long_short_ratio = EXCLUDED.long_short_ratio,
                updated_at = now()
            """;

    private static final String OI_VOLUME_UPSERT = """
            INSERT INTO okx_open_interest_volume (ccy, period, ts_ms, oi, vol, updated_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (ccy, period, ts_ms) DO UPDATE SET
                oi = EXCLUDED.oi,
                vol = EXCLUDED.vol,
                updated_at = now()
            """;

    private static final String TAKER_UPSERT = """
            INSERT INTO okx_taker_volume (scope_kind, scope, market_type, period, ts_ms, sell_vol, buy_vol, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (scope_kind, scope, market_type, period, ts_ms) DO UPDATE SET
                sell_vol = EXCLUDED.sell_vol,
                buy_vol = EXCLUDED.buy_vol,
                updated_at = now()
            """;

    private static final String OPEN_INTEREST_UPSERT = """
            INSERT INTO okx_open_interest (inst_id, ts_ms, inst_type, oi, oi_ccy, oi_usd, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (inst_id, ts_ms) DO UPDATE SET
                inst_type = EXCLUDED.inst_type,
                oi = EXCLUDED.oi,
                oi_ccy = EXCLUDED.oi_ccy,
                oi_usd = EXCLUDED.oi_usd,
                updated_at = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public StatisticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int upsertRatios(List<RatioRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (RatioRow row : rows) {
            batch.add(new Object[] { row.ratioType(), row.scope(), row.period(), row.tsMs(), row.scopeKind(),
                    row.longShortRatio() });
        }
        jdbcTemplate.batchUpdate(RATIO_UPSERT, batch);
        return rows.size();
    }

    public int upsertOpenInterestVolume(List<OpenInterestVolumeRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (OpenInterestVolumeRow row : rows) {
            batch.add(new Object[] { row.ccy(), row.period(), row.tsMs(), row.oi(), row.vol() });
        }
        jdbcTemplate.batchUpdate(OI_VOLUME_UPSERT, batch);
        return rows.size();
    }

    public int upsertTakerVolume(List<TakerVolumeRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (TakerVolumeRow row : rows) {
            batch.add(new Object[] { row.scopeKind(), row.scope(), nullToEmpty(row.marketType()), row.period(),
                    row.tsMs(), row.sellVol(), row.buyVol() });
        }
        jdbcTemplate.batchUpdate(TAKER_UPSERT, batch);
        return rows.size();
    }

    public int upsertOpenInterest(List<OpenInterestRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (OpenInterestRow row : rows) {
            batch.add(new Object[] { row.instId(), row.tsMs(), row.instType(), row.oi(), row.oiCcy(), row.oiUsd() });
        }
        jdbcTemplate.batchUpdate(OPEN_INTEREST_UPSERT, batch);
        return rows.size();
    }

    /** market_type 参与唯一键且列为 NOT NULL，null 落库会违反约束，此处统一折叠为空串。 */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------ queries

    private static final RowMapper<RatioRow> RATIO_MAPPER = (ResultSet rs, int rowNum) -> new RatioRow(
            rs.getString("ratio_type"),
            rs.getString("scope"),
            rs.getString("period"),
            rs.getLong("ts_ms"),
            rs.getString("scope_kind"),
            rs.getBigDecimal("long_short_ratio"));

    public List<RatioRow> queryRatios(String ratioType, String scope, String period, Long startMs, Long endMs,
            int limit, boolean descending) {
        StringBuilder sql = new StringBuilder("SELECT * FROM okx_long_short_ratio WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (ratioType != null && !ratioType.isBlank()) {
            sql.append(" AND ratio_type = ?");
            args.add(ratioType);
        }
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND scope = ?");
            args.add(scope);
        }
        if (period != null && !period.isBlank()) {
            sql.append(" AND period = ?");
            args.add(period);
        }
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
        return jdbcTemplate.query(sql.toString(), RATIO_MAPPER, args.toArray());
    }

    public List<OpenInterestVolumeRow> queryOpenInterestVolume(String ccy, String period, Long startMs, Long endMs,
            int limit, boolean descending) {
        StringBuilder sql = new StringBuilder("SELECT * FROM okx_open_interest_volume WHERE ccy = ?");
        List<Object> args = new ArrayList<>();
        args.add(ccy);
        if (period != null && !period.isBlank()) {
            sql.append(" AND period = ?");
            args.add(period);
        }
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
        return jdbcTemplate.query(sql.toString(), (ResultSet rs, int rowNum) -> new OpenInterestVolumeRow(
                rs.getString("ccy"), rs.getString("period"), rs.getLong("ts_ms"),
                rs.getBigDecimal("oi"), rs.getBigDecimal("vol")), args.toArray());
    }

    public List<TakerVolumeRow> queryTakerVolume(String scopeKind, String scope, String period, Long startMs,
            Long endMs, int limit, boolean descending) {
        StringBuilder sql = new StringBuilder("SELECT * FROM okx_taker_volume WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (scopeKind != null && !scopeKind.isBlank()) {
            sql.append(" AND scope_kind = ?");
            args.add(scopeKind);
        }
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND scope = ?");
            args.add(scope);
        }
        if (period != null && !period.isBlank()) {
            sql.append(" AND period = ?");
            args.add(period);
        }
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
        return jdbcTemplate.query(sql.toString(), (ResultSet rs, int rowNum) -> new TakerVolumeRow(
                rs.getString("scope_kind"), rs.getString("scope"), rs.getString("market_type"),
                rs.getString("period"), rs.getLong("ts_ms"), rs.getBigDecimal("sell_vol"),
                rs.getBigDecimal("buy_vol")), args.toArray());
    }

    public List<OpenInterestRow> queryOpenInterest(String instId, Long startMs, Long endMs, int limit,
            boolean descending) {
        StringBuilder sql = new StringBuilder("SELECT * FROM okx_open_interest WHERE inst_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(instId);
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
        return jdbcTemplate.query(sql.toString(), (ResultSet rs, int rowNum) -> new OpenInterestRow(
                rs.getString("inst_id"), rs.getString("inst_type"), rs.getLong("ts_ms"),
                rs.getBigDecimal("oi"), rs.getBigDecimal("oi_ccy"), rs.getBigDecimal("oi_usd")), args.toArray());
    }

    public long countRatios() {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_long_short_ratio", Long.class);
        return value == null ? 0L : value;
    }

    public long countOpenInterestVolume() {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_open_interest_volume", Long.class);
        return value == null ? 0L : value;
    }

    public long countTakerVolume() {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_taker_volume", Long.class);
        return value == null ? 0L : value;
    }

    public long countOpenInterest() {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_open_interest", Long.class);
        return value == null ? 0L : value;
    }

    public List<java.util.Map<String, Object>> ratioSummary() {
        return jdbcTemplate.queryForList("""
                SELECT ratio_type, scope, period, count(*) AS rows, min(ts_ms) AS min_ts, max(ts_ms) AS max_ts
                  FROM okx_long_short_ratio GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
                """);
    }

    static BigDecimal orNull(BigDecimal value) {
        return value;
    }
}
