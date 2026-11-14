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
 *
 * <p>此外还补齐阶段 C 的另外三类校验：C4 合法性（OHLC 关系与非正价格）、
 * C5 时间戳对齐（{@code ts_ms % interval != 0}）、C6 1m→大周期聚合一致性。
 * C3 唯一性由主键保证；C7 新鲜度由 {@code TradingGate} 门禁负责。</p>
 */
@Service
public class DataQualityService {

    /** {@link #report} 里 C4/C5/C6 只扫最近 7 天，避免对 2 亿行的 1m 表做全表聚合。 */
    private static final long RECENT_WINDOW_MS = 7L * 86_400_000L;

    /**
     * {@link #report} 的结果缓存 60 秒。
     *
     * <p>一次 report 要对 okx_kline 做 5 段扫描（覆盖度、缺口、C4 两段、C5、C6），1H 周期十秒上下；
     * 它是「看一眼数据质量」的只读报表，60 秒内的重复点击没有意义，直接复用上一次的结果。</p>
     */
    private static final long REPORT_CACHE_MS = 60_000L;

    /** key = {@code bar|gapLimit} -> [body, fetchedAtMs]。 */
    private final java.util.concurrent.ConcurrentHashMap<String, ReportCacheEntry> reportCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 缓存条目：body 只读（调用方只做 JSON 序列化，不改写）。 */
    private record ReportCacheEntry(Map<String, Object> body, long fetchedAtMs) {
    }

    /**
     * report 的缓存时长：小周期（1m/3m/5m）的全历史聚合要几分钟，缓存放宽到 5 分钟，
     * 避免连点把 3 亿行再扫一遍；其余周期十几秒内，60 秒足够。
     */
    private static long reportCacheTtlMs(long intervalMs) {
        return intervalMs <= Bar.M5.intervalMs() ? 300_000L : REPORT_CACHE_MS;
    }

    /** C5 未对齐样本的固定上限（该接口没有 sampleLimit 入参）。 */
    private static final int ALIGNMENT_SAMPLE_LIMIT = 20;

    /** C4 命中行的谓词（计数与取样共用，避免两处漂移）。 */
    private static final String LEGALITY_PREDICATE = "open <= 0 OR high <= 0 OR low <= 0 OR close <= 0 OR vol < 0"
            + " OR high < low OR high < open OR high < close OR low > open OR low > close";

    /** C6 的 1m 分桶聚合；两个 {@code ?} 依次是目标周期毫秒与 sinceMs。 */
    private static final String AGGREGATION_CTE = """
            WITH m AS (
                SELECT inst_id,
                       ts_ms - (ts_ms % ?) AS bucket,
                       (array_agg(open ORDER BY ts_ms))[1] AS o,
                       (array_agg(close ORDER BY ts_ms DESC))[1] AS c,
                       max(high) AS h,
                       min(low) AS l,
                       count(*) AS n
                FROM okx_kline
                WHERE kline_type = 'CANDLE' AND bar = '1m' AND ts_ms >= ?
                GROUP BY inst_id, bucket
            )
            """;

    /** C6 的 OHLC 不一致谓词（计数与取样共用）。 */
    private static final String AGGREGATION_MISMATCH = "m.o <> k.open OR m.c <> k.close"
            + " OR m.h <> k.high OR m.l <> k.low";

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

    /** C4 合法性命中样本。 */
    public record LegalitySample(String instId, long tsMs, double open, double high, double low, double close,
            double vol, List<String> problems) {
    }

    /** C5 未对齐样本，offsetMs = ts_ms % intervalMs。 */
    public record AlignmentSample(String instId, long tsMs, long offsetMs) {
    }

    /** C6 不一致桶样本：一边是 1m 聚合值，一边是目标周期落库行。 */
    public record AggregationSample(String instId, long bucketTs, long barsInBucket, long expectedBars,
            double aggOpen, double aggHigh, double aggLow, double aggClose,
            double barOpen, double barHigh, double barLow, double barClose, List<String> problems) {
    }

    /** C5 扫描统计。 */
    private record AlignmentStat(long checked, long misaligned, Long earliestTs, Long latestTs) {
    }

    public Map<String, Object> report(String barCode, int gapLimit) {
        Bar bar = Bar.fromCode(barCode);
        long interval = bar.intervalMs();

        String cacheKey = barCode + "|" + gapLimit;
        long nowMs = System.currentTimeMillis();
        ReportCacheEntry cached = reportCache.get(cacheKey);
        if (cached != null && nowMs - cached.fetchedAtMs() < reportCacheTtlMs(interval)) {
            return cached.body();
        }

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

        // 缺口列表与缺口总数原本各扫一遍（同一段 CTE 跑两次，1H 周期单次就是 3 秒多）。这里只扫一次，
        // 用 count(*) OVER () 把总数随行带回；没有缺口时 total 取不到，初值 0 正好是对的。
        long[] gapTotal = {0L};
        List<Gap> gaps = jdbcTemplate.query("""
                WITH ordered AS (
                    SELECT inst_id, ts_ms, lag(ts_ms) OVER (PARTITION BY inst_id ORDER BY ts_ms) AS prev_ts
                    FROM okx_kline WHERE bar = ? AND kline_type = 'CANDLE'
                )
                SELECT inst_id, prev_ts, ts_ms, ts_ms - prev_ts AS gap_ms, count(*) OVER () AS total
                FROM ordered
                WHERE prev_ts IS NOT NULL AND ts_ms - prev_ts > ?
                ORDER BY gap_ms DESC
                LIMIT ?
                """, (rs, rowNum) -> {
                    gapTotal[0] = rs.getLong("total");
                    long gapMs = rs.getLong("gap_ms");
                    return new Gap(rs.getString("inst_id"), rs.getLong("prev_ts"), rs.getLong("ts_ms"), gapMs,
                            missedBars(gapMs, interval));
                }, barCode, interval, Math.max(1, Math.min(gapLimit, 200)));
        long gapCount = gapTotal[0];

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

        // C4/C5/C6 只扫最近 7 天：1m 表 2 亿行，全量聚合会把体检接口拖成分钟级。
        long recentSince = windowLast <= 0 ? 0L : Math.max(0L, windowLast - RECENT_WINDOW_MS);
        String aggregateBar = interval > Bar.M1.intervalMs() ? bar.code() : Bar.H1.code();
        out.put("legality", legality(barCode, recentSince, 5));
        out.put("alignment", alignment(barCode, recentSince));
        out.put("aggregation", aggregation(aggregateBar, recentSince, 5));
        reportCache.put(cacheKey, new ReportCacheEntry(out, System.currentTimeMillis()));
        return out;
    }

    /**
     * C4 合法性：{@code high ≥ low}、{@code high ≥ max(open,close)}、{@code low ≤ min(open,close)}、
     * 四个价格 &gt; 0。返回扫描行数、命中行数与最多 {@code sampleLimit} 条命中样本。
     *
     * @param sinceMs 非空时只扫 {@code ts_ms >= sinceMs}，为空则扫该周期全表
     */
    public Map<String, Object> legality(String barCode, Long sinceMs, int sampleLimit) {
        Bar bar = Bar.fromCode(barCode);
        long since = sinceOrZero(sinceMs);
        int limit = Math.max(1, Math.min(sampleLimit, 200));

        long[] counts = jdbcTemplate.queryForObject("""
                SELECT count(*) AS checked,
                       count(*) FILTER (WHERE %s) AS violations
                FROM okx_kline WHERE kline_type = 'CANDLE' AND bar = ? AND ts_ms >= ?
                """.formatted(LEGALITY_PREDICATE),
                (rs, rowNum) -> new long[] {rs.getLong("checked"), rs.getLong("violations")},
                barCode, since);

        List<LegalitySample> samples = jdbcTemplate.query("""
                SELECT inst_id, ts_ms, open, high, low, close, vol
                FROM okx_kline
                WHERE kline_type = 'CANDLE' AND bar = ? AND ts_ms >= ?
                  AND (%s)
                ORDER BY ts_ms, inst_id
                LIMIT ?
                """.formatted(LEGALITY_PREDICATE),
                (rs, rowNum) -> new LegalitySample(rs.getString("inst_id"), rs.getLong("ts_ms"),
                        rs.getDouble("open"), rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close"),
                        rs.getDouble("vol"),
                        ohlcProblems(rs.getDouble("open"), rs.getDouble("high"), rs.getDouble("low"),
                                rs.getDouble("close"), rs.getDouble("vol"))),
                barCode, since, limit);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bar", bar.code());
        out.put("sinceMs", since);
        out.put("checked", counts == null ? 0L : counts[0]);
        out.put("violations", counts == null ? 0L : counts[1]);
        out.put("samples", samples);
        return out;
    }

    /**
     * C5 时间戳对齐：{@code ts_ms % intervalMs != 0} 的行数、最早/最晚未对齐时间与最早若干样本。
     *
     * <p>只做一次取模，不做时区换算：{@code ts_ms} 是 UTC epoch 毫秒，所有白名单周期的边界
     * 都是 epoch 的整数倍（1D 由 OKX 固定为 UTC 00:00 切日）。</p>
     */
    public Map<String, Object> alignment(String barCode, Long sinceMs) {
        Bar bar = Bar.fromCode(barCode);
        long interval = bar.intervalMs();
        long since = sinceOrZero(sinceMs);

        AlignmentStat stat = jdbcTemplate.queryForObject("""
                SELECT count(*) AS checked,
                       count(*) FILTER (WHERE ts_ms % ? <> 0) AS misaligned,
                       min(ts_ms) FILTER (WHERE ts_ms % ? <> 0) AS earliest_bad,
                       max(ts_ms) FILTER (WHERE ts_ms % ? <> 0) AS latest_bad
                FROM okx_kline WHERE kline_type = 'CANDLE' AND bar = ? AND ts_ms >= ?
                """,
                (rs, rowNum) -> {
                    long earliest = rs.getLong("earliest_bad");
                    Long earliestTs = rs.wasNull() ? null : earliest;
                    long latest = rs.getLong("latest_bad");
                    Long latestTs = rs.wasNull() ? null : latest;
                    return new AlignmentStat(rs.getLong("checked"), rs.getLong("misaligned"), earliestTs,
                            latestTs);
                },
                interval, interval, interval, barCode, since);

        List<AlignmentSample> samples = jdbcTemplate.query("""
                SELECT inst_id, ts_ms
                FROM okx_kline
                WHERE kline_type = 'CANDLE' AND bar = ? AND ts_ms >= ? AND ts_ms % ? <> 0
                ORDER BY ts_ms, inst_id
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    long ts = rs.getLong("ts_ms");
                    return new AlignmentSample(rs.getString("inst_id"), ts, ts % interval);
                },
                barCode, since, interval, ALIGNMENT_SAMPLE_LIMIT);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bar", bar.code());
        out.put("intervalMs", interval);
        out.put("sinceMs", since);
        out.put("checked", stat == null ? 0L : stat.checked());
        out.put("misaligned", stat == null ? 0L : stat.misaligned());
        out.put("earliestTs", stat == null ? null : stat.earliestTs());
        out.put("latestTs", stat == null ? null : stat.latestTs());
        out.put("samples", samples);
        return out;
    }

    /**
     * C6 1m→大周期聚合一致性：把 1m 按 {@code ts_ms - (ts_ms % targetInterval)} 分桶，
     * 聚合出 open(桶内最小 ts 的 open)/high(max)/low(min)/close(桶内最大 ts 的 close)，
     * 与同 inst_id、同桶起始 ts 的目标周期落库行逐字段比较。
     *
     * <p>只比较目标周期行存在、且桶内 1m 根数达到期望根数 90% 的桶：
     * 未补全的一段缺根，聚合值天然对不上，拿它判错会把"还没补"误报成"聚合错"。</p>
     *
     * @param targetBar   目标周期，必须大于 1m（如 {@code 1H}）
     * @param sinceMs     非空时只扫 {@code ts_ms >= sinceMs} 的 1m 行
     * @param sampleLimit 最多返回多少条不一致样本
     * @throws IllegalArgumentException targetBar 解析失败或不大于 1m
     */
    public Map<String, Object> aggregation(String targetBar, Long sinceMs, int sampleLimit) {
        Bar target = Bar.fromCode(targetBar);
        long targetInterval = target.intervalMs();
        long expectedBars = targetInterval / Bar.M1.intervalMs();
        if (expectedBars <= 1L) {
            throw new IllegalArgumentException("targetBar 必须大于 1m: " + targetBar);
        }
        long since = sinceOrZero(sinceMs);
        long minBars = minBarsForAggregation(expectedBars);
        int limit = Math.max(1, Math.min(sampleLimit, 200));

        long[] counts = jdbcTemplate.queryForObject(AGGREGATION_CTE + """
                SELECT count(*) AS checked,
                       count(*) FILTER (WHERE %s) AS inconsistent,
                       count(DISTINCT m.inst_id) FILTER (WHERE %s) AS instruments
                FROM m
                JOIN okx_kline k ON k.kline_type = 'CANDLE' AND k.bar = ? AND k.inst_id = m.inst_id
                                AND k.ts_ms = m.bucket
                WHERE m.n >= ?
                  AND m.o IS NOT NULL AND m.c IS NOT NULL AND m.h IS NOT NULL AND m.l IS NOT NULL
                  AND k.open IS NOT NULL AND k.high IS NOT NULL AND k.low IS NOT NULL AND k.close IS NOT NULL
                """.formatted(AGGREGATION_MISMATCH, AGGREGATION_MISMATCH),
                (rs, rowNum) -> new long[] {rs.getLong("checked"), rs.getLong("inconsistent"),
                        rs.getLong("instruments")},
                targetInterval, since, targetBar, minBars);

        List<AggregationSample> samples = jdbcTemplate.query(AGGREGATION_CTE + """
                SELECT m.inst_id, m.bucket, m.n, m.o, m.h, m.l, m.c,
                       k.open AS k_open, k.high AS k_high, k.low AS k_low, k.close AS k_close
                FROM m
                JOIN okx_kline k ON k.kline_type = 'CANDLE' AND k.bar = ? AND k.inst_id = m.inst_id
                                AND k.ts_ms = m.bucket
                WHERE m.n >= ?
                  AND m.o IS NOT NULL AND m.c IS NOT NULL AND m.h IS NOT NULL AND m.l IS NOT NULL
                  AND k.open IS NOT NULL AND k.high IS NOT NULL AND k.low IS NOT NULL AND k.close IS NOT NULL
                  AND (%s)
                ORDER BY m.inst_id, m.bucket
                LIMIT ?
                """.formatted(AGGREGATION_MISMATCH),
                (rs, rowNum) -> {
                    double aggOpen = rs.getDouble("o");
                    double aggHigh = rs.getDouble("h");
                    double aggLow = rs.getDouble("l");
                    double aggClose = rs.getDouble("c");
                    double barOpen = rs.getDouble("k_open");
                    double barHigh = rs.getDouble("k_high");
                    double barLow = rs.getDouble("k_low");
                    double barClose = rs.getDouble("k_close");
                    return new AggregationSample(rs.getString("inst_id"), rs.getLong("bucket"),
                            rs.getLong("n"), expectedBars, aggOpen, aggHigh, aggLow, aggClose,
                            barOpen, barHigh, barLow, barClose,
                            aggregationProblems(aggOpen, aggHigh, aggLow, aggClose,
                                    barOpen, barHigh, barLow, barClose));
                },
                targetInterval, since, targetBar, minBars, limit);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targetBar", target.code());
        out.put("targetIntervalMs", targetInterval);
        out.put("expectedBarsPerBucket", expectedBars);
        out.put("minBarsPerBucket", minBars);
        out.put("sinceMs", since);
        out.put("checked", counts == null ? 0L : counts[0]);
        out.put("inconsistent", counts == null ? 0L : counts[1]);
        out.put("inconsistentInstruments", counts == null ? 0L : counts[2]);
        out.put("samples", samples);
        return out;
    }

    /**
     * C4 命中的问题代码，按"非正价格 → 负成交量 → 高低倒挂 → high 低于开收 → low 高于开收"的固定顺序返回。
     *
     * <p>用 {@code high < max(open,close)} 的等价拆分（{@code high < open} / {@code high < close}），
     * 取等号不算错；low 同理。</p>
     */
    public static List<String> ohlcProblems(double open, double high, double low, double close) {
        return ohlcProblems(open, high, low, close, 0d);
    }

    /** 带成交量的版本：{@code vol < 0} 记为 {@code VOL_NEGATIVE}（方案 C4 的第五项）。 */
    public static List<String> ohlcProblems(double open, double high, double low, double close, double vol) {
        List<String> problems = new ArrayList<>(4);
        if (open <= 0 || high <= 0 || low <= 0 || close <= 0) {
            problems.add("NON_POSITIVE");
        }
        if (vol < 0) {
            problems.add("VOL_NEGATIVE");
        }
        if (high < low) {
            problems.add("HIGH_LT_LOW");
        }
        if (high < open) {
            problems.add("HIGH_LT_OPEN");
        }
        if (high < close) {
            problems.add("HIGH_LT_CLOSE");
        }
        if (low > open) {
            problems.add("LOW_GT_OPEN");
        }
        if (low > close) {
            problems.add("LOW_GT_CLOSE");
        }
        return problems;
    }

    /** C5 判定：ts_ms 是否正好落在周期边界（非正周期视为未对齐）。 */
    public static boolean aligned(long tsMs, long intervalMs) {
        return intervalMs > 0 && tsMs % intervalMs == 0;
    }

    /**
     * C6 判定：桶内 1m 根数是否够得上参与聚合比对。
     *
     * <p>门槛是期望根数的 90%（含边界，60 根周期即 54 根；向上取整，避免整除截断放宽）。</p>
     */
    public static boolean aggregatable(long barsInBucket, long expectedBars) {
        return expectedBars > 0 && barsInBucket >= minBarsForAggregation(expectedBars);
    }

    /** C6 参与比较所需的最少 1m 根数 = ceil(0.9 × expectedBars)。 */
    public static long minBarsForAggregation(long expectedBars) {
        if (expectedBars <= 0) {
            return Long.MAX_VALUE;
        }
        return (expectedBars * 9L + 9L) / 10L;
    }

    /** C6 聚合值与目标周期行的不一致字段。 */
    public static List<String> aggregationProblems(double aggOpen, double aggHigh, double aggLow, double aggClose,
            double barOpen, double barHigh, double barLow, double barClose) {
        List<String> problems = new ArrayList<>(4);
        if (aggOpen != barOpen) {
            problems.add("OPEN");
        }
        if (aggHigh != barHigh) {
            problems.add("HIGH");
        }
        if (aggLow != barLow) {
            problems.add("LOW");
        }
        if (aggClose != barClose) {
            problems.add("CLOSE");
        }
        return problems;
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

    /** null 或负数的 sinceMs 视为"从头扫"（epoch 毫秒非负，0 即全量）。 */
    private static long sinceOrZero(Long sinceMs) {
        return sinceMs == null || sinceMs < 0L ? 0L : sinceMs;
    }
}
