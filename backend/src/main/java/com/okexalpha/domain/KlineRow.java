package com.okexalpha.domain;

/**
 * okx_kline 一行；MARK（标记价格）没有量，vol/volCcy/volCcyQuote 为 null。
 * 数值列用 Double（表里是 double precision）：比 BigDecimal 省 varlena 头，
 * 且能走 TimescaleDB 列式压缩；行情价格/量级的 15~17 位有效数字足够。
 *
 * @param confirm true = 已收盘（OKX 的 1），false = 当前未收敛的 bar（OKX 的 0）
 */
public record KlineRow(
        String klineType,
        String instId,
        String bar,
        long tsMs,
        String instType,
        Double open,
        Double high,
        Double low,
        Double close,
        Double vol,
        Double volCcy,
        Double volCcyQuote,
        boolean confirm) {
}
