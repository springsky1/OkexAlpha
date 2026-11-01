package com.okexalpha.service.backfill;

import java.util.List;

/** POST /backfill/klines 的业务请求体。 */
public record KlineBackfillRequest(
        List<String> instIds,
        String instType,
        String baseCcy,
        List<String> bars,
        List<String> klineTypes,
        Long startMs,
        Long endMs,
        Integer pageLimit) {
}
