package com.okexalpha.strategy.engine;

/**
 * 回测的一笔完整交易（含多次加仓，成本已含手续费、滑点与资金费）。
 *
 * @param layers        实际加仓层数（1 = 只开首仓）
 * @param avgEntry      加权平均开仓价
 * @param maxAdversePct 持仓期间最大不利幅度（%），用于评估爆仓距离
 */
public record BacktestTrade(
        String side,
        long entryTsMs,
        long exitTsMs,
        double avgEntry,
        double exitPx,
        double qty,
        double notionalUsd,
        double pnl,
        double pnlPct,
        double fee,
        double funding,
        int layers,
        double maxAdversePct,
        String exitReason) {
}
