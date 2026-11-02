package com.okexalpha.strategy.engine;

/**
 * 影子引擎的纯计算工具：强平价 / 止损价 / 止盈价 / 未实现盈亏 / 加仓合并。
 *
 * <p>口径与 {@link MartingaleBacktester} 一致，否则影子账本会和回测结论对不上：</p>
 * <ul>
 *   <li>维持保证金率 0.5%，逐仓强平价 = 均价 × (1 ∓ 1/杠杆 ± 维持保证金率)；</li>
 *   <li>止损按"亏掉保证金的 stopLossPct%"折算成价格距离：distance = stopLossPct/100 × 保证金 / 张数；</li>
 *   <li>止盈直接按均价百分比。</li>
 * </ul>
 */
public final class ShadowMath {

    /** 维持保证金率（与 MartingaleBacktester 保持一致）。 */
    public static final double MAINTENANCE_MARGIN_RATE = 0.005d;

    private ShadowMath() {
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
