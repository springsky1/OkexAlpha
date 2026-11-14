package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 组合数上限的口径：{@code maxCombos <= 0} 表示不限制（0.1 之后默认配置即 0），
 * 但结果永远不超过网格规模——网格本身另有 {@code ParamRanges.DEFAULT_EXPAND_LIMIT} 的内存护栏。
 */
class BacktestLimitsTest {

    @Test
    @DisplayName("请求 5000 组不再被 2000 截断")
    void requestIsNotCappedAt2000() {
        assertThat(BacktestService.wantCombos(5000, 400, 0, 17_280)).isEqualTo(5000);
    }

    @Test
    @DisplayName("请求数超过网格时按全网格跑")
    void requestAboveGridFallsBackToGrid() {
        assertThat(BacktestService.wantCombos(50_000, 400, 0, 17_280)).isEqualTo(17_280);
    }

    @Test
    @DisplayName("未指定 combos 时用默认抽样数（默认仍是 400，不会无意跑满网格）")
    void defaultsToSampleSize() {
        assertThat(BacktestService.wantCombos(null, 400, 0, 17_280)).isEqualTo(400);
    }

    @Test
    @DisplayName("默认抽样数为 0 时跑全网格")
    void zeroSampleSizeMeansWholeGrid() {
        assertThat(BacktestService.wantCombos(null, 0, 0, 17_280)).isEqualTo(17_280);
    }

    @Test
    @DisplayName("显式配置了 maxCombos 时仍然生效（向后兼容旧配置）")
    void explicitCapStillApplies() {
        assertThat(BacktestService.wantCombos(5000, 400, 2000, 17_280)).isEqualTo(2000);
    }

    @Test
    @DisplayName("退化输入不会算出 0 组")
    void degenerateInputYieldsOne() {
        assertThat(BacktestService.wantCombos(0, 0, 0, 0)).isEqualTo(1);
        assertThat(BacktestService.wantCombos(-5, -1, 2000, 0)).isEqualTo(1);
    }
}
