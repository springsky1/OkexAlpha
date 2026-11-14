package com.okexalpha.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 时间口径单点定义的回归测试（方案 §1.3）。 */
class TimeAxisTest {

    /**
     * docs/DATA-QUALITY.md 里被标成「2026-05-01 08:00 (+08)」的那个时间戳。
     * 真实值是 <b>2026-05-01T16:00:00Z</b>（即 +08 的 2026-05-02 00:00）—— 报告把本地时刻当成了 UTC。
     */
    private static final long REPORTED_TS = 1_777_651_200_000L;

    /** 下一个 UTC 日界（+08 的 2026-05-02 08:00）。 */
    private static final long NEXT_UTC_MIDNIGHT = REPORTED_TS + 8L * 3_600_000L;

    @Test
    @DisplayName("被标错的 1777651200000 是 2026-05-01T16:00Z，不是 UTC 日界")
    void reportedTimestampIsNotUtcMidnight() {
        assertThat(TimeAxis.isoUtc(REPORTED_TS)).startsWith("2026-05-01T16:00");
        assertThat(TimeAxis.aligned(REPORTED_TS, TimeAxis.DAY_MS)).isFalse();
        assertThat(TimeAxis.dayBucket(REPORTED_TS)).isEqualTo(Math.floorDiv(REPORTED_TS, TimeAxis.DAY_MS));
    }

    @Test
    @DisplayName("统计日按 UTC 切：+08 的次日 00:00 才换日")
    void statsDayIsUtc() {
        assertThat(TimeAxis.isoUtc(NEXT_UTC_MIDNIGHT)).startsWith("2026-05-02T00:00");
        assertThat(TimeAxis.aligned(NEXT_UTC_MIDNIGHT, TimeAxis.DAY_MS)).isTrue();
        // 16:00Z 与 23:00Z 同属一个 UTC 日；跨过 00:00Z 才 +1
        assertThat(TimeAxis.dayBucket(REPORTED_TS + 7L * 3_600_000L)).isEqualTo(TimeAxis.dayBucket(REPORTED_TS));
        assertThat(TimeAxis.dayBucket(NEXT_UTC_MIDNIGHT)).isEqualTo(TimeAxis.dayBucket(REPORTED_TS) + 1L);
    }

    @Test
    @DisplayName("周期边界对齐：1H / 4H / 1D")
    void barStartAlignsToInterval() {
        assertThat(TimeAxis.barStart(REPORTED_TS + 3_599_999L, 3_600_000L)).isEqualTo(REPORTED_TS);
        assertThat(TimeAxis.barStart(REPORTED_TS + 3_600_000L, 3_600_000L)).isEqualTo(REPORTED_TS + 3_600_000L);
        assertThat(TimeAxis.barStart(REPORTED_TS + 5L, 14_400_000L)).isEqualTo(REPORTED_TS);
        assertThat(TimeAxis.aligned(REPORTED_TS + 1L, 60_000L)).isFalse();
    }
}
