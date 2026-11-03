package com.okexalpha.strategy.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 参数范围（回测提交可选）：为 6 个寻优维度分别给出闭区间与步长，null = 该维沿用内置网格。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@link Range} 的 min/max/step 必须同时提供；整数维（杠杆、层数）要求三者都是整数且 step ≥ 1，
 *       展开时按 step 递增<b>并保证包含 max</b>；</li>
 *   <li>浮点维按 step 递增，取值统一四舍五入到 4 位小数，避免出现 2.4999999 这类浮点噪声；</li>
 *   <li>sides 为空 = 双向（long + short）。</li>
 * </ul>
 *
 * <p>6 个维度全为 null 时等价于旧的 648 组内置网格（{@link ParamSampler#gridSize()}）。</p>
 */
public record ParamRanges(
        Range leverage,
        Range maxLayers,
        Range layerStepPct,
        Range layerScale,
        Range takeProfitPct,
        Range stopLossPct,
        List<String> sides) {

    /** 单个维度的闭区间 [min, max]，按 step 递增。 */
    public record Range(Double min, Double max, Double step) {
    }

    /** 展开的安全上限：超过它先截断，服务端再按 maxCombos 抽样，避免用户填出天文数字时打爆内存。 */
    public static final int DEFAULT_EXPAND_LIMIT = 20_000;

    public ParamRanges {
        List<String> normalized = new ArrayList<>();
        if (sides != null) {
            for (String side : sides) {
                if (side == null || side.isBlank()) {
                    continue;
                }
                String value = side.trim();
                if (!normalized.contains(value)) {
                    normalized.add(value);
                }
            }
        }
        sides = List.copyOf(normalized);
    }

    /** 校验范围本身是否可用；不合法直接抛 IllegalArgumentException（服务端转 400）。 */
    public void validate() {
        checkRange("leverage", leverage, true, 1d, false, 125d);
        checkRange("maxLayers", maxLayers, true, 1d, false, 50d);
        checkRange("layerStepPct", layerStepPct, false, 0d, true, null);
        checkRange("layerScale", layerScale, false, 1d, false, null);
        checkRange("takeProfitPct", takeProfitPct, false, 0d, true, null);
        checkRange("stopLossPct", stopLossPct, false, 1d, false, 100d);
        for (String side : sides) {
            if (!StrategyParams.LONG.equals(side) && !StrategyParams.SHORT.equals(side)) {
                throw new IllegalArgumentException("paramRanges.sides: unknown side " + side);
            }
        }
    }

    /**
     * 组合数：空维度按内置网格的取值个数计，sides 为空按双向（×2）。
     * 结果饱和到 {@link Integer#MAX_VALUE}，不会因为用户输入过大而溢出。
     */
    public int combos() {
        long total = 1L;
        total = multiply(total, count(leverage, ParamSampler.LEVERAGES.length, true));
        total = multiply(total, count(maxLayers, ParamSampler.MAX_LAYERS.length, true));
        total = multiply(total, count(layerStepPct, ParamSampler.LAYER_STEPS.length, false));
        total = multiply(total, count(layerScale, ParamSampler.LAYER_SCALES.length, false));
        total = multiply(total, count(takeProfitPct, ParamSampler.TAKE_PROFITS.length, false));
        total = multiply(total, count(stopLossPct, ParamSampler.STOP_LOSSES.length, false));
        total = multiply(total, sides.isEmpty() ? 2 : sides.size());
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** 展开整个参数网格（受 {@link #DEFAULT_EXPAND_LIMIT} 保护，超大网格会被截断）。 */
    public List<StrategyParams> expand(StrategyParams base) {
        return expand(base, DEFAULT_EXPAND_LIMIT);
    }

    /**
     * 展开参数网格，最多返回 {@code limit} 组。
     *
     * <p>循环顺序与 {@link ParamSampler#grid(StrategyParams)} 保持一致：
     * 方向 → 杠杆 → 层数 → 加仓间隔 → 加仓倍数 → 止盈 → 止损；
     * 固定维度（保证金、持仓时长、费率、资金费率、滑点）沿用 base。</p>
     */
    public List<StrategyParams> expand(StrategyParams base, int limit) {
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        validate();
        int cap = Math.min(limit, DEFAULT_EXPAND_LIMIT);
        List<String> sideValues = sides.isEmpty()
                ? List.of(StrategyParams.LONG, StrategyParams.SHORT)
                : sides;
        List<Integer> leverageValues = integralValues(leverage, ParamSampler.LEVERAGES, cap);
        List<Integer> maxLayerValues = integralValues(maxLayers, ParamSampler.MAX_LAYERS, cap);
        List<Double> stepValues = decimalValues(layerStepPct, ParamSampler.LAYER_STEPS, cap);
        List<Double> scaleValues = decimalValues(layerScale, ParamSampler.LAYER_SCALES, cap);
        List<Double> takeProfitValues = decimalValues(takeProfitPct, ParamSampler.TAKE_PROFITS, cap);
        List<Double> stopLossValues = decimalValues(stopLossPct, ParamSampler.STOP_LOSSES, cap);

        List<StrategyParams> out = new ArrayList<>(Math.min(cap, combos()));
        outer:
        for (String side : sideValues) {
            for (int leverageValue : leverageValues) {
                for (int maxLayerValue : maxLayerValues) {
                    for (double step : stepValues) {
                        for (double scale : scaleValues) {
                            for (double takeProfit : takeProfitValues) {
                                for (double stopLoss : stopLossValues) {
                                    if (out.size() >= limit) {
                                        break outer;
                                    }
                                    out.add(new StrategyParams(side, base.initialMarginUsd(), leverageValue,
                                            maxLayerValue, step, scale, takeProfit, stopLoss, base.maxHoldHours(),
                                            base.feeTaker(), base.fundingPer8hPct(), base.slippageBps()));
                                }
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ 内部

    private static void checkRange(String name, Range range, boolean integral, Double lower, boolean lowerExclusive,
            Double upper) {
        if (range == null) {
            return;
        }
        Double min = range.min();
        Double max = range.max();
        Double step = range.step();
        if (min == null || max == null || step == null) {
            throw new IllegalArgumentException("paramRanges." + name + ": min/max/step must be provided together");
        }
        if (!isFinite(min) || !isFinite(max) || !isFinite(step)) {
            throw new IllegalArgumentException("paramRanges." + name + ": min/max/step must be finite numbers");
        }
        if (min > max) {
            throw new IllegalArgumentException("paramRanges." + name + ": min must be <= max");
        }
        if (integral) {
            if (min != Math.rint(min) || max != Math.rint(max)) {
                throw new IllegalArgumentException("paramRanges." + name + ": min/max must be integers");
            }
            if (step < 1d || step != Math.rint(step)) {
                throw new IllegalArgumentException("paramRanges." + name + ": step must be an integer >= 1");
            }
        } else if (!(step > 0d)) {
            throw new IllegalArgumentException("paramRanges." + name + ": step must be > 0");
        }
        if (lower != null && (lowerExclusive ? min <= lower : min < lower)) {
            throw new IllegalArgumentException("paramRanges." + name + ": min must be "
                    + (lowerExclusive ? "> " : ">= ") + trim(lower));
        }
        if (upper != null && max > upper) {
            throw new IllegalArgumentException("paramRanges." + name + ": max must be <= " + trim(upper));
        }
    }

    /** 单个维度的取值个数；字段不全（未校验通过）时退回默认个数，保证 combos() 随时可调用。 */
    private static int count(Range range, int defaultCount, boolean integral) {
        if (range == null || range.min() == null || range.max() == null || range.step() == null) {
            return defaultCount;
        }
        double min = range.min();
        double max = range.max();
        double step = range.step();
        if (!(step > 0d) || max < min) {
            return defaultCount;
        }
        if (integral) {
            long span = Math.round(max) - Math.round(min);
            long stride = Math.max(1L, Math.round(step));
            long values = span / stride + 1L;
            if (span % stride != 0L) {
                values++;
            }
            return (int) Math.min(Integer.MAX_VALUE, values);
        }
        double raw = (max - min) / step;
        double eps = 1e-9d * Math.max(1d, Math.abs(raw));
        long steps = Math.max(0L, (long) Math.floor(raw + eps));
        return (int) Math.min(Integer.MAX_VALUE, steps + 1L);
    }

    private static List<Integer> integralValues(Range range, int[] defaults, int cap) {
        if (range == null || range.min() == null || range.max() == null || range.step() == null) {
            List<Integer> values = new ArrayList<>(defaults.length);
            for (int value : defaults) {
                values.add(value);
            }
            return List.copyOf(values);
        }
        int min = (int) Math.round(range.min());
        int max = (int) Math.round(range.max());
        int step = Math.max(1, (int) Math.round(range.step()));
        List<Integer> values = new ArrayList<>();
        for (int value = min; value <= max; value += step) {
            if (values.size() >= cap) {
                return List.copyOf(values);
            }
            values.add(value);
        }
        // 整数维保证包含 max（step 除不尽时补一个末值）
        if (values.isEmpty() || values.get(values.size() - 1) != max) {
            if (values.size() < cap) {
                values.add(max);
            }
        }
        return List.copyOf(values);
    }

    private static List<Double> decimalValues(Range range, double[] defaults, int cap) {
        if (range == null || range.min() == null || range.max() == null || range.step() == null) {
            List<Double> values = new ArrayList<>(defaults.length);
            for (double value : defaults) {
                values.add(value);
            }
            return List.copyOf(values);
        }
        double min = range.min();
        double max = range.max();
        double step = range.step();
        double raw = (max - min) / step;
        double eps = 1e-9d * Math.max(1d, Math.abs(raw));
        long steps = Math.max(0L, (long) Math.floor(raw + eps));
        List<Double> values = new ArrayList<>();
        for (long i = 0; i <= steps && values.size() < cap; i++) {
            values.add(round4(min + i * step));
        }
        return List.copyOf(values);
    }

    private static long multiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Integer.MAX_VALUE / right) {
            return Integer.MAX_VALUE;
        }
        return Math.min(Integer.MAX_VALUE, left * right);
    }

    /** 固定 4 位小数，抹掉 0.1 + 0.2 这类浮点误差。 */
    private static double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static boolean isFinite(Double value) {
        return value != null && !value.isNaN() && !value.isInfinite();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
