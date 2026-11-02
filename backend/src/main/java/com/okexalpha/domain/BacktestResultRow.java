package com.okexalpha.domain;

/** okx_backtest_result 一行（一个参数组合或一个币的明细）。params/metrics 是 jsonb 原文。 */
public record BacktestResultRow(
        long id,
        long runId,
        String paramKey,
        String params,
        String instId,
        String segment,
        String metrics,
        int coinCount,
        int tradeCount,
        Double winRate,
        Double netPnl,
        Double netPnlPct,
        Double maxDrawdownPct,
        Double sharpe,
        int liquidationCount,
        Double oosNetPnlPct,
        String verdict,
        Integer rank) {
}
