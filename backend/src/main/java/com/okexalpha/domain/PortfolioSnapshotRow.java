package com.okexalpha.domain;

import java.time.OffsetDateTime;

/** 组合快照（okx_portfolio_snapshot 一行，影子引擎每轮写一次）。 */
public record PortfolioSnapshotRow(
        long id,
        long tsMs,
        Double equityUsd,
        Double availableUsd,
        Integer positionCount,
        Double grossNotionalUsd,
        Double netNotionalUsd,
        Double unrealizedPnlUsd,
        OffsetDateTime createdAt) {
}
