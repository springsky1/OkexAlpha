package com.okexalpha.client.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.okexalpha.domain.FundingRateRow;

/** OkxFundingRate -> FundingRateRow。 */
public final class FundingRateMapper {

    private FundingRateMapper() {
    }

    public static FundingRateRow toRow(OkxFundingRate dto, String defaultInstType) {
        if (dto == null || dto.instId() == null || dto.fundingTime() == null) {
            throw new IllegalArgumentException("funding-rate element missing instId/fundingTime");
        }
        long fundingTime = parseLong(dto.fundingTime());
        boolean settled = "settled".equalsIgnoreCase(dto.settState())
                || "settled".equalsIgnoreCase(dto.settledState())
                || dto.realizedRate() != null;
        return new FundingRateRow(
                dto.instId(),
                dto.instType() != null ? dto.instType() : defaultInstType,
                fundingTime,
                dto.fundingRate(),
                dto.realizedRate(),
                dto.method(),
                dto.formulaType(),
                settled,
                dto.premium(),
                dto.interestRate(),
                parseLongOrNull(dto.nextFundingTime()),
                dto.impactValue(),
                dto.minFundingRate(),
                dto.maxFundingRate(),
                parseLongOrNull(dto.ts()));
    }

    public static List<FundingRateRow> toRows(List<OkxFundingRate> dtos, String defaultInstType) {
        List<FundingRateRow> rows = new ArrayList<>();
        if (dtos == null) {
            return rows;
        }
        for (OkxFundingRate dto : dtos) {
            rows.add(toRow(dto, defaultInstType));
        }
        return rows;
    }

    public static long parseLong(String text) {
        Long value = parseLongOrNull(text);
        if (value == null) {
            throw new IllegalArgumentException("not a number: " + text);
        }
        return value;
    }

    public static Long parseLongOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static BigDecimal decimal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return new BigDecimal(text.trim());
    }
}
