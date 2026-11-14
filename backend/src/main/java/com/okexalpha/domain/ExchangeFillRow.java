package com.okexalpha.domain;

/** OKX 逐笔成交的本地镜像行（okx_exchange_fill）。 */
public record ExchangeFillRow(
        String tradeId,
        String ordId,
        String instId,
        String instType,
        String side,
        String posSide,
        Double fillSz,
        Double fillPx,
        Long fillTimeMs,
        Double fee,
        String feeCcy,
        String execType,
        String tag,
        Double pnl) {
}
