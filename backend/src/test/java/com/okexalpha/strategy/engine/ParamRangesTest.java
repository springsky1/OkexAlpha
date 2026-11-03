package com.okexalpha.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParamRangesTest {

    private static final StrategyParams BASE = new StrategyParams(StrategyParams.LONG, 100d, 5, 5, 2.5d, 1.0d,
            2.0d, 25.0d, 48, 0.0005d, 0.01d, 2.0d);

    private static ParamRanges.Range range(double min, double max, double step) {
        return new ParamRanges.Range(min, max, step);
    }

    @Test
    @DisplayName("全空范围等价内置网格：combos=648，expand 与 ParamSampler.grid 完全一致")
    void emptyRangesMatchBuiltInGrid() {
        ParamRanges empty = new ParamRanges(null, null, null, null, null, null, null);
        empty.validate();
        assertThat(empty.combos()).isEqualTo(ParamSampler.gridSize());
        assertThat(empty.expand(BASE)).isEqualTo(ParamSampler.grid(BASE));
        assertThat(ParamRanges.class.cast(empty).sides()).isEmpty();
    }

    @Test
    @DisplayName("组合数按维度精确计算；sides 为空按双向，单方向减半")
    void combosCountsEachDimension() {
        ParamRanges twoSides = new ParamRanges(range(3, 5, 1), range(3, 5, 1), range(1.5, 2.5, 0.5), range(1, 1, 0.5),
                range(1, 2, 0.5), range(15, 25, 5), List.of());
        // 3 杠杆 × 3 层数 × 3 间隔 × 1 倍数 × 3 止盈 × 3 止损 × 2 方向
        assertThat(twoSides.combos()).isEqualTo(486);
        assertThat(twoSides.expand(BASE)).hasSize(486);

        ParamRanges oneSide = new ParamRanges(twoSides.leverage(), twoSides.maxLayers(), twoSides.layerStepPct(),
                twoSides.layerScale(), twoSides.takeProfitPct(), twoSides.stopLossPct(), List.of(StrategyParams.SHORT));
        assertThat(oneSide.combos()).isEqualTo(243);
        assertThat(oneSide.expand(BASE)).hasSize(243);
        assertThat(oneSide.expand(BASE)).allSatisfy(p -> assertThat(p.side()).isEqualTo(StrategyParams.SHORT));
    }

    @Test
    @DisplayName("整数维按步长递增，且末值一定包含 max（步长除不尽时补一个）")
    void integralDimensionAlwaysEndsAtMax() {
        // 杠杆 3..10 步 3 → 3,6,9,10；层数 3..4 步 1 → 3,4；其余维度固定单值，便于断言总数
        ParamRanges ranges = new ParamRanges(range(3, 10, 3), range(3, 4, 1), range(2.5, 2.5, 0.5),
                range(1, 1, 0.5), range(2, 2, 0.5), range(25, 25, 5), List.of(StrategyParams.LONG));
        ranges.validate();
        List<StrategyParams> expanded = ranges.expand(BASE);
        assertThat(expanded).hasSize(4 * 2);
        assertThat(expanded.stream().map(StrategyParams::leverage).distinct())
                .containsExactly(3, 6, 9, 10);
        assertThat(expanded.stream().map(StrategyParams::maxLayers).distinct())
                .containsExactly(3, 4);
        assertThat(ranges.combos()).isEqualTo(expanded.size());
    }

    @Test
    @DisplayName("浮点维按 4 位小数稳定取值，不出现 1.2000000000000002")
    void decimalDimensionUsesStableRounding() {
        ParamRanges ranges = new ParamRanges(null, null, range(1.1, 1.4, 0.1), range(1.0, 1.5, 0.5), null, null,
                List.of(StrategyParams.LONG));
        ranges.validate();
        List<Double> steps = ranges.expand(BASE).stream().map(StrategyParams::layerStepPct).distinct().toList();
        assertThat(steps).containsExactly(1.1d, 1.2d, 1.3d, 1.4d);
        List<Double> scales = ranges.expand(BASE).stream().map(StrategyParams::layerScale).distinct().toList();
        assertThat(scales).containsExactly(1.0d, 1.5d);
    }

    @Test
    @DisplayName("max 小于 min、step<=0、整数维步长非整数、越界都报 IllegalArgumentException")
    void validateRejectsBadRanges() {
        assertThatThrownBy(() -> new ParamRanges(range(10, 3, 1), null, null, null, null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min must be <= max");

        assertThatThrownBy(() -> new ParamRanges(null, null, range(1, 2, 0), null, null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step must be > 0");

        assertThatThrownBy(() -> new ParamRanges(range(3, 5, 0.5), null, null, null, null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step must be an integer");

        assertThatThrownBy(() -> new ParamRanges(range(0, 5, 1), null, null, null, null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min must be >= 1");

        assertThatThrownBy(() -> new ParamRanges(range(3, 200, 1), null, null, null, null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max must be <= 125");

        assertThatThrownBy(() -> new ParamRanges(null, null, null, null, null, range(1, 101, 5), null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max must be <= 100");

        assertThatThrownBy(() -> new ParamRanges(null, null, null, null, null, null, List.of("both")).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown side");

        // 组件不全也不接受
        assertThatThrownBy(() -> new ParamRanges(new ParamRanges.Range(3d, null, 1d), null, null, null, null, null,
                null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min/max/step must be provided together");
    }

    @Test
    @DisplayName("expand 超过安全上限时截断，combos 仍报告精确值")
    void expandTruncatesAtLimit() {
        // 125 杠杆 × 3 层数 × 3 间隔 × 2 倍数 × 3 止盈 × 100 止损 × 2 方向 = 1,350,000 组
        ParamRanges huge = new ParamRanges(range(1, 125, 1), null, null, null, null, range(1, 100, 1), null);
        huge.validate();
        assertThat(huge.combos()).isEqualTo(1_350_000);
        assertThat(huge.expand(BASE)).hasSize(ParamRanges.DEFAULT_EXPAND_LIMIT);
        assertThat(huge.expand(BASE, 100)).hasSize(100);
    }

    @Test
    @DisplayName("预设目录：三个预设可查、组合数自洽、区间合法")
    void presetsAreConsistent() {
        List<ParamPresets> catalog = ParamPresets.catalog();
        assertThat(catalog).extracting(ParamPresets::key)
                .containsExactly("conservative", "balanced", "aggressive");
        assertThat(catalog).allSatisfy(preset -> {
            preset.ranges().validate();
            assertThat(preset.note()).isNotBlank();
            assertThat(preset.combos()).isEqualTo(preset.ranges().combos());
        });
        assertThat(ParamPresets.byKey("conservative")).isNotNull();
        assertThat(ParamPresets.byKey("nope")).isNull();
        assertThat(ParamPresets.byKey(null)).isNull();
    }
}
