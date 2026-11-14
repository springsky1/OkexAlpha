package com.okexalpha.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 时间口径的<b>唯一来源</b>（方案 §1.3）。全库不允许出现第二处 {@code 8 * 3600} 之类的时区偏移。
 *
 * <p>规则：</p>
 * <ol>
 *   <li>bar 存储、回测计算、日指标切日、日限额切日<b>全部用 UTC</b>；</li>
 *   <li>资金费在 00:00/08:00/16:00 UTC 结算，用 +08 切日会每天跨一次结算点；</li>
 *   <li>展示层要本地时间时，只在前端或 DTO 上转换，不回到库与引擎里。</li>
 * </ol>
 */
public final class TimeAxis {

    /** bar 的存储与计算时区。 */
    public static final ZoneId BAR_ZONE = ZoneOffset.UTC;
    /** 日指标、日限额等"统计日"的切分时区。 */
    public static final ZoneId STATS_ZONE = ZoneOffset.UTC;
    /** 一天的毫秒数。 */
    public static final long DAY_MS = 86_400_000L;
    /** 资金费结算间隔。 */
    public static final long FUNDING_INTERVAL_MS = 8L * 3_600_000L;

    private TimeAxis() {
    }

    /** 时间戳落在第几个 UTC 自然日（与 {@code Math.floorDiv(ts, DAY_MS)} 等价）。 */
    public static long dayBucket(long tsMs) {
        return Math.floorDiv(tsMs, DAY_MS);
    }

    /** 给定周期的 bar 起始时间（向下取整到周期边界）。 */
    public static long barStart(long tsMs, long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be > 0");
        }
        return Math.floorDiv(tsMs, intervalMs) * intervalMs;
    }

    /** 是否落在周期边界上（数据校验 C5 用）。 */
    public static boolean aligned(long tsMs, long intervalMs) {
        return intervalMs > 0 && Math.floorMod(tsMs, intervalMs) == 0;
    }

    /** 调试/日志用：UTC ISO-8601。切勿用于业务切日。 */
    public static String isoUtc(long tsMs) {
        return Instant.ofEpochMilli(tsMs).atZone(BAR_ZONE).toOffsetDateTime().toString();
    }
}
