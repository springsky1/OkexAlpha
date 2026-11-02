package com.okexalpha.domain;

/**
 * 最小下单量快照（okx_min_order 一行）。
 *
 * <p>notionalPerContractUsd：一张合约的美元名义价值（线性合约 = ctVal × 价格，反向合约 = ctVal 本身）。
 * minNotionalUsd：平台允许的最小下单金额 = minSz × notionalPerContractUsd。
 * 公式见 {@link com.okexalpha.service.strategy.MinOrderService}。</p>
 */
public record MinOrderRow(
        String instId,
        String instType,
        String settleCcy,
        Double ctVal,
        String ctValCcy,
        Double ctMult,
        Double lotSz,
        Double minSz,
        Double tickSz,
        Double refPx,
        Double notionalPerContractUsd,
        Double minNotionalUsd,
        Double minLots,
        boolean tradable,
        String note) {
}
