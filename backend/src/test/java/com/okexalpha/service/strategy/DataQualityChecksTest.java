package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * C4 合法性 / C5 对齐 / C6 聚合可比较阈值的纯函数测试。
 *
 * <p>测试环境没有数据库，SQL 本身不在这里验证；这里只钉住判定口径，防止以后改阈值时悄悄放宽。</p>
 */
class DataQualityChecksTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;

    @Nested
    @DisplayName("C4 ohlcProblems：OHLC 关系与非正价格")
    class OhlcProblems {

        @Test
        @DisplayName("正常 bar 无问题；high 恰好等于 max(open,close) 不算错")
        void normalBarHasNoProblem() {
            assertThat(DataQualityService.ohlcProblems(10d, 12d, 9d, 11d)).isEmpty();
            // high == open == close、low == open == close，全部取等号也不报
            assertThat(DataQualityService.ohlcProblems(10d, 10d, 10d, 10d)).isEmpty();
            assertThat(DataQualityService.ohlcProblems(9d, 10d, 9d, 10d)).isEmpty();
        }

        @Test
        @DisplayName("high < low → HIGH_LT_LOW（此时开收盘必然有一端越界，故会连带命中）")
        void highBelowLow() {
            // high=8 低于 low=9；开收都等于 10，于是同时 high < open、high < close。
            assertThat(DataQualityService.ohlcProblems(10d, 8d, 9d, 10d))
                    .containsExactly("HIGH_LT_LOW", "HIGH_LT_OPEN", "HIGH_LT_CLOSE");
        }

        @Test
        @DisplayName("high < open / high < close 分别命中")
        void highBelowOpenOrClose() {
            assertThat(DataQualityService.ohlcProblems(11d, 10d, 9d, 10d))
                    .containsExactly("HIGH_LT_OPEN");
            assertThat(DataQualityService.ohlcProblems(10d, 10d, 9d, 11d))
                    .containsExactly("HIGH_LT_CLOSE");
        }

        @Test
        @DisplayName("low > open / low > close 分别命中")
        void lowAboveOpenOrClose() {
            assertThat(DataQualityService.ohlcProblems(8d, 10d, 9d, 9d))
                    .containsExactly("LOW_GT_OPEN");
            assertThat(DataQualityService.ohlcProblems(9d, 10d, 9d, 8d))
                    .containsExactly("LOW_GT_CLOSE");
        }

        @Test
        @DisplayName("价格 <= 0 → NON_POSITIVE（0 与负数都算）")
        void nonPositivePrice() {
            assertThat(DataQualityService.ohlcProblems(0d, 1d, 0d, 1d))
                    .containsExactly("NON_POSITIVE");
            assertThat(DataQualityService.ohlcProblems(-1d, 1d, -2d, 1d))
                    .containsExactly("NON_POSITIVE");
        }

        @Test
        @DisplayName("负成交量 → VOL_NEGATIVE（与价格问题可同时命中）")
        void negativeVolume() {
            assertThat(DataQualityService.ohlcProblems(100d, 101d, 99d, 100d, -1d))
                    .containsExactly("VOL_NEGATIVE");
            assertThat(DataQualityService.ohlcProblems(100d, 101d, 99d, 100d, 0d)).isEmpty();
            assertThat(DataQualityService.ohlcProblems(0d, 10d, 8d, 11d, -0.5d))
                    .containsExactly("NON_POSITIVE", "VOL_NEGATIVE", "HIGH_LT_CLOSE", "LOW_GT_OPEN");
        }

        @Test
        @DisplayName("多个问题同时命中时全部列出")
        void multipleProblems() {
            // open=0 非正；high=10 < close=11；low=8 > open=0。
            List<String> problems = DataQualityService.ohlcProblems(0d, 10d, 8d, 11d);
            assertThat(problems).containsExactly("NON_POSITIVE", "HIGH_LT_CLOSE", "LOW_GT_OPEN");
        }
    }

    @Nested
    @DisplayName("C5 aligned：ts_ms 是否落在周期边界")
    class Aligned {

        @Test
        @DisplayName("恰好对齐不报错")
        void exactBoundaryIsAligned() {
            assertThat(DataQualityService.aligned(0L, MINUTE)).isTrue();
            assertThat(DataQualityService.aligned(1_700_000_040_000L, MINUTE)).isTrue();
            // 1_699_999_200_000 = 472_222 × 3_600_000，正好是整点
            assertThat(DataQualityService.aligned(1_699_999_200_000L, HOUR)).isTrue();
        }

        @Test
        @DisplayName("差 1 毫秒即为未对齐")
        void oneMillisecondOff() {
            assertThat(DataQualityService.aligned(1_700_000_040_001L, MINUTE)).isFalse();
            assertThat(DataQualityService.aligned(1_700_000_000_001L, HOUR)).isFalse();
        }

        @Test
        @DisplayName("半个周期也视为未对齐")
        void halfIntervalOff() {
            assertThat(DataQualityService.aligned(1_700_000_030_000L, MINUTE)).isFalse();
        }

        @Test
        @DisplayName("非法周期返回 false，不抛异常")
        void invalidInterval() {
            assertThat(DataQualityService.aligned(1_700_000_000_000L, 0L)).isFalse();
            assertThat(DataQualityService.aligned(1_700_000_000_000L, -MINUTE)).isFalse();
        }
    }

    @Nested
    @DisplayName("C6 aggregatable：桶内 1m 根数是否够 90% 期望值")
    class Aggregatable {

        @Test
        @DisplayName("满桶与恰好 90% 都参与比较")
        void fullAndNinetyPercent() {
            assertThat(DataQualityService.aggregatable(60L, 60L)).isTrue();
            assertThat(DataQualityService.aggregatable(54L, 60L)).isTrue();
            assertThat(DataQualityService.aggregatable(1296L, 1440L)).isTrue();
        }

        @Test
        @DisplayName("低于 90% 不参与比较")
        void belowNinetyPercent() {
            assertThat(DataQualityService.aggregatable(53L, 60L)).isFalse();
            assertThat(DataQualityService.aggregatable(1295L, 1440L)).isFalse();
            assertThat(DataQualityService.aggregatable(0L, 60L)).isFalse();
        }

        @Test
        @DisplayName("超过期望根数仍可比较（多出的重复根不算错）")
        void moreThanExpected() {
            assertThat(DataQualityService.aggregatable(61L, 60L)).isTrue();
        }

        @Test
        @DisplayName("期望根数非正时返回 false，不抛异常")
        void invalidExpectation() {
            assertThat(DataQualityService.aggregatable(10L, 0L)).isFalse();
            assertThat(DataQualityService.aggregatable(10L, -60L)).isFalse();
        }
    }

    @Nested
    @DisplayName("C6 aggregationProblems：1m 聚合值与目标周期行的差异字段")
    class AggregationProblems {

        @Test
        @DisplayName("OHLC 完全一致时无差异")
        void identicalOhlc() {
            assertThat(DataQualityService.aggregationProblems(10d, 12d, 9d, 11d, 10d, 12d, 9d, 11d)).isEmpty();
        }

        @Test
        @DisplayName("只差一个字段时只报该字段")
        void singleFieldDiffers() {
            assertThat(DataQualityService.aggregationProblems(10d, 12d, 9d, 11d, 10.5d, 12d, 9d, 11d))
                    .containsExactly("OPEN");
            assertThat(DataQualityService.aggregationProblems(10d, 12d, 9d, 11d, 10d, 12d, 9.5d, 11d))
                    .containsExactly("LOW");
        }

        @Test
        @DisplayName("四个字段全不一致时按 OPEN/HIGH/LOW/CLOSE 顺序全部列出")
        void allFieldsDiffer() {
            assertThat(DataQualityService.aggregationProblems(10d, 12d, 9d, 11d, 1d, 2d, 3d, 4d))
                    .containsExactly("OPEN", "HIGH", "LOW", "CLOSE");
        }
    }
}
