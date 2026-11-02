package com.okexalpha.strategy.engine;

/** 资金曲线上的一个点（按自然日聚合，最后一个点取最后一根 bar）。 */
public record EquityPoint(long tsMs, double equity, double drawdownPct) {
}
