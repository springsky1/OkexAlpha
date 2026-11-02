package com.okexalpha.domain;

/** okx_funding_rate 一行（资金时间为主键一部分）；比率列与表一致使用 double precision。 */
public record FundingRateRow(
        String instId,
        String instType,
        long fundingTimeMs,
        Double fundingRate,
        Double realizedRate,
        String method,
        String formulaType,
        boolean settled,
        Double premium,
        Double interestRate,
        Long nextFundingTimeMs,
        Double impactValue,
        Double minFundingRate,
        Double maxFundingRate,
        Long tsMs) {
}
