package com.okexalpha.strategy.engine;

/**
 * 回测用的一根 K 线（只保留价格；量不参与马丁决策）。
 *
 * @param tsMs  该 bar 的起始时间（毫秒）
 */
public record PriceBar(long tsMs, double open, double high, double low, double close) {

    /** bar 之间是等距的（1m = 60000），用相邻 bar 差值求间隔时用。 */
    public static final long ONE_MINUTE = 60_000L;
}
