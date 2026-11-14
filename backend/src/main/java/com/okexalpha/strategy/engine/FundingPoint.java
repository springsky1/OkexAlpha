package com.okexalpha.strategy.engine;

/**
 * 一次资金费结算（方案 §2.4 / F5）。时间与费率都取自 {@code okx_funding_rate}，
 * 引擎不再用"开仓 bar + n×8h"推算。
 *
 * @param tsMs 结算时刻（funding_time_ms）
 * @param rate 该次结算的费率（小数，0.0001 = 0.01%）；正数 = 多头付给空头
 */
public record FundingPoint(long tsMs, double rate) {
}
