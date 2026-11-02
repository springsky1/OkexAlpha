package com.okexalpha.client.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.okexalpha.support.Fixtures;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.okexalpha.domain.FundingRateRow;

/** FundingRateMapper：DTO -> 行，含 settled 推导与字符串数值转换。比率列是 double precision。 */
class FundingRateMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsFundingHistoryRows() {
        List<FundingRateRow> rows = FundingRateMapper.toRows(fundingDtos(), "FUTURES");

        assertThat(rows).hasSize(2);

        FundingRateRow settled = rows.get(0);
        assertThat(settled.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(settled.instType()).isEqualTo("SWAP");
        assertThat(settled.fundingTimeMs()).isEqualTo(1735689600000L);
        assertThat(settled.fundingRate()).isEqualTo(0.00012345);
        assertThat(settled.realizedRate()).isEqualTo(0.00012);
        assertThat(settled.method()).isEqualTo("current_period");
        assertThat(settled.formulaType()).isEqualTo("withRateLimt");
        assertThat(settled.settled()).isTrue();
        assertThat(settled.premium()).isEqualTo(0.0002);
        assertThat(settled.interestRate()).isEqualTo(0.0001);
        assertThat(settled.nextFundingTimeMs()).isEqualTo(1735718400000L);
        assertThat(settled.impactValue()).isEqualTo(15000.0);
        assertThat(settled.minFundingRate()).isEqualTo(-0.00375);
        assertThat(settled.maxFundingRate()).isEqualTo(0.00375);
        assertThat(settled.tsMs()).isEqualTo(1735689601000L);

        FundingRateRow unsettled = rows.get(1);
        assertThat(unsettled.instType()).isEqualTo("FUTURES");
        assertThat(unsettled.fundingTimeMs()).isEqualTo(1735693200000L);
        assertThat(unsettled.settled()).isFalse();
        assertThat(unsettled.realizedRate()).isNull();
        assertThat(unsettled.nextFundingTimeMs()).isNull();
    }

    /** 用真实 OKX funding-rate-history 响应形状构造 DTO 列表（不联网）。 */
    private static List<OkxFundingRate> fundingDtos() {
        List<OkxFundingRate> dtos = new ArrayList<>();
        for (JsonNode node : Fixtures.json("okx-funding-history.json").path("data")) {
            dtos.add(MAPPER.readValue(MAPPER.writeValueAsString(node), OkxFundingRate.class));
        }
        return dtos;
    }

    @Test
    void rejectsElementsWithoutInstIdOrFundingTime() {
        OkxFundingRate missingId = new OkxFundingRate("SWAP", null, null, null, "1735689600000", null, null, null,
                null, null, null, null, null, null, null, null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FundingRateMapper.toRow(missingId, "SWAP"))
                .withMessageContaining("missing instId/fundingTime");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FundingRateMapper.toRow(null, "SWAP"))
                .withMessageContaining("missing instId/fundingTime");
    }

    @Test
    void parseLongRejectsNonNumericInput() {
        // parseLongOrNull 现在把 NumberFormatException 收敛成 null，因此 parseLong 的
        // "not a number: X" 分支对非数字文本可达，抛的是 IllegalArgumentException。
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FundingRateMapper.parseLong("not-a-number"))
                .withMessageContaining("not a number: not-a-number");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FundingRateMapper.parseLong(null))
                .withMessageContaining("not a number: null");

        assertThat(FundingRateMapper.parseLong("1735689600000")).isEqualTo(1735689600000L);
    }

    @Test
    void parseLongOrNullSwallowsNonNumericInput() {
        assertThat(FundingRateMapper.parseLongOrNull("abc")).isNull();
        assertThat(FundingRateMapper.parseLongOrNull("12.5")).isNull();
    }

    @Test
    void parseHelpersTolerateBlankValues() {
        assertThat(FundingRateMapper.parseLongOrNull(" ")).isNull();
        assertThat(FundingRateMapper.parseLongOrNull("  12 ")).isEqualTo(12L);
        assertThat(FundingRateMapper.decimal("")).isNull();
        assertThat(FundingRateMapper.decimal(" 1.5 ")).isEqualByComparingTo("1.5");
    }

    @Test
    void dblIsNullSafe() {
        assertThat(FundingRateMapper.dbl(null)).isNull();
        assertThat(FundingRateMapper.dbl(new java.math.BigDecimal("0.00012345"))).isEqualTo(0.00012345);
    }
}
