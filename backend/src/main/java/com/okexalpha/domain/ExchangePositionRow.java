package com.okexalpha.domain;

/** OKX 已平仓仓位的本地镜像行（okx_exchange_position）。 */
public record ExchangePositionRow(
        String instId,
        String instType,
        String mgnMode,
        String posSide,
        String type,
        Double lever,
        Double openAvgPx,
        Double closeAvgPx,
        Double markPx,
        Double pnl,
        Double pnlRatio,
        Double realizedPnl,
        Double fee,
        Double fundingFee,
        Double liqPx,
        Long cTimeMs,
        Long uTimeMs) {
}
