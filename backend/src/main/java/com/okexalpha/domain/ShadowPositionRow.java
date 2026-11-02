package com.okexalpha.domain;

import java.time.OffsetDateTime;

/** 影子持仓（okx_shadow_position 一行，本地账本）。 */
public record ShadowPositionRow(
        long id,
        Long strategyId,
        String instId,
        String side,
        double sz,
        Double entryPx,
        Double markPx,
        Double notionalUsd,
        Double unrealizedPnl,
        Double leverage,
        Double liqPx,
        Double marginUsd,
        String status,
        OffsetDateTime openedAt,
        OffsetDateTime updatedAt,
        OffsetDateTime closedAt) {
}
