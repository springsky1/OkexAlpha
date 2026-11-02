package com.okexalpha.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParamSamplerTest {

    private static final StrategyParams BASE = new StrategyParams(StrategyParams.LONG, 100d, 5, 5, 2.5d, 1.0d,
            2.0d, 25.0d, 48, 0.0005d, 0.01d, 2.0d);

    @Test
    @DisplayName("全网格 648 组（3×3×3×2×3×2×2），固定维度全部沿用基准")
    void gridExpandsAllDimensions() {
        assertThat(ParamSampler.gridSize()).isEqualTo(648);
        List<StrategyParams> grid = ParamSampler.grid(BASE);
        assertThat(grid).hasSize(648);
        assertThat(grid).allSatisfy(p -> {
            assertThat(p.initialMarginUsd()).isEqualTo(100d);
            assertThat(p.maxHoldHours()).isEqualTo(48);
            assertThat(p.feeTaker()).isEqualTo(0.0005d);
            assertThat(p.fundingPer8hPct()).isEqualTo(0.01d);
            assertThat(p.slippageBps()).isEqualTo(2.0d);
        });
        // 每个维度都取到了边界值
        assertThat(grid).anySatisfy(p -> {
            assertThat(p.side()).isEqualTo(StrategyParams.SHORT);
            assertThat(p.leverage()).isEqualTo(10);
            assertThat(p.maxLayers()).isEqualTo(8);
            assertThat(p.layerStepPct()).isEqualTo(4.0d);
            assertThat(p.layerScale()).isEqualTo(1.5d);
            assertThat(p.takeProfitPct()).isEqualTo(3.0d);
            assertThat(p.stopLossPct()).isEqualTo(25.0d);
        });
        // key() 唯一
        assertThat(grid.stream().map(StrategyParams::key).distinct().count()).isEqualTo(648);
    }

    @Test
    @DisplayName("抽样按种子可复现，且不超过上限")
    void sampleIsDeterministic() {
        List<StrategyParams> grid = ParamSampler.grid(BASE);
        List<StrategyParams> a = ParamSampler.sample(grid, 400, 20261101L);
        List<StrategyParams> b = ParamSampler.sample(grid, 400, 20261101L);
        List<StrategyParams> c = ParamSampler.sample(grid, 400, 7L);

        assertThat(a).hasSize(400);
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(ParamSampler.sample(grid, 5000, 1L)).hasSize(648);
        assertThat(ParamSampler.sample(grid, 0, 1L)).hasSize(648);
    }

    @Test
    @DisplayName("邻域扰动只改三个价格百分比维度，其余不变")
    void neighborhoodPerturbsPriceDimensionsOnly() {
        List<StrategyParams> nb = ParamSampler.neighborhood(BASE, new double[] {0.8d, 1.0d, 1.25d});
        assertThat(nb).hasSize(3);
        assertThat(nb).allSatisfy(p -> {
            assertThat(p.leverage()).isEqualTo(BASE.leverage());
            assertThat(p.maxLayers()).isEqualTo(BASE.maxLayers());
            assertThat(p.layerScale()).isEqualTo(BASE.layerScale());
            assertThat(p.initialMarginUsd()).isEqualTo(BASE.initialMarginUsd());
        });
        assertThat(nb.get(0).layerStepPct()).isEqualTo(2.0d);
        assertThat(nb.get(2).layerStepPct()).isEqualTo(3.125d);
        assertThat(nb.get(2).takeProfitPct()).isEqualTo(2.5d);
        assertThat(nb.get(2).stopLossPct()).isEqualTo(20.0d);
        // 止损反比放大后夹在 100 以内（2.5% × 10 倍会爆界，靠上限兜住）
        assertThat(ParamSampler.neighborhood(BASE, new double[] {0.1d}).get(0).stopLossPct()).isEqualTo(100.0d);
    }
}
