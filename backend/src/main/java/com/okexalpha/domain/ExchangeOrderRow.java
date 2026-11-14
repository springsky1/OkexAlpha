package com.okexalpha.domain;

/**
 * OKX 委托订单的本地镜像行（okx_exchange_order）。
 *
 * <p>数字字段用包装类型保留 null（OKX 对市价单/开仓单常返回空串），时间统一存毫秒。</p>
 */
public record ExchangeOrderRow(
        String ordId,
        String clOrdId,
        String instId,
        String instType,
        String tdMode,
        String side,
        String posSide,
        String ordType,
        Double sz,
        Double px,
        Double avgPx,
        Double fillSz,
        String state,
        Double lever,
        Double fee,
        String feeCcy,
        Double pnl,
        Boolean reduceOnly,
        String algoId,
        String cancelSource,
        Long cTimeMs,
        Long uTimeMs,
        boolean archived) {
}
