package com.okexalpha.strategy.engine;

import java.util.List;

/** 一次回测的完整输出：指标 + 逐笔成交 + 资金曲线。 */
public record BacktestOutcome(BacktestMetrics metrics, List<BacktestTrade> trades, List<EquityPoint> equity) {
}
