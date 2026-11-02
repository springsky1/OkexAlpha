package com.okexalpha.repository;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.FundingRateRow;

@Repository
public class FundingRateRepository {

    /** 幂等写入 + 内容相同的行不 UPDATE（避免重复补拉造成的死元组膨胀）。 */
    private static final String UPSERT = """
            INSERT INTO okx_funding_rate (inst_id, funding_time_ms, inst_type, funding_rate, realized_rate,
                method, formula_type, settled, premium, interest_rate, next_funding_time_ms, impact_value,
                min_funding_rate, max_funding_rate, ts_ms, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (inst_id, funding_time_ms) DO UPDATE SET
                inst_type = EXCLUDED.inst_type,
                funding_rate = EXCLUDED.funding_rate,
                realized_rate = EXCLUDED.realized_rate,
                method = EXCLUDED.method,
                formula_type = EXCLUDED.formula_type,
                settled = EXCLUDED.settled,
                premium = EXCLUDED.premium,
                interest_rate = EXCLUDED.interest_rate,
                next_funding_time_ms = EXCLUDED.next_funding_time_ms,
                impact_value = EXCLUDED.impact_value,
                min_funding_rate = EXCLUDED.min_funding_rate,
                max_funding_rate = EXCLUDED.max_funding_rate,
                ts_ms = EXCLUDED.ts_ms,
                updated_at = now()
            WHERE (okx_funding_rate.inst_type, okx_funding_rate.funding_rate, okx_funding_rate.realized_rate,
                   okx_funding_rate.method, okx_funding_rate.formula_type, okx_funding_rate.settled,
                   okx_funding_rate.premium, okx_funding_rate.interest_rate,
                   okx_funding_rate.next_funding_time_ms, okx_funding_rate.impact_value,
                   okx_funding_rate.min_funding_rate, okx_funding_rate.max_funding_rate, okx_funding_rate.ts_ms)
                IS DISTINCT FROM
                  (EXCLUDED.inst_type, EXCLUDED.funding_rate, EXCLUDED.realized_rate,
                   EXCLUDED.method, EXCLUDED.formula_type, EXCLUDED.settled,
                   EXCLUDED.premium, EXCLUDED.interest_rate,
                   EXCLUDED.next_funding_time_ms, EXCLUDED.impact_value,
                   EXCLUDED.min_funding_rate, EXCLUDED.max_funding_rate, EXCLUDED.ts_ms)
            """;

    private static final RowMapper<FundingRateRow> ROW_MAPPER = (ResultSet rs, int rowNum) -> new FundingRateRow(
            rs.getString("inst_id"),
            rs.getString("inst_type"),
            rs.getLong("funding_time_ms"),
            rs.getObject("funding_rate", Double.class),
            rs.getObject("realized_rate", Double.class),
            rs.getString("method"),
            rs.getString("formula_type"),
            rs.getBoolean("settled"),
            rs.getObject("premium", Double.class),
            rs.getObject("interest_rate", Double.class),
            rs.getObject("next_funding_time_ms", Long.class),
            rs.getObject("impact_value", Double.class),
            rs.getObject("min_funding_rate", Double.class),
            rs.getObject("max_funding_rate", Double.class),
            rs.getObject("ts_ms", Long.class));

    private final JdbcTemplate jdbcTemplate;

    public FundingRateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int upsertAll(List<FundingRateRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (FundingRateRow row : rows) {
            batch.add(new Object[] { row.instId(), row.fundingTimeMs(), row.instType(), row.fundingRate(),
                    row.realizedRate(), row.method(), row.formulaType(), row.settled(), row.premium(),
                    row.interestRate(), row.nextFundingTimeMs(), row.impactValue(), row.minFundingRate(),
                    row.maxFundingRate(), row.tsMs() });
        }
        jdbcTemplate.batchUpdate(UPSERT, batch);
        return rows.size();
    }

    public List<FundingRateRow> query(String instId, Long startMs, Long endMs, int limit, boolean descending) {
        StringBuilder sql = new StringBuilder("SELECT * FROM okx_funding_rate WHERE inst_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(instId);
        if (startMs != null) {
            sql.append(" AND funding_time_ms >= ?");
            args.add(startMs);
        }
        if (endMs != null) {
            sql.append(" AND funding_time_ms <= ?");
            args.add(endMs);
        }
        sql.append(" ORDER BY funding_time_ms ").append(descending ? "DESC" : "ASC").append(" LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long count() {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_funding_rate", Long.class);
        return value == null ? 0L : value;
    }
}
