package com.okexalpha.service.strategy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.okexalpha.domain.Bar;
import com.okexalpha.error.BadRequestException;

/**
 * 数据规划（R3）：回答"某段区间、某周期、多少个合约，要拉多少行 / 多少页 / 多少时间 / 多少空间"，
 * 并给出库内已有覆盖率与缺口（用于判断"这一年 1m 要多久"以及补到哪一天）。
 *
 * <p>吞吐与体积口径来自实测（见 docs/STORAGE.md 与 README 的验证记录）：
 * 装载期 1951 行/秒、6.3 页/秒（candles 16/2s 限流下的干净吞吐）、压缩后约 58 B/行、未压缩约 262 B/行。
 * 估算只用于决策，不代表承诺值。</p>
 */
@Service
public class DataPlanService {

    /** 单页最多 300 根（OKX history-candles 上限）。 */
    public static final int MAX_ROWS_PER_PAGE = 300;
    /** 装载期实测行/秒（2026-11-01 job 27 采集）。 */
    public static final double MEASURED_ROWS_PER_SECOND = 1951d;
    /** 装载期实测页/秒（candles 16/2s）。 */
    public static final double MEASURED_PAGES_PER_SECOND = 6.3d;
    /** 压缩后每行字节（TimescaleDB segmentby=bar,kline_type 实测）。 */
    public static final double COMPRESSED_BYTES_PER_ROW = 58d;
    /** 压缩窗口内未压缩每行字节。 */
    public static final double HOT_BYTES_PER_ROW = 262d;
    /** 默认压缩窗口 30 天。 */
    private static final long COMPRESSION_WINDOW_MS = 2_592_000_000L;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final JdbcTemplate jdbcTemplate;

    public DataPlanService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 估算指定区间的补拉成本。
     *
     * @param bar            K 线周期
     * @param klineTypes     CANDLE / MARK（每个类型各拉一遍）
     * @param instrumentCount 合约数
     * @param startMs        区间起点
     * @param endMs          区间终点
     */
    public Estimate estimate(Bar bar, List<String> klineTypes, int instrumentCount, long startMs, long endMs) {
        if (endMs <= startMs) {
            throw new BadRequestException("endMs must be greater than startMs");
        }
        int types = Math.max(1, klineTypes == null || klineTypes.isEmpty() ? 1 : klineTypes.size());
        long windowMs = endMs - startMs;
        long barsPerInstrument = windowMs / bar.intervalMs() + 1;
        long rowsPerInstrument = barsPerInstrument * types;
        long rowsTotal = rowsPerInstrument * Math.max(0, instrumentCount);
        long pagesPerInstrument = ceilDiv(rowsPerInstrument, MAX_ROWS_PER_PAGE);
        long pagesTotal = pagesPerInstrument * Math.max(0, instrumentCount);
        double seconds = rowsTotal / MEASURED_ROWS_PER_SECOND;
        double pagesSeconds = pagesTotal / MEASURED_PAGES_PER_SECOND;
        double serialSeconds = Math.max(seconds, pagesSeconds);
        long hotRows = hotWindowRows(bar, types, instrumentCount, startMs, endMs);
        double hotBytes = hotRows * HOT_BYTES_PER_ROW;
        double coldBytes = Math.max(0, rowsTotal - hotRows) * COMPRESSED_BYTES_PER_ROW;
        return new Estimate(bar.code(), klineTypes == null ? List.of() : List.copyOf(klineTypes), instrumentCount,
                startMs, endMs, windowMs, barsPerInstrument, rowsPerInstrument, rowsTotal,
                MAX_ROWS_PER_PAGE, pagesPerInstrument, pagesTotal,
                MEASURED_ROWS_PER_SECOND, MEASURED_PAGES_PER_SECOND,
                round1(seconds), round2(seconds / 3600d), round2(pagesSeconds / 3600d),
                round2((hotBytes + coldBytes) / 1_073_741_824d), round2(hotBytes / 1_073_741_824d),
                hotRows, round1(hotRows / (double) Math.max(1, rowsTotal) * 100d));
    }

    /** 库里 SWAP 且 state=live 的合约数（估算默认样本量）。 */
    public int swapInstrumentCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM okx_instrument WHERE inst_type = 'SWAP' AND state = 'live'", Long.class);
        return count == null ? 0 : count.intValue();
    }

    /** 库内覆盖情况：逐合约行数 + 首末时间 + 覆盖率；rows 按 inst_id 升序。 */
    public Coverage coverage(Bar bar, String klineType, long startMs, long endMs, List<String> instIds) {
        long expected = (endMs - startMs) / bar.intervalMs() + 1;
        String sql = """
                SELECT inst_id, count(*) AS rows, min(ts_ms) AS first_ts, max(ts_ms) AS last_ts
                FROM okx_kline
                WHERE bar = ? AND kline_type = ? AND ts_ms BETWEEN ? AND ?
                GROUP BY inst_id ORDER BY inst_id
                """;
        Set<String> filter = instIds == null || instIds.isEmpty() ? Set.of() : new LinkedHashSet<>(instIds);
        List<InstCoverage> rows = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            String instId = rs.getString("inst_id");
            if (!filter.isEmpty() && !filter.contains(instId)) {
                return;
            }
            rows.add(toCoverage(instId, rs.getLong("rows"), (Long) rs.getObject("first_ts", Long.class),
                    rs.getObject("last_ts", Long.class), expected));
        }, bar.code(), klineType, startMs, endMs);
        long totalRows = rows.stream().mapToLong(InstCoverage::rows).sum();
        long full = rows.stream().filter(row -> row.coveragePct() >= 99.5).count();
        return new Coverage(bar.code(), klineType, startMs, endMs, ts(startMs), ts(endMs), expected,
                rows.size(), (int) full, totalRows, rows);
    }

    private InstCoverage toCoverage(String instId, long rows, Long firstTs, Long lastTs, long expected) {
        double pct = expected <= 0 ? 0 : Math.min(100d, rows * 100d / expected);
        return new InstCoverage(instId, rows, expected, firstTs, lastTs, ts(firstTs), ts(lastTs),
                round1(pct), rows >= expected * 0.995);
    }

    private long hotWindowRows(Bar bar, int types, int instrumentCount, long startMs, long endMs) {
        long now = Instant.now().toEpochMilli();
        long windowStart = Math.max(startMs, now - COMPRESSION_WINDOW_MS);
        if (windowStart >= endMs) {
            return 0;
        }
        long bars = (endMs - windowStart) / bar.intervalMs() + 1;
        return bars * types * Math.max(0, instrumentCount);
    }

    private static String ts(Long ms) {
        return ms == null ? null : TS.format(Instant.ofEpochMilli(ms));
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    /**
     * 估算结果。
     *
     * @param barsPerInstrument 单合约根数（含两端）
     * @param pagesTotal        总请求页数（每页 ≤300 根）
     * @param secondsEstimate   按行吞吐估算的秒数
     * @param hoursEstimate     按行吞吐估算的小时数
     * @param pagesHoursEstimate 按页吞吐估算的小时数（限流打满时更接近真实值）
     * @param gbAfterCompression 稳态磁盘占用（热窗口未压缩 + 其余压缩）
     * @param gbHotWindow       其中 30 天压缩窗口内的未压缩部分
     * @param hotWindowRowPct   热行占比
     */
    public record Estimate(String bar, List<String> klineTypes, int instrumentCount, long startMs, long endMs,
            long windowMs, long barsPerInstrument, long rowsPerInstrument, long rowsTotal, int maxRowsPerPage,
            long pagesPerInstrument, long pagesTotal, double rowsPerSecond, double pagesPerSecond,
            double secondsEstimate, double hoursEstimate, double pagesHoursEstimate,
            double gbAfterCompression, double gbHotWindow, long hotWindowRows, double hotWindowRowPct) {
    }

    public record Coverage(String bar, String klineType, long startMs, long endMs, String startTime, String endTime,
            long expectedRowsPerInstrument, int instrumentCount, int fullCoverageCount, long rowsTotal,
            List<InstCoverage> instruments) {
    }

    public record InstCoverage(String instId, long rows, long expectedRows, Long firstTs, Long lastTs,
            String firstTime, String lastTime, double coveragePct, boolean complete) {
    }
}
