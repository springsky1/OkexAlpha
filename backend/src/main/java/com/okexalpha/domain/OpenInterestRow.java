package com.okexalpha.domain;

import java.math.BigDecimal;

/** okx_open_interest 一行（public/open-interest 快照）。 */
public record OpenInterestRow(
        String instId,
        String instType,
        long tsMs,
        BigDecimal oi,
        BigDecimal oiCcy,
        BigDecimal oiUsd) {
}
