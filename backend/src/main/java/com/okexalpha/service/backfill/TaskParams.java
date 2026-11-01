package com.okexalpha.service.backfill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 写进 okx_backfill_task.params 的任务参数（各数据类型共用，未使用的字段为 null）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskParams(
        String dataType,
        String instId,
        String instType,
        String bar,
        String klineType,
        String ratioType,
        String scope,
        String scopeKind,
        String ccy,
        String period,
        String marketType,
        Long startMs,
        Long endMs,
        Integer pageLimit) {
}
