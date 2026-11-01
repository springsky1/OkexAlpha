package com.okexalpha.web.dto;

import java.util.List;

/** 同步相关请求体。 */
public final class SyncRequests {

    private SyncRequests() {
    }

    public record InstrumentSyncRequest(List<String> instTypes, Boolean includeOptions, List<String> families) {
    }

    public record BasicSyncRequest(
            List<String> instTypes,
            Boolean includeOptions,
            List<String> families,
            String instType,
            Integer maxInstruments,
            Integer maxCcys,
            List<String> bars,
            List<String> klineTypes,
            Integer days,
            Boolean includeFunding,
            Boolean includeRatios,
            List<String> ratioTypes) {
    }
}
