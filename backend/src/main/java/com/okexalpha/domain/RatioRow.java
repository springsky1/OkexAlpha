package com.okexalpha.domain;

import java.math.BigDecimal;

/** okx_long_short_ratio 一行。 */
public record RatioRow(
        String ratioType,
        String scope,
        String period,
        long tsMs,
        String scopeKind,
        BigDecimal longShortRatio) {
}
