package com.okexalpha.strategy.engine;

import java.util.List;

/** 一次回测的完整输出：指标 + 逐笔成交 + 资金曲线 + 窗口末状态（可续跑）。 */
public record BacktestOutcome(BacktestMetrics metrics, List<BacktestTrade> trades, List<EquityPoint> equity,
        EngineState endState) {

    public BacktestOutcome(BacktestMetrics metrics, List<BacktestTrade> trades, List<EquityPoint> equity) {
        this(metrics, trades, equity, EngineState.flat(0d));
    }
}
