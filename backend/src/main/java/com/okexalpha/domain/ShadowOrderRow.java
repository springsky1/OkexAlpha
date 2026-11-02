package com.okexalpha.domain;

import java.time.OffsetDateTime;

/** 影子订单（okx_shadow_order 一行，纯本地记账，不发真实单）。 */
public record ShadowOrderRow(
        long id,
        Long strategyId,
        String instId,
        String side,
        String ordType,
        Double sz,
        Double px,
        Double notionalUsd,
        String status,
        String reason,
        Long signalTsMs,
        OffsetDateTime decidedAt) {
}
