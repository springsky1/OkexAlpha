package com.okexalpha.domain;

import java.math.BigDecimal;

/** okx_funding_rate 一行（资金时间为主键一部分）。 */
public record FundingRateRow(
        String instId,
        String instType,
        long fundingTimeMs,
        BigDecimal fundingRate,
        BigDecimal realizedRate,
        String method,
        String formulaType,
        boolean settled,
        BigDecimal premium,
        BigDecimal interestRate,
        Long nextFundingTimeMs,
        BigDecimal impactValue,
        BigDecimal minFundingRate,
        BigDecimal maxFundingRate,
        Long tsMs) {
}
