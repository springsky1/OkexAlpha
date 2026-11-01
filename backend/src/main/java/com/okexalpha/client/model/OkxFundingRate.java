package com.okexalpha.client.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** funding-rate / funding-rate-history 的 data 元素。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OkxFundingRate(
        String instType,
        String instId,
        BigDecimal fundingRate,
        BigDecimal realizedRate,
        String fundingTime,
        String nextFundingTime,
        BigDecimal premium,
        BigDecimal interestRate,
        String method,
        String formulaType,
        String settState,
        String settledState,
        BigDecimal impactValue,
        BigDecimal minFundingRate,
        BigDecimal maxFundingRate,
        String ts) {
}
