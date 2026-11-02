package com.okexalpha.strategy.engine;

/**
 * 马丁策略参数（单币单方向的完整描述）。
 *
 * <p>资金口径：每个币投入 {@code initialMarginUsd} 作为保证金，第 i 层开仓的名义价值为
 * {@code initialMarginUsd × leverage × layerScale^(i-1)}，所以层数越多占用保证金越多，
 * 但杠杆始终按"当前总名义 / 初始保证金"的预算来加（超过就在层数上截断）。</p>
 *
 * @param side            long / short
 * @param initialMarginUsd 单币保证金（美元）
 * @param leverage         名义杠杆
 * @param maxLayers        最多加仓层数（含首仓）
 * @param layerStepPct     逆向加仓间距（相对上一层的价格跌幅 %）
 * @param layerScale       每层名义倍数（1 = 等额，1.5 = 递增）
 * @param takeProfitPct    止盈（相对持仓均价的 %）
 * @param stopLossPct      止损（亏损达到已用保证金的 %）
 * @param maxHoldHours     最长持有小时数，超时平仓
 * @param feeTaker         单边费率（0.0005 = 0.05%）
 * @param fundingPer8hPct  每 8 小时资金费率 %（正数 = 多头付给空头）
 * @param slippageBps      单边滑点（基点，2 = 0.02%）
 */
public record StrategyParams(
        String side,
        double initialMarginUsd,
        int leverage,
        int maxLayers,
        double layerStepPct,
        double layerScale,
        double takeProfitPct,
        double stopLossPct,
        int maxHoldHours,
        double feeTaker,
        double fundingPer8hPct,
        double slippageBps) {

    public static final String LONG = "long";
    public static final String SHORT = "short";

    public StrategyParams {
        if (side == null || !(LONG.equals(side) || SHORT.equals(side))) {
            throw new IllegalArgumentException("side must be long or short: " + side);
        }
        if (initialMarginUsd <= 0) {
            throw new IllegalArgumentException("initialMarginUsd must be > 0");
        }
        if (leverage < 1) {
            throw new IllegalArgumentException("leverage must be >= 1");
        }
        if (maxLayers < 1) {
            throw new IllegalArgumentException("maxLayers must be >= 1");
        }
        if (layerStepPct <= 0) {
            throw new IllegalArgumentException("layerStepPct must be > 0");
        }
        if (layerScale < 1) {
            throw new IllegalArgumentException("layerScale must be >= 1");
        }
        if (takeProfitPct <= 0) {
            throw new IllegalArgumentException("takeProfitPct must be > 0");
        }
        if (stopLossPct <= 0 || stopLossPct > 100) {
            throw new IllegalArgumentException("stopLossPct must be in (0,100]");
        }
        if (maxHoldHours < 1) {
            throw new IllegalArgumentException("maxHoldHours must be >= 1");
        }
        if (feeTaker < 0 || fundingPer8hPct < 0 || slippageBps < 0) {
            throw new IllegalArgumentException("fee/funding/slippage must be >= 0");
        }
    }

    /**
     * 派生方向判断。刻意不命名成 {@code isLong()}：Jackson 会把 {@code isXxx()} 当成属性，
     * 序列化 params 时多写一个冗余的 "long" 字段（Jackson 3 不再识别 JsonIgnore）。
     */
    public boolean longSide() {
        return LONG.equals(side);
    }

    /** 参数签名（用于分组/去重，不含资金与成本项）。 */
    public String key() {
        return side + "|" + leverage + "|" + maxLayers + "|" + layerStepPct + "|" + layerScale + "|"
                + takeProfitPct + "|" + stopLossPct;
    }

    /** 邻域扰动：按比例缩放连续参数（网格精调用）；止损按反比放大并夹在 (0,100]。 */
    public StrategyParams withScale(double factor) {
        if (!(factor > 0) || !Double.isFinite(factor)) {
            throw new IllegalArgumentException("factor must be a positive finite number: " + factor);
        }
        double stopLoss = Math.min(100d, stopLossPct * Math.max(0.5d, 1d / factor));
        return new StrategyParams(side, initialMarginUsd, leverage, maxLayers,
                layerStepPct * factor, layerScale, takeProfitPct * factor, stopLoss,
                maxHoldHours, feeTaker, fundingPer8hPct, slippageBps);
    }
}
