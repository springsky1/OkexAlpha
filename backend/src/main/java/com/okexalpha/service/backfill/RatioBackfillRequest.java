package com.okexalpha.service.backfill;

import java.util.List;

/** POST /backfill/ratios 的业务请求体。 */
public record RatioBackfillRequest(
        List<String> ratioTypes,
        List<String> scopes,
        List<String> ccys,
        List<String> periods,
        Long startMs,
        Long endMs) {
}
