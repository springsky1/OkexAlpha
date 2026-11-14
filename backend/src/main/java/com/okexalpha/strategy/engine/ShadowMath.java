package com.okexalpha.strategy.engine;

/**
 * 影子引擎的纯计算工具：强平价 / 止损价 / 止盈价 / 未实现盈亏 / 加仓合并。
 *
 * <p>口径与 {@link MartingaleBacktester} 一致，否则影子账本会和回测结论对不上：</p>
 * <ul>
 *   <li>维持保证金率 0.5%，逐仓强平价 = 均价 × (1 ∓ 1/杠杆 ± 维持保证金率)；</li>
 *   <li>止损按"亏掉保证金的 stopLossPct%"折算成价格距离：distance = stopLossPct/100 × 保证金 / 张数；</li>
 *   <li>止盈直接按均价百分比。</li>
 *   <li>滑点随当根振幅缩放（{@link #slippageBps}）：固定 2bp 在 1m 级高波动 bar 上严重低估成交损耗。</li>
 * </ul>
 */
public final class ShadowMath {

    /** 维持保证金率（与 MartingaleBacktester 保持一致）。 */
    public static final double MAINTENANCE_MARGIN_RATE = 0.005d;

    /** 加仓后已用保证金最多占账户权益的比例（留 5% 缓冲垫）。 */
    public static final double LAYER_HEADROOM = 0.95d;

    /** 滑点缩放的基准振幅（%）：1m bar 振幅等于它时，滑点 = 参数值。 */
    public static final double SLIPPAGE_RANGE_REF_PCT = 0.2d;
    /** 缩放系数的上下限：再平静的行情也要付一半，再狂的行情也只放大到 5 倍。 */
    public static final double SLIPPAGE_MIN_FACTOR = 0.5d;
    public static final double SLIPPAGE_MAX_FACTOR = 5d;

    private ShadowMath() {
    }

    /**
     * 按当根振幅缩放后的单边滑点（bp）。
     *
     * <p>固定滑点等于假设"任何行情都能以同样的摩擦成交"，而高频马丁平仓集中在插针与急跌里：
     * 振幅 0.4% 的 bar 上再去按 2bp 成交，等于白送。这里让滑点与振幅成正比，
     * 并以 {@link #SLIPPAGE_RANGE_REF_PCT} 为基准做夹取（0.5× ~ 5×）。</p>
     *
     * <p>入参非法（负滑点、非有限价、high &lt; low）时退回基准值，绝不返回负数。</p>
     */
    public static double slippageBps(double baseBps, double high, double low, double refPx) {
        if (!(baseBps > 0d) || !Double.isFinite(baseBps)) {
            return 0d;
        }
        if (!(refPx > 0d) || !Double.isFinite(refPx) || !(high > 0d) || !(low > 0d) || high < low) {
            return baseBps;
        }
        double rangePct = (high - low) / refPx * 100d;
        if (!Double.isFinite(rangePct) || rangePct <= 0d) {
            return baseBps;
        }
        double factor = Math.min(SLIPPAGE_MAX_FACTOR,
                Math.max(SLIPPAGE_MIN_FACTOR, rangePct / SLIPPAGE_RANGE_REF_PCT));
        return baseBps * factor;
    }

    /** 逐仓强平价：多单跌破即强平，空单涨破即强平。 */
    public static double liqPrice(double avgEntry, boolean longSide, double leverage) {
        double move = 1d / leverage - MAINTENANCE_MARGIN_RATE;
        return longSide ? avgEntry * (1d - move) : avgEntry * (1d + move);
    }

    /** 止损价：distance = stopLossPct/100 × 保证金 / 张数，多单减、空单加。 */
    public static double stopPrice(double avgEntry, boolean longSide, double stopLossPct, double marginUsed, double qty) {
        if (qty <= 0d) {
            return avgEntry;
        }
        double distance = stopLossPct / 100d * marginUsed / qty;
        return longSide ? avgEntry - distance : avgEntry + distance;
    }

    /** 止盈价：均价 ± takeProfitPct%。 */
    public static double takeProfitPrice(double avgEntry, boolean longSide, double takeProfitPct) {
        return longSide ? avgEntry * (1d + takeProfitPct / 100d) : avgEntry * (1d - takeProfitPct / 100d);
    }

    /**
     * 加仓资金闸门：已用保证金 + 新层保证金 ≤ (账户权益 + 已实现盈亏) × 95%。
     *
     * <p>这是「回测能加满 8 层、实盘账户根本开不出来」的那道闸：回测器与影子引擎必须用同一条判据，
     * 否则两边又会各算一套。权益未知（≤ 0 或 NaN）时一律拒绝，宁可不加仓。</p>
     */
    public static boolean allowsLayer(double accountCapitalUsd, double realizedPnl, double marginUsed,
            double addMargin) {
        if (!(accountCapitalUsd > 0d) || !Double.isFinite(accountCapitalUsd)) {
            return false;
        }
        if (!(addMargin > 0d) || !Double.isFinite(addMargin) || !(marginUsed >= 0d)) {
            return false;
        }
        double equity = accountCapitalUsd + (Double.isFinite(realizedPnl) ? realizedPnl : 0d);
        if (!(equity > 0d)) {
            return false;
        }
        return marginUsed + addMargin <= equity * LAYER_HEADROOM + 1e-9;
    }

    /** 未实现盈亏（不含手续费与资金费）。 */
    public static double unrealized(double avgEntry, double markPx, double qty, boolean longSide) {
        return (markPx - avgEntry) * qty * (longSide ? 1d : -1d);
    }

    /** 加仓后的新均价（按名义金额加权），旧总名义 <= 0 时直接返回新价。 */
    public static double mergeAverage(double oldNotional, double oldAvg, double addNotional, double addPx) {
        double total = oldNotional + addNotional;
        if (total <= 0d) {
            return addPx;
        }
        return (oldNotional * oldAvg + addNotional * addPx) / total;
    }

    /** 第 n 层（1 基）的名义金额 = 基础名义 × layerScale^(n-1)。 */
    public static double layerNotional(double baseNotional, double layerScale, int layer) {
        return baseNotional * Math.pow(layerScale, Math.max(0, layer - 1));
    }

    /** 已知当前累计名义与层数，反推第一层基础名义。 */
    public static double baseNotional(double notional, double layerScale, int layers) {
        if (layers <= 0) {
            return notional;
        }
        double sum = 0d;
        for (int i = 1; i <= layers; i++) {
            sum += Math.pow(layerScale, i - 1);
        }
        return sum <= 0d ? notional : notional / sum;
    }
}
