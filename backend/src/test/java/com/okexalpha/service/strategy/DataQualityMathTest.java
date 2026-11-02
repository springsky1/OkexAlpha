package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataQualityMathTest {

    private static final long MINUTE = 60_000L;

    @Test
    @DisplayName("满覆盖：首尾各一根、无缺口 → 100%")
    void fullCoverage() {
        long first = 1_000_000_000_000L;
        long last = first + 9 * MINUTE;
        assertThat(DataQualityService.coveragePct(first, last, 10L, MINUTE)).isEqualTo(100d);
    }

    @Test
    @DisplayName("缺一半 → 50%")
    void halfCoverage() {
        long first = 1_000_000_000_000L;
        long last = first + 9 * MINUTE;
        assertThat(DataQualityService.coveragePct(first, last, 5L, MINUTE)).isEqualTo(50d);
    }

    @Test
    @DisplayName("单根数据 → 100%（跨度 0 也应有 1 根）")
    void singleRowIsFullCoverage() {
        assertThat(DataQualityService.coveragePct(5_000L, 5_000L, 1L, MINUTE)).isEqualTo(100d);
    }

    @Test
    @DisplayName("行数超过应有根数时封顶 100%，不出现 120%")
    void capsAtHundred() {
        assertThat(DataQualityService.coveragePct(0L, 5 * MINUTE, 50L, MINUTE)).isEqualTo(100d);
    }

    @Test
    @DisplayName("缺口漏根数 = 差/周期 − 1")
    void missedBars() {
        assertThat(DataQualityService.missedBars(3 * MINUTE, MINUTE)).isEqualTo(2L);
        assertThat(DataQualityService.missedBars(MINUTE, MINUTE)).isEqualTo(0L);
        assertThat(DataQualityService.missedBars(59_999L, MINUTE)).isEqualTo(0L);
    }

    @Test
    @DisplayName("非法输入返回 0，不抛异常")
    void invalidInputs() {
        assertThat(DataQualityService.coveragePct(10L, 5L, 3L, MINUTE)).isEqualTo(0d);
        assertThat(DataQualityService.coveragePct(0L, 5L, 0L, MINUTE)).isEqualTo(0d);
        assertThat(DataQualityService.missedBars(-1L, MINUTE)).isEqualTo(0L);
        assertThat(DataQualityService.missedBars(100L, 0L)).isEqualTo(0L);
    }

    @Test
    @DisplayName("上线晚于窗口首日：覆盖起点向上对齐到周期边界")
    void coverageStartAlignsToListing() {
        long windowFirst = 1_000_000_020_000L; // 对齐到整分钟，便于断言
        long listTime = windowFirst + 90_000L;
        assertThat(DataQualityService.coverageStart(windowFirst, listTime, MINUTE)).isEqualTo(windowFirst + 2 * MINUTE);
    }

    @Test
    @DisplayName("上线早于窗口首日或未知：覆盖起点就是窗口首日")
    void coverageStartFallsBackToWindowFirst() {
        long windowFirst = 1_000_000_000_000L;
        assertThat(DataQualityService.coverageStart(windowFirst, null, MINUTE)).isEqualTo(windowFirst);
        assertThat(DataQualityService.coverageStart(windowFirst, windowFirst - 5 * MINUTE, MINUTE)).isEqualTo(windowFirst);
    }

    @Test
    @DisplayName("应有根数按周期算，末日早于起点为 0")
    void expectedRows() {
        long start = 1_000_000_000_000L;
        assertThat(DataQualityService.expectedRows(start, start + 4 * MINUTE, MINUTE)).isEqualTo(5L);
        assertThat(DataQualityService.expectedRows(start, start - MINUTE, MINUTE)).isEqualTo(0L);
        assertThat(DataQualityService.expectedRows(start, start + 4 * MINUTE, 0L)).isEqualTo(0L);
    }
}
