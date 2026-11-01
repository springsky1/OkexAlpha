package com.okexalpha.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.KlineRow;

@Repository
public class KlineRepository {

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
            """;

    private static final RowMapper<KlineRow> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        java.math.BigDecimal vol = rs.getBigDecimal("vol");
        return new KlineRow(
                rs.getString("kline_type"),
                rs.getString("inst_id"),
                rs.getString("bar"),
                rs.getLong("ts_ms"),
                rs.getString("inst_type"),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                vol,
                rs.getBigDecimal("vol_ccy"),
                rs.getBigDecimal("vol_ccy_quote"),
                rs.getObject("confirm") == null ? 0 : rs.getInt("confirm"));
    };

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

    public long count() {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_kline", Long.class);
        return value == null ? 0L : value;
    }

    public List<java.util.Map<String, Object>> summary() {
        return jdbcTemplate.queryForList("""
                SELECT kline_type, bar, count(*) AS rows, min(ts_ms) AS min_ts, max(ts_ms) AS max_ts
                  FROM okx_kline GROUP BY 1, 2 ORDER BY 1, 2
                """);
    }
}
