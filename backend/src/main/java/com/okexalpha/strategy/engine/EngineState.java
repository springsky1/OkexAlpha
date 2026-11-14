package com.okexalpha.strategy.engine;

/**
 * 可序列化的引擎状态（方案 §2.5）：窗口末把持仓原样交给下一次回放，<b>不再强制平仓</b>。
 *
 * <p>{@code feePaid}/{@code fundingPaid}/{@code worstAdversePct} 是"当前持仓"的累计量，
 * 开新仓时清零；平仓后它们留在最后一次的值上（只作审计，不参与下次计算）。
 * {@code peakMarginUsed} 是整段会话的峰值已用保证金，收益率分母用它（F6）。</p>
 */
public record EngineState(
        boolean inPos,
        int layers,
        double qty,
        double avgEntry,
        double firstEntryPx,
        double costNotional,
        double marginUsed,
        double peakMarginUsed,
        double lastLayerPx,
        long entryTsMs,
        long lastFundingTsMs,
        double realizedPnl,
        double feePaid,
        double fundingPaid,
        double peakEquity,
        double initialCapital,
        double worstAdversePct) {

    public static EngineState flat(double capital) {
        return new EngineState(false, 0, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0L, 0L, 0d, 0d, 0d, capital, capital, 0d);
    }
}
