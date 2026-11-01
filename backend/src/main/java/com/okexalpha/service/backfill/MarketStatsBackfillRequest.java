package com.okexalpha.service.backfill;

import java.util.List;

/** POST /backfill/market-stats 的业务请求体。 */
public record MarketStatsBackfillRequest(
        List<String> kinds,
        List<String> ccys,
        List<String> instIds,
        List<String> periods,
        Long startMs,
        Long endMs) {
}
