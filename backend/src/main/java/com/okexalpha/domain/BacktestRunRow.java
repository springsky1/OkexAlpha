package com.okexalpha.domain;

import java.time.OffsetDateTime;

/** okx_backtest_run 一行（回测批次）。 */
public record BacktestRunRow(
        long id,
        Long strategyId,
        String label,
        String status,
        String bar,
        Long startMs,
        Long endMs,
        String phase,
        String request,
        int coinTotal,
        int coinDone,
        int combosTotal,
        int combosDone,
        Long bestResultId,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
