package com.okexalpha.domain;

import java.math.BigDecimal;

/** okx_instrument 一行。 */
public record InstrumentRow(
        String instId,
        String instType,
        String instFamily,
        String uly,
        String baseCcy,
        String quoteCcy,
        String settleCcy,
        String ctType,
        BigDecimal ctVal,
        String ctValCcy,
        BigDecimal ctMult,
        BigDecimal lever,
        BigDecimal tickSz,
        BigDecimal lotSz,
        BigDecimal minSz,
        Long listTimeMs,
        Long expTimeMs,
        String state,
        String ruleType,
        String category,
        String instCategory,
        String optionType,
        String alias,
        String raw) {
}
