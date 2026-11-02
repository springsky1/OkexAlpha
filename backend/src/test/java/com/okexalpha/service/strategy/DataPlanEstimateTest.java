package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.okexalpha.domain.Bar;
import com.okexalpha.error.BadRequestException;

/** R3 数据规划：行数/页数/耗时的确定性换算（不连库，只用纯计算部分）。 */
class DataPlanEstimateTest {

    private final DataPlanService service = new DataPlanService(null);

    /** 2020-09-13T12:26:40Z，取一个远早于压缩窗口的固定点，避免热数据分支随当前时间漂移。 */
    private static final long START = 1_600_000_000_000L;
    private static final long DAY = 86_400_000L;

    @Test
    void oneDayOfOneMinuteCandlesForOneInstrument() {
        DataPlanService.Estimate estimate = service.estimate(Bar.M1, List.of("CANDLE"), 1, START, START + DAY);

        assertThat(estimate.barsPerInstrument()).isEqualTo(1441); // 含两端
        assertThat(estimate.rowsPerInstrument()).isEqualTo(1441);
        assertThat(estimate.rowsTotal()).isEqualTo(1441);
        assertThat(estimate.pagesPerInstrument()).isEqualTo(5); // 1441 / 300 向上取整
        assertThat(estimate.pagesTotal()).isEqualTo(5);
        assertThat(estimate.maxRowsPerPage()).isEqualTo(300);
        // 远早于 30 天压缩窗口：全部算冷数据
        assertThat(estimate.hotWindowRows()).isZero();
        assertThat(estimate.gbHotWindow()).isZero();
    }

    @Test
    void twoKlineTypesDoubleTheRows() {
        DataPlanService.Estimate candle = service.estimate(Bar.D1, List.of("CANDLE"), 10, START, START + 100 * DAY);
        DataPlanService.Estimate both = service.estimate(Bar.D1, List.of("CANDLE", "MARK"), 10, START,
                START + 100 * DAY);

        assertThat(candle.rowsPerInstrument()).isEqualTo(101);
        assertThat(candle.rowsTotal()).isEqualTo(1010);
        assertThat(both.rowsTotal()).isEqualTo(2020);
        assertThat(both.pagesTotal()).isEqualTo(10);
        assertThat(both.hoursEstimate()).isCloseTo(2020 / 1951d / 3600d, within(0.01));
    }

    @Test
    void oneHourBarsScaleDownBySixty() {
        DataPlanService.Estimate estimate = service.estimate(Bar.H1, List.of("CANDLE"), 4, START, START + 30 * DAY);

        assertThat(estimate.barsPerInstrument()).isEqualTo(721);
        assertThat(estimate.rowsTotal()).isEqualTo(2884);
        assertThat(estimate.pagesPerInstrument()).isEqualTo(3);
        assertThat(estimate.pagesTotal()).isEqualTo(12);
    }

    @Test
    void fullYearOfOneMinuteForAllSwapsMatchesMeasuredThroughput() {
        long year = 304 * DAY;
        DataPlanService.Estimate estimate = service.estimate(Bar.M1, List.of("CANDLE"), 532, START, START + year);

        assertThat(estimate.barsPerInstrument()).isEqualTo(437_761L);
        assertThat(estimate.rowsTotal()).isEqualTo(437_761L * 532);
        assertThat(estimate.pagesPerInstrument()).isEqualTo(1460);
        assertThat(estimate.pagesTotal()).isEqualTo(1460L * 532);
        // 行吞吐 1951 行/秒、页吞吐 6.3 页/秒（限流打满时以页为准）
        assertThat(estimate.hoursEstimate()).isBetween(33d, 34d);
        assertThat(estimate.pagesHoursEstimate()).isBetween(34d, 35d);
        assertThat(estimate.gbAfterCompression()).isGreaterThan(0d);
    }

    @Test
    void zeroInstrumentsProducesEmptyPlan() {
        DataPlanService.Estimate estimate = service.estimate(Bar.M1, List.of("CANDLE"), 0, START, START + DAY);

        assertThat(estimate.rowsTotal()).isZero();
        assertThat(estimate.pagesTotal()).isZero();
        assertThat(estimate.hoursEstimate()).isZero();
    }

    @Test
    void invertedRangeIsRejected() {
        assertThatThrownBy(() -> service.estimate(Bar.M1, List.of("CANDLE"), 1, START, START))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("endMs must be greater than startMs");
    }
}
