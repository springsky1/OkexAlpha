package com.okexalpha.domain;

import java.math.BigDecimal;

/** okx_kline 一行；MARK（标记价格）没有量，相关列为 null。 */
public record KlineRow(
        String klineType,
        String instId,
        String bar,
        long tsMs,
        String instType,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal vol,
        BigDecimal volCcy,
        BigDecimal volCcyQuote,
        int confirm) {
}
