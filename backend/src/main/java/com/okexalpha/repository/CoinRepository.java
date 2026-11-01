package com.okexalpha.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** okx_coin：由 okx_instrument 全量重建（派生表）。 */
@Repository
public class CoinRepository {

    private static final String REBUILD = """
            INSERT INTO okx_coin (coin, channels, roles, quote_ccys, inst_count,
                spot_inst_count, swap_inst_count, futures_inst_count, option_inst_count, margin_inst_count, updated_at)
            WITH pairs AS (
                SELECT base_ccy AS coin, inst_id, inst_type, quote_ccy
                  FROM okx_instrument WHERE base_ccy IS NOT NULL AND base_ccy <> ''
                UNION ALL
                SELECT quote_ccy, inst_id, inst_type, quote_ccy
                  FROM okx_instrument WHERE quote_ccy IS NOT NULL AND quote_ccy <> ''
                UNION ALL
                SELECT settle_ccy, inst_id, inst_type, quote_ccy
                  FROM okx_instrument WHERE settle_ccy IS NOT NULL AND settle_ccy <> ''
            ), roles AS (
                SELECT base_ccy AS coin, 'BASE' AS role
                  FROM okx_instrument WHERE base_ccy IS NOT NULL AND base_ccy <> ''
                UNION ALL
                SELECT quote_ccy, 'QUOTE'
                  FROM okx_instrument WHERE quote_ccy IS NOT NULL AND quote_ccy <> ''
                UNION ALL
                SELECT settle_ccy, 'SETTLE'
                  FROM okx_instrument WHERE settle_ccy IS NOT NULL AND settle_ccy <> ''
            )
            SELECT p.coin,
                   array_agg(DISTINCT p.inst_type) AS channels,
                   (SELECT array_agg(DISTINCT r.role) FROM roles r WHERE r.coin = p.coin) AS roles,
                   array_remove(array_agg(DISTINCT p.quote_ccy), NULL) AS quote_ccys,
                   count(DISTINCT p.inst_id) AS inst_count,
                   count(DISTINCT p.inst_id) FILTER (WHERE p.inst_type = 'SPOT') AS spot_inst_count,
                   count(DISTINCT p.inst_id) FILTER (WHERE p.inst_type = 'SWAP') AS swap_inst_count,
                   count(DISTINCT p.inst_id) FILTER (WHERE p.inst_type = 'FUTURES') AS futures_inst_count,
                   count(DISTINCT p.inst_id) FILTER (WHERE p.inst_type = 'OPTION') AS option_inst_count,
                   count(DISTINCT p.inst_id) FILTER (WHERE p.inst_type = 'MARGIN') AS margin_inst_count,
                   now()
              FROM pairs p
             GROUP BY p.coin
            """;

    private final JdbcTemplate jdbcTemplate;

    public CoinRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 整体重建 okx_coin，返回币种数。 */
    public int rebuild() {
        jdbcTemplate.update("DELETE FROM okx_coin");
        return jdbcTemplate.update(REBUILD);
    }

    public long count() {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM okx_coin", Long.class);
        return value == null ? 0L : value;
    }

    public List<CoinRecord> findAll(String channel, String query, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM okx_coin WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (channel != null && !channel.isBlank()) {
            sql.append(" AND ? = ANY (channels)");
            args.add(channel);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND coin ILIKE ?");
            args.add("%" + query + "%");
        }
        sql.append(" ORDER BY inst_count DESC, coin LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), COIN_MAPPER, args.toArray());
    }

    private static final RowMapper<CoinRecord> COIN_MAPPER = (ResultSet rs, int rowNum) -> new CoinRecord(
            rs.getString("coin"),
            toStringArray(rs.getArray("channels")),
            toStringArray(rs.getArray("roles")),
            toStringArray(rs.getArray("quote_ccys")),
            rs.getInt("inst_count"),
            rs.getInt("spot_inst_count"),
            rs.getInt("swap_inst_count"),
            rs.getInt("futures_inst_count"),
            rs.getInt("option_inst_count"),
            rs.getInt("margin_inst_count"));

    private static List<String> toStringArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }

    public record CoinRecord(
            String coin,
            List<String> channels,
            List<String> roles,
            List<String> quoteCcys,
            int instCount,
            int spotInstCount,
            int swapInstCount,
            int futuresInstCount,
            int optionInstCount,
            int marginInstCount) {
    }
}
