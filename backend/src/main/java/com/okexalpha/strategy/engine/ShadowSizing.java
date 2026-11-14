package com.okexalpha.strategy.engine;

/**
 * 合约乘数换算：把「合约张数（sz）」与「币数量（qty）」显式互转。
 *
 * <p>OKX 线性合约（USDT 本位永续）的 {@code sz} 是<b>张数</b>，一张 = {@code ctVal} 个币；
 * 而 {@link ShadowMath} 的止损/盈亏公式吃的是<b>币数量</b>。历史实现直接把这些公式喂了张数，
 * BTC（ctVal=0.01）止损距离被压到 0.05%、盈亏放大 100 倍，DOGE（ctVal=1000）则永远打不到止损。</p>
 *
 * <p>口径约定：所有落库的 {@code okx_shadow_position.sz / okx_shadow_order.sz} 都是<b>张数</b>，
 * 与交易所 API 一致；凡是要进入 ShadowMath 的量，必须先用 {@link #coinQty(double, double)} 换算。</p>
 */
public final class ShadowSizing {

    /** 名义金额一致性容差：1 USD 或名义的 1%（取大者）。 */
    private static final double NOTIONAL_TOLERANCE_USD = 1d;
    private static final double NOTIONAL_TOLERANCE_RATIO = 0.01d;

    private ShadowSizing() {
    }

    /**
     * 取合约乘数：优先 okx_min_order.ct_val；缺失时用快照名义 / 快照价反推（linear 合约 ctVal × px）。
     *
     * @return ctVal > 0；信息不足时返回 0（调用方必须跳过，不能把张数当币数用）
     */
    public static double ctValue(Double ctVal, Double notionalPerContractUsd, Double refPx) {
        if (ctVal != null && ctVal > 0d && Double.isFinite(ctVal)) {
            return ctVal;
        }
        if (notionalPerContractUsd != null && notionalPerContractUsd > 0d
                && refPx != null && refPx > 0d && Double.isFinite(refPx)) {
            double derived = notionalPerContractUsd / refPx;
            return Double.isFinite(derived) && derived > 0d ? derived : 0d;
        }
        return 0d;
    }

    /** 一张合约的美元名义价值（按实时价，不用快照里的旧价）。 */
    public static double perContractUsd(double ctVal, double px) {
        if (!(ctVal > 0d) || !(px > 0d)) {
            return 0d;
        }
        return ctVal * px;
    }

    /** 张数 → 币数量。 */
    public static double coinQty(double contracts, double ctVal) {
        if (!(contracts > 0d) || !(ctVal > 0d)) {
            return 0d;
        }
        return contracts * ctVal;
    }

    /** 币数量 → 张数。 */
    public static double contracts(double coinQty, double ctVal) {
        if (!(coinQty > 0d) || !(ctVal > 0d)) {
            return 0d;
        }
        return coinQty / ctVal;
    }

    /**
     * 目标名义 → 张数，按 lotSz 向上取整并保证至少一个 lot。
     *
     * @param lotSz 最小变动张数，null/0/非法时按 1 处理
     */
    public static double contractsForNotional(double notionalUsd, double ctVal, double px, double lotSz) {
        double perContract = perContractUsd(ctVal, px);
        if (!(notionalUsd > 0d) || !(perContract > 0d)) {
            return 0d;
        }
        double lot = lotSz > 0d && Double.isFinite(lotSz) ? lotSz : 1d;
        double raw = notionalUsd / perContract;
        double lots = Math.ceil(raw / lot);
        if (lots < 1d) {
            lots = 1d;
        }
        return lots * lot;
    }

    /**
     * 不变量校验：名义金额必须约等于 张数 × ctVal × 价格。
     *
     * <p>用于识别历史污染行（sz 里混进了币数量）以及任何口径漂移；没有乘数信息时不判断。</p>
     */
    public static boolean notionalConsistent(double notionalUsd, double contracts, double ctVal, double px) {
        if (!(ctVal > 0d) || !(px > 0d) || !(contracts > 0d)) {
            return true;
        }
        double expected = contracts * ctVal * px;
        double tolerance = Math.max(NOTIONAL_TOLERANCE_USD, Math.abs(notionalUsd) * NOTIONAL_TOLERANCE_RATIO);
        return Math.abs(expected - notionalUsd) <= tolerance;
    }
}
