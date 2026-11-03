package com.okexalpha.strategy.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 参数网格与抽样：把「马丁策略的 7 个可优化维度」展开成参数组合。
 *
 * <p>固定维度（初始保证金、最大持仓小时数、费率、资金费率、滑点）来自基准参数，
 * 只有下面这些参与寻优：方向 × 杠杆 × 最大层数 × 加仓间隔 × 加仓倍数 × 止盈 × 止损。</p>
 */
public final class ParamSampler {

    static final int[] LEVERAGES = {3, 5, 10};
    static final int[] MAX_LAYERS = {3, 5, 8};
    static final double[] LAYER_STEPS = {1.5d, 2.5d, 4.0d};
    static final double[] LAYER_SCALES = {1.0d, 1.5d};
    static final double[] TAKE_PROFITS = {1.0d, 2.0d, 3.0d};
    static final double[] STOP_LOSSES = {15.0d, 25.0d};
    static final String[] SIDES = {StrategyParams.LONG, StrategyParams.SHORT};

    private ParamSampler() {
    }

    /** 全网格组合数（3 杠杆 × 3 层数 × 3 间隔 × 2 倍数 × 3 止盈 × 2 止损 × 2 方向 = 648）。 */
    public static int gridSize() {
        return LEVERAGES.length * MAX_LAYERS.length * LAYER_STEPS.length * LAYER_SCALES.length
                * TAKE_PROFITS.length * STOP_LOSSES.length * SIDES.length;
    }

    /** 展开全网格；固定维度沿用 base。 */
    public static List<StrategyParams> grid(StrategyParams base) {
        List<StrategyParams> out = new ArrayList<>(gridSize());
        for (String side : SIDES) {
            for (int leverage : LEVERAGES) {
                for (int maxLayers : MAX_LAYERS) {
                    for (double step : LAYER_STEPS) {
                        for (double scale : LAYER_SCALES) {
                            for (double tp : TAKE_PROFITS) {
                                for (double sl : STOP_LOSSES) {
                                    out.add(new StrategyParams(side, base.initialMarginUsd(), leverage, maxLayers,
                                            step, scale, tp, sl, base.maxHoldHours(), base.feeTaker(),
                                            base.fundingPer8hPct(), base.slippageBps()));
                                }
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    /** 按自定义范围展开；ranges 为 null 时退回内置全网格（648 组），保持旧行为。 */
    public static List<StrategyParams> grid(StrategyParams base, ParamRanges ranges) {
        if (ranges == null) {
            return grid(base);
        }
        return ranges.expand(base);
    }

    /** 用固定种子洗牌后取前 max 个，保证同一 seed 结果可复现。 */
    public static List<StrategyParams> sample(List<StrategyParams> all, int max, long seed) {
        if (max <= 0 || all.size() <= max) {
            return List.copyOf(all);
        }
        List<StrategyParams> copy = new ArrayList<>(all);
        Collections.shuffle(copy, new Random(seed));
        return List.copyOf(copy.subList(0, max));
    }

    /**
     * 精调邻域：在选中参数附近按因子扰动加仓间隔与止盈，止损按反比放大
     * （止盈收窄时止损放宽，避免两者同时收紧把样本压成 0 笔）。
     */
    public static List<StrategyParams> neighborhood(StrategyParams p, double[] factors) {
        List<StrategyParams> out = new ArrayList<>(factors.length);
        for (double factor : factors) {
            if (factor <= 0) {
                continue;
            }
            out.add(p.withScale(factor));
        }
        return List.copyOf(out);
    }
}
