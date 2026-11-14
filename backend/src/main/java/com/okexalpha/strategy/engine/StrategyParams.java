package com.okexalpha.strategy.engine;

/**
 * 马丁策略参数（单币单方向的完整描述）。
 *
 * <p>资金口径：每个币投入 {@code initialMarginUsd} 作为保证金，第 i 层开仓的名义价值为
 * {@code initialMarginUsd × leverage × layerScale^(i-1)}。层数不是无限制的：已用保证金（含新层）
 * 必须落在账户权益 {@code accountCapitalUsd} 的 95% 以内，否则这一层不开 —— 这就是
 * {@link ShadowMath#allowsLayer} 的判据，回测与影子账本共用同一条。</p>
 *
 * <p>为什么不复用 {@code initialMarginUsd} 当权益：首仓保证金恰好等于它，两者合一就等于
 * "一开仓就把账户占满"，多层策略全部退化成单层；回测里那个 8 层加到 3000U 的组合也会被
 * 当成"100U 账户能跑"。</p>
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
 * @param feeTaker         吃单费率（0.0005 = 0.05%）；开仓与止损/超时平仓都按它算
 * @param feeMaker         挂单费率（0.0002 = 0.02%）；止盈是限价单，按它算（比吃单便宜，回测不能一律按吃单）
 * @param fundingPer8hPct  每 8 小时资金费率 %（正数 = 多头付给空头）
 * @param slippageBps      单边滑点（基点，2 = 0.02%）
 * @param accountCapitalUsd 该币可动用的账户权益（美元）；<= 0 或缺失时归一成 {@code initialMarginUsd}，
 *                          即"只够开首仓、不加仓"（fail-closed）
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
        double feeMaker,
        double fundingPer8hPct,
        double slippageBps,
        double accountCapitalUsd) {

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
        if (feeTaker < 0 || feeMaker < 0 || fundingPer8hPct < 0 || slippageBps < 0) {
            throw new IllegalArgumentException("fee/funding/slippage must be >= 0");
        }
        if (!(accountCapitalUsd > 0d) || !Double.isFinite(accountCapitalUsd)) {
            // 没给账户权益 = 只能确认首仓预算：宁可少加仓，也不能凭空放大名义
            accountCapitalUsd = initialMarginUsd;
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
                maxHoldHours, feeTaker, feeMaker, fundingPer8hPct, slippageBps, accountCapitalUsd);
    }
}
