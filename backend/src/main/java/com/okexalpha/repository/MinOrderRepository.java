package com.okexalpha.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.MinOrderRow;

/** okx_min_order 读写：整表快照，upsert 幂等。 */
@Repository
public class MinOrderRepository {

    private static final String UPSERT = """
            INSERT INTO okx_min_order (
                inst_id, inst_type, settle_ccy, ct_val, ct_val_ccy, ct_mult, lot_sz, min_sz, tick_sz,
                ref_px, notional_per_contract_usd, min_notional_usd, min_lots, tradable, note, refreshed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (inst_id) DO UPDATE SET
                inst_type = EXCLUDED.inst_type,
                settle_ccy = EXCLUDED.settle_ccy,
                ct_val = EXCLUDED.ct_val,
                ct_val_ccy = EXCLUDED.ct_val_ccy,
                ct_mult = EXCLUDED.ct_mult,
                lot_sz = EXCLUDED.lot_sz,
                min_sz = EXCLUDED.min_sz,
                tick_sz = EXCLUDED.tick_sz,
                ref_px = EXCLUDED.ref_px,
                notional_per_contract_usd = EXCLUDED.notional_per_contract_usd,
                min_notional_usd = EXCLUDED.min_notional_usd,
                min_lots = EXCLUDED.min_lots,
                tradable = EXCLUDED.tradable,
                note = EXCLUDED.note,
                refreshed_at = now()
            """;

    private static final String COLUMNS = """
            SELECT inst_id, inst_type, settle_ccy, ct_val, ct_val_ccy, ct_mult, lot_sz, min_sz, tick_sz,
                   ref_px, notional_per_contract_usd, min_notional_usd, min_lots, tradable, note
            FROM okx_min_order
            """;

    private static final RowMapper<MinOrderRow> ROW_MAPPER = (rs, rowNum) -> new MinOrderRow(
            rs.getString("inst_id"),
            rs.getString("inst_type"),
            rs.getString("settle_ccy"),
            getDouble(rs, "ct_val"),
            rs.getString("ct_val_ccy"),
            getDouble(rs, "ct_mult"),
            getDouble(rs, "lot_sz"),
            getDouble(rs, "min_sz"),
            getDouble(rs, "tick_sz"),
            getDouble(rs, "ref_px"),
            getDouble(rs, "notional_per_contract_usd"),
            getDouble(rs, "min_notional_usd"),
            getDouble(rs, "min_lots"),
            rs.getBoolean("tradable"),
            rs.getString("note"));

    private final JdbcTemplate jdbcTemplate;

    public MinOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int upsertAll(List<MinOrderRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        jdbcTemplate.batchUpdate(UPSERT, rows, rows.size(), (ps, row) -> {
            ps.setString(1, row.instId());
            ps.setString(2, row.instType());
            ps.setString(3, row.settleCcy());
            setDouble(ps, 4, row.ctVal());
            ps.setString(5, row.ctValCcy());
            setDouble(ps, 6, row.ctMult());
            setDouble(ps, 7, row.lotSz());
            setDouble(ps, 8, row.minSz());
            setDouble(ps, 9, row.tickSz());
            setDouble(ps, 10, row.refPx());
            setDouble(ps, 11, row.notionalPerContractUsd());
            setDouble(ps, 12, row.minNotionalUsd());
            setDouble(ps, 13, row.minLots());
            ps.setBoolean(14, row.tradable());
            ps.setString(15, row.note());
        });
        return rows.size();
    }

    public List<MinOrderRow> findAll(String instType, Boolean tradable, int limit, int offset) {
        StringBuilder sql = new StringBuilder(COLUMNS);
        List<Object> args = new java.util.ArrayList<>();
        sql.append(" WHERE 1 = 1");
        if (instType != null && !instType.isBlank()) {
            sql.append(" AND inst_type = ?");
            args.add(instType);
        }
        if (tradable != null) {
            sql.append(" AND tradable = ?");
            args.add(tradable);
        }
        sql.append(" ORDER BY inst_id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public MinOrderRow findById(String instId) {
        List<MinOrderRow> rows = jdbcTemplate.query(COLUMNS + " WHERE inst_id = ?", ROW_MAPPER, instId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_min_order", Long.class);
        return count == null ? 0L : count;
    }

    public long countTradable() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_min_order WHERE tradable", Long.class);
        return count == null ? 0L : count;
    }

    private static Double getDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static void setDouble(java.sql.PreparedStatement ps, int index, Double value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }
}
