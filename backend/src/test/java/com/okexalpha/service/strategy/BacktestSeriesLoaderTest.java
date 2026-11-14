package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F2：未收盘 bar（confirm=false）绝不能进入回测序列。 */
class BacktestSeriesLoaderTest {

    @Test
    @DisplayName("回测序列 SQL 带 confirm 过滤，否则未收盘 bar 会变成未来函数")
    void seriesSqlFiltersUnconfirmedBars() {
        assertThat(BacktestSeriesLoader.SERIES_SQL)
                .contains("confirm = true")
                .contains("kline_type = 'CANDLE'")
                .contains("ORDER BY ts_ms");
    }
}
