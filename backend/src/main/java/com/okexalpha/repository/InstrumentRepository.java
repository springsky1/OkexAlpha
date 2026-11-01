package com.okexalpha.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.InstrumentRow;

@Repository
public class InstrumentRepository {

    private static final String UPSERT = """
            INSERT INTO okx_instrument (inst_id, inst_type, inst_family, uly, base_ccy, quote_ccy, settle_ccy,
                ct_type, ct_val, ct_val_ccy, ct_mult, lever, tick_sz, lot_sz, min_sz, list_time_ms, exp_time_ms,
                state, rule_type, category, inst_category, option_type, alias, raw, last_seen_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), now())
            ON CONFLICT (inst_id) DO UPDATE SET
                inst_type = EXCLUDED.inst_type,
                inst_family = EXCLUDED.inst_family,
                uly = EXCLUDED.uly,
                base_ccy = EXCLUDED.base_ccy,
                quote_ccy = EXCLUDED.quote_ccy,
                settle_ccy = EXCLUDED.settle_ccy,
                ct_type = EXCLUDED.ct_type,
                ct_val = EXCLUDED.ct_val,
                ct_val_ccy = EXCLUDED.ct_val_ccy,
                ct_mult = EXCLUDED.ct_mult,
                lever = EXCLUDED.lever,
                tick_sz = EXCLUDED.tick_sz,
                lot_sz = EXCLUDED.lot_sz,
                min_sz = EXCLUDED.min_sz,
                list_time_ms = EXCLUDED.list_time_ms,
                exp_time_ms = EXCLUDED.exp_time_ms,
                state = EXCLUDED.state,
                rule_type = EXCLUDED.rule_type,
                category = EXCLUDED.category,
                inst_category = EXCLUDED.inst_category,
                option_type = EXCLUDED.option_type,
                alias = EXCLUDED.alias,
                raw = EXCLUDED.raw,
                last_seen_at = now(),
                updated_at = now()
            """;

    private static final RowMapper<InstrumentRow> ROW_MAPPER = InstrumentRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public InstrumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int upsertAll(List<InstrumentRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (InstrumentRow row : rows) {
            batch.add(new Object[] {
                    row.instId(), row.instType(), row.instFamily(), row.uly(), row.baseCcy(), row.quoteCcy(),
                    row.settleCcy(), row.ctType(), row.ctVal(), row.ctValCcy(), row.ctMult(), row.lever(),
                    row.tickSz(), row.lotSz(), row.minSz(), row.listTimeMs(), row.expTimeMs(), row.state(),
                    row.ruleType(), row.category(), row.instCategory(), row.optionType(), row.alias(), row.raw() });
        }
        jdbcTemplate.batchUpdate(UPSERT, batch);
        return rows.size();
    }

    public long count() {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_instrument", Long.class);
        return value == null ? 0L : value;
    }

    public List<InstrumentRow> findAll(String instType, String baseCcy, String state, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM okx_instrument WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (instType != null && !instType.isBlank()) {
            sql.append(" AND inst_type = ?");
            args.add(instType);
        }
        if (baseCcy != null && !baseCcy.isBlank()) {
            sql.append(" AND base_ccy = ?");
            args.add(baseCcy);
        }
        if (state != null && !state.isBlank()) {
            sql.append(" AND state = ?");
            args.add(state);
        }
        sql.append(" ORDER BY inst_id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public InstrumentRow findById(String instId) {
        List<InstrumentRow> rows = jdbcTemplate.query("SELECT * FROM okx_instrument WHERE inst_id = ?", ROW_MAPPER, instId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> distinctFamilies() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT inst_family FROM okx_instrument WHERE inst_family IS NOT NULL AND inst_family <> '' ORDER BY 1",
                String.class);
    }

    /** 按品种取 instId（可按 base_ccy 过滤），用于补拉任务的实例展开。 */
    public List<String> findInstIds(String instType, String baseCcy, int limit) {
        StringBuilder sql = new StringBuilder("SELECT inst_id FROM okx_instrument WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (instType != null && !instType.isBlank()) {
            sql.append(" AND inst_type = ?");
            args.add(instType);
        }
        if (baseCcy != null && !baseCcy.isBlank()) {
            sql.append(" AND base_ccy = ?");
            args.add(baseCcy);
        }
        sql.append(" AND (state = 'live' OR state IS NULL) ORDER BY inst_id LIMIT ?");
        args.add(limit);
        return jdbcTemplate.queryForList(sql.toString(), String.class, args.toArray());
    }

    /** 某品种下出现过的 base ccy（多空比按币种维度补拉时用）。 */
    public List<String> distinctBaseCcys(String instType, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT base_ccy FROM okx_instrument
                 WHERE inst_type = ? AND base_ccy IS NOT NULL AND base_ccy <> ''
                 GROUP BY base_ccy ORDER BY count(*) DESC, base_ccy LIMIT ?
                """, String.class, instType, limit);
    }

    public List<java.util.Map<String, Object>> countByInstType() {
        return jdbcTemplate.queryForList("SELECT inst_type, count(*) AS cnt FROM okx_instrument GROUP BY 1 ORDER BY 2 DESC");
    }

    static InstrumentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Long listTime = rs.getObject("list_time_ms") == null ? null : rs.getLong("list_time_ms");
        Long expTime = rs.getObject("exp_time_ms") == null ? null : rs.getLong("exp_time_ms");
        return new InstrumentRow(
                rs.getString("inst_id"),
                rs.getString("inst_type"),
                rs.getString("inst_family"),
                rs.getString("uly"),
                rs.getString("base_ccy"),
                rs.getString("quote_ccy"),
                rs.getString("settle_ccy"),
                rs.getString("ct_type"),
                rs.getBigDecimal("ct_val"),
                rs.getString("ct_val_ccy"),
                rs.getBigDecimal("ct_mult"),
                rs.getBigDecimal("lever"),
                rs.getBigDecimal("tick_sz"),
                rs.getBigDecimal("lot_sz"),
                rs.getBigDecimal("min_sz"),
                listTime,
                expTime,
                rs.getString("state"),
                rs.getString("rule_type"),
                rs.getString("category"),
                rs.getString("inst_category"),
                rs.getString("option_type"),
                rs.getString("alias"),
                rs.getString("raw"));
    }
}
