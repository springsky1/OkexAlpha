package com.okexalpha.service.strategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.okexalpha.domain.Bar;

/**
 * 数据缺陷体检（阶段 C / P5）：对某个 K 线周期统计"从上线到现在的覆盖率"。
 *
 * <p>只看 CANDLE（一条价格序列）。口径按<b>上线时间</b>算，而不是按全表首日算，
 * 否则晚上线的新币会被误判成缺数据：</p>
 * <ul>
 *   <li>每合约应有根数 = (全局末日 − max(全局首日, 上线时间对齐到周期)) / 周期 + 1；</li>
 *   <li>覆盖率 = 实际根数 / 应有根数（封顶 100%）；</li>
 *   <li>0 根 → {@code MISSING}；&lt;95% → {@code PARTIAL}；其余 {@code OK}；上线晚于窗口首日的标 {@code LISTED_LATER}；</li>
 *   <li>缺口 = 相邻两根 ts 差 &gt; 周期，missedBars = 差/周期 − 1。</li>
 * </ul>
 */
@Service
public class DataQualityService {

    private final JdbcTemplate jdbcTemplate;

    public DataQualityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 每合约的原始覆盖事实。 */
    public record CoverageFact(String instId, Long listTimeMs, Long firstTs, Long lastTs, long rows) {
    }

    /** 一个缺口。 */
    public record Gap(String instId, long prevTs, long ts, long gapMs, long missedBars) {
    }

    /** 单合约的判定结果。 */
    public record Judged(String instId, long rows, long expected, double pct, String status, Long firstTs, Long lastTs) {
    }

    public Map<String, Object> report(String barCode, int gapLimit) {
        Bar bar = Bar.fromCode(barCode);
        long interval = bar.intervalMs();

        List<CoverageFact> facts = jdbcTemplate.query("""
                WITH s AS (
                    SELECT inst_id, min(ts_ms) AS first_ts, max(ts_ms) AS last_ts, count(*) AS rows
                    FROM okx_kline WHERE bar = ? AND kline_type = 'CANDLE' GROUP BY inst_id
                )
                SELECT i.inst_id, i.list_time_ms, s.first_ts, s.last_ts, COALESCE(s.rows, 0) AS rows
                FROM okx_instrument i LEFT JOIN s ON s.inst_id = i.inst_id
                WHERE i.inst_type = 'SWAP' AND i.state = 'live'
                ORDER BY i.inst_id
                """, (rs, rowNum) -> {
                    long list = rs.getLong("list_time_ms");
                    Long listTime = rs.wasNull() ? null : list;
                    long first = rs.getLong("first_ts");
                    Long firstTs = rs.wasNull() ? null : first;
                    long last = rs.getLong("last_ts");
                    Long lastTs = rs.wasNull() ? null : last;
                    return new CoverageFact(rs.getString("inst_id"), listTime, firstTs, lastTs, rs.getLong("rows"));
                }, barCode);

        long totalRows = facts.stream().mapToLong(CoverageFact::rows).sum();
        long windowFirst = facts.stream().filter(f -> f.firstTs() != null).mapToLong(CoverageFact::firstTs)
                .min().orElse(0L);
        long windowLast = facts.stream().filter(f -> f.lastTs() != null).mapToLong(CoverageFact::lastTs)
                .max().orElse(0L);

        List<Judged> judged = new ArrayList<>(facts.size());
        for (CoverageFact fact : facts) {
            long start = coverageStart(windowFirst, fact.listTimeMs(), interval);
            long expected = expectedRows(start, windowLast, interval);
            double pct = expected == 0 ? (fact.rows() > 0 ? 100d : 100d) : round2(Math.min(100d, fact.rows() * 100d / expected));
            String status;
            if (fact.rows() == 0) {
                status = "MISSING";
            } else if (pct < 95d) {
                status = "PARTIAL";
            } else if (fact.listTimeMs() != null && fact.listTimeMs() > windowFirst) {
                status = "LISTED_LATER";
            } else {
                status = "OK";
            }
            judged.add(new Judged(fact.instId(), fact.rows(), expected, pct, status, fact.firstTs(), fact.lastTs()));
        }

        long missing = judged.stream().filter(j -> j.status().equals("MISSING")).count();
        long partial = judged.stream().filter(j -> j.status().equals("PARTIAL")).count();
        long listedLater = judged.stream().filter(j -> j.status().equals("LISTED_LATER")).count();
        long okCount = judged.stream().filter(j -> j.status().equals("OK")).count();
        double avgPct = round2(judged.stream().mapToDouble(Judged::pct).average().orElse(0d));
        double minPct = judged.stream().mapToDouble(Judged::pct).min().orElse(0d);
        long below90 = judged.stream().filter(j -> j.pct() < 90d).count();
        long below95 = judged.stream().filter(j -> j.pct() < 95d).count();
        long expectedTotal = judged.stream().mapToLong(Judged::expected).sum();

        List<Judged> worst = judged.stream()
                .filter(j -> !j.status().equals("OK") && !j.status().equals("LISTED_LATER"))
                .sorted((a, b) -> {
                    int byPct = Double.compare(a.pct(), b.pct());
                    return byPct != 0 ? byPct : Long.compare(a.rows(), b.rows());
                })
                .limit(20)
                .toList();

        List<Gap> gaps = jdbcTemplate.query("""
                WITH ordered AS (
                    SELECT inst_id, ts_ms, lag(ts_ms) OVER (PARTITION BY inst_id ORDER BY ts_ms) AS prev_ts
                    FROM okx_kline WHERE bar = ? AND kline_type = 'CANDLE'
                )
                SELECT inst_id, prev_ts, ts_ms, ts_ms - prev_ts AS gap_ms
                FROM ordered
                WHERE prev_ts IS NOT NULL AND ts_ms - prev_ts > ?
                ORDER BY gap_ms DESC
                LIMIT ?
                """, (rs, rowNum) -> new Gap(rs.getString("inst_id"), rs.getLong("prev_ts"), rs.getLong("ts_ms"),
                rs.getLong("gap_ms"), missedBars(rs.getLong("gap_ms"), interval)),
                barCode, interval, Math.max(1, Math.min(gapLimit, 200)));

        long gapCount = jdbcTemplate.queryForObject("""
                WITH ordered AS (
                    SELECT inst_id, ts_ms, lag(ts_ms) OVER (PARTITION BY inst_id ORDER BY ts_ms) AS prev_ts
                    FROM okx_kline WHERE bar = ? AND kline_type = 'CANDLE'
                )
                SELECT count(*) FROM ordered WHERE prev_ts IS NOT NULL AND ts_ms - prev_ts > ?
                """, Long.class, barCode, interval);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bar", bar.code());
        out.put("intervalMs", interval);
        out.put("window", Map.of("firstTs", windowFirst, "lastTs", windowLast));
        out.put("instruments", Map.of(
                "liveSwaps", facts.size(),
                "withData", facts.size() - missing,
                "missing", missing,
                "partial", partial,
                "ok", okCount,
                "listedLater", listedLater));
        out.put("rows", Map.of(
                "total", totalRows,
                "expected", expectedTotal,
                "min", judged.isEmpty() ? 0L : judged.stream().mapToLong(Judged::rows).min().orElse(0L),
                "max", judged.isEmpty() ? 0L : judged.stream().mapToLong(Judged::rows).max().orElse(0L)));
        out.put("coverage", Map.of(
                "avgPct", avgPct,
                "minPct", round2(minPct),
                "below90", below90,
                "below95", below95,
                "overallPct", expectedTotal == 0 ? 0d : round2(Math.min(100d, totalRows * 100d / expectedTotal))));
        out.put("gaps", Map.of("count", gapCount, "worst", gaps));
        out.put("worstInstruments", worst);
        return out;
    }

    /** 覆盖起点：全局窗口首日与上线时间取大，并把上线时间向上对齐到周期边界。 */
    public static long coverageStart(long windowFirst, Long listTimeMs, long intervalMs) {
        if (listTimeMs == null || listTimeMs <= windowFirst) {
            return windowFirst;
        }
        long aligned = ((listTimeMs + intervalMs - 1) / intervalMs) * intervalMs;
        return Math.max(windowFirst, aligned);
    }

    /** 从覆盖起点到全局末日应有多少根。 */
    public static long expectedRows(long startTs, long lastTs, long intervalMs) {
        if (intervalMs <= 0 || lastTs < startTs) {
            return 0L;
        }
        return (lastTs - startTs) / intervalMs + 1L;
    }

    /** 单合约覆盖率（按自身时间跨度）。 */
    public static double coveragePct(long firstTs, long lastTs, long rows, long intervalMs) {
        if (intervalMs <= 0 || lastTs < firstTs || rows <= 0) {
            return 0d;
        }
        return round2(Math.min(100d, rows * 100d / expectedRows(firstTs, lastTs, intervalMs)));
    }

    /** 缺口里漏掉的根数。 */
    public static long missedBars(long gapMs, long intervalMs) {
        if (intervalMs <= 0 || gapMs <= 0) {
            return 0L;
        }
        return Math.max(0L, gapMs / intervalMs - 1L);
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
