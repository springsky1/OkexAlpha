package com.okexalpha.client.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** /api/v5/public/instruments 的 data 元素（OKX 数值字段以字符串下发，Jackson 自动转 BigDecimal）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OkxInstrument(
        String instType,
        String instId,
        String instFamily,
        String uly,
        String category,
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
        Long listTime,
        Long expTime,
        String state,
        String ruleType,
        String instCategory,
        String optType,
        String alias) {
}
