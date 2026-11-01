package com.okexalpha.service.backfill;

import java.util.List;

/** POST /backfill/funding-rates 的业务请求体。 */
public record FundingBackfillRequest(
        List<String> instIds,
        String instType,
        Long startMs,
        Long endMs) {
}
