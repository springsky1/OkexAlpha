package com.okexalpha.service.strategy;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.okexalpha.domain.Bar;
import com.okexalpha.strategy.engine.PriceBar;

/** 从 okx_kline 读回测用的单币序列（只读 CANDLE，索引 (inst_id, bar, kline_type, ts_ms) 命中）。 */
@Service
public class BacktestSeriesLoader {

    private static final String SERIES_SQL = """
            SELECT ts_ms, open, high, low, close
            FROM okx_kline
            WHERE inst_id = ? AND bar = ? AND kline_type = 'CANDLE'
              AND ts_ms >= ? AND ts_ms <= ?
            ORDER BY ts_ms
            """;

    private static final String LIVE_SWAPS_SQL = """
            SELECT inst_id FROM okx_instrument
            WHERE inst_type = 'SWAP' AND state = 'live'
            ORDER BY inst_id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public BacktestSeriesLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @throws com.okexalpha.error.BadRequestException bar 不在白名单时 */
    public List<PriceBar> load(String instId, String bar, long startMs, long endMs) {
        Bar.fromCode(bar);
        return jdbcTemplate.query(SERIES_SQL, (rs, rowNum) -> new PriceBar(
                rs.getLong("ts_ms"),
                rs.getDouble("open"),
                rs.getDouble("high"),
                rs.getDouble("low"),
                rs.getDouble("close")), instId, bar, startMs, endMs);
    }

    public List<String> liveSwaps(int limit) {
        return jdbcTemplate.queryForList(LIVE_SWAPS_SQL, String.class, limit);
    }

    public int liveSwapCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM okx_instrument WHERE inst_type = 'SWAP' AND state = 'live'", Integer.class);
        return n == null ? 0 : n;
    }

    /** 库内该币该周期的行数（用于提交前判断数据是否已就绪）。 */
    public int countBars(String instId, String bar) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM okx_kline
                WHERE inst_id = ? AND bar = ? AND kline_type = 'CANDLE'
                """, Integer.class, instId, bar);
        return n == null ? 0 : n;
    }
}
