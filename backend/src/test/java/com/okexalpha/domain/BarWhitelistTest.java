package com.okexalpha.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 白名单枚举：合法值往返、utc 变体、非法值拒绝、保留期与窗口期关系。 */
class BarWhitelistTest {

    @ParameterizedTest
    @EnumSource(Bar.class)
    void barCodeRoundTrips(Bar bar) {
        assertThat(Bar.fromCode(bar.code())).isSameAs(bar);
        assertThat(bar.code()).isNotBlank();
        assertThat(bar.intervalMs()).isPositive();
    }

    @Test
    void barCodesAreUnique() {
        String[] codes = Arrays.stream(Bar.values()).map(Bar::code).toArray(String[]::new);

        assertThat(Arrays.stream(codes).distinct().count()).isEqualTo(codes.length);
    }

    @Test
    void barContainsUtcVariants() {
        assertThat(Bar.fromCode("6Hutc")).isSameAs(Bar.H6_UTC);
        assertThat(Bar.fromCode("12Hutc")).isSameAs(Bar.H12_UTC);
        assertThat(Bar.fromCode("1Mutc")).isSameAs(Bar.MO1_UTC);
        assertThat(Bar.fromCode("3Mutc")).isSameAs(Bar.MO3_UTC);
        assertThat(Bar.H6_UTC.code()).isEqualTo("6Hutc");
        assertThat(Bar.MO1_UTC.code()).isEqualTo("1Mutc");
        assertThat(Bar.H6_UTC.intervalMs()).isEqualTo(Bar.H6.intervalMs());
    }

    @Test
    void barRejectsUnknownCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Bar.fromCode("1h"))
                .withMessageContaining("invalid bar: 1h");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Bar.fromCode("7m"))
                .withMessageContaining("invalid bar");
        assertThatThrownBy(() -> Bar.fromCode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(KlineType.class)
    void klineTypeCodeRoundTrips(KlineType type) {
        assertThat(KlineType.fromCode(type.name())).isSameAs(type);
        assertThat(KlineType.fromCode(type.name().toLowerCase())).isSameAs(type);
        assertThat(type.path()).startsWith("/api/v5/market/history");
    }

    @Test
    void onlyCandleCarriesVolumeColumns() {
        assertThat(KlineType.CANDLE.hasVolume()).isTrue();
        assertThat(KlineType.MARK.hasVolume()).isFalse();
        assertThat(KlineType.CANDLE.path()).isEqualTo("/api/v5/market/history-candles");
        assertThat(KlineType.MARK.path()).isEqualTo("/api/v5/market/history-mark-price-candles");
    }

    @Test
    void klineTypeOnlyContainsCandleAndMark() {
        assertThat(KlineType.values()).containsExactly(KlineType.CANDLE, KlineType.MARK);
        assertThat(Arrays.stream(KlineType.values()).map(KlineType::name).toList())
                .doesNotContain("INDEX");
    }

    @Test
    void klineTypeRejectsUnknownCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KlineType.fromCode("BAR"))
                .withMessageContaining("invalid klineType: BAR")
                .withMessageContaining("allowed: CANDLE,MARK");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KlineType.fromCode(null))
                .withMessageContaining("klineType is required");
    }

    @Test
    void klineTypeRejectsRemovedIndexCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KlineType.fromCode("INDEX"))
                .withMessageContaining("invalid klineType: INDEX")
                .withMessageContaining("allowed: CANDLE,MARK");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KlineType.fromCode("index"))
                .withMessageContaining("invalid klineType: index");
    }

    @Test
    void periodRetentionAndWindowsAreConsistent() {
        assertThat(Period.M5.retentionMs()).isEqualTo(48L * 3_600_000L);
        assertThat(Period.H1.retentionMs()).isEqualTo(30L * 86_400_000L);
        assertThat(Period.D1.retentionMs()).isEqualTo(180L * 86_400_000L);

        for (Period period : Period.values()) {
            assertThat(period.windowMs()).isPositive();
            assertThat(period.windowMs()).isLessThanOrEqualTo(period.retentionMs());
            assertThat(Period.fromCode(period.code())).isSameAs(period);
        }
    }

    @Test
    void periodRejectsUnknownCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Period.fromCode("1d"))
                .withMessageContaining("invalid period: 1d");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Period.fromCode("1m"))
                .withMessageContaining("invalid period");
    }

    @ParameterizedTest
    @EnumSource(RatioType.class)
    void ratioTypeCodeRoundTrips(RatioType type) {
        assertThat(RatioType.fromCode(type.name())).isSameAs(type);
        assertThat(type.path()).startsWith("/api/v5/rubik/stat/contracts/");
        assertThat(type.scopeKind()).isNotNull();
    }

    @Test
    void onlyAccountCcyRatioIsCcyScoped() {
        assertThat(RatioType.LSR_ACCOUNT_CCY.scopeKind()).isEqualTo(RatioType.ScopeKind.CCY);
        assertThat(RatioType.LSR_ACCOUNT_CONTRACT.scopeKind()).isEqualTo(RatioType.ScopeKind.INST);
        assertThat(RatioType.TOP_TRADER_ACCOUNT.scopeKind()).isEqualTo(RatioType.ScopeKind.INST);
        assertThat(RatioType.TOP_TRADER_POSITION.scopeKind()).isEqualTo(RatioType.ScopeKind.INST);
        assertThat(RatioType.TOP_TRADER_POSITION.path())
                .isEqualTo("/api/v5/rubik/stat/contracts/long-short-position-ratio-contract-top-trader");
    }

    @Test
    void ratioTypeRejectsUnknownCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RatioType.fromCode("LSR"))
                .withMessageContaining("invalid ratioType: LSR");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RatioType.fromCode(null))
                .withMessageContaining("ratioType is required");
    }

    @ParameterizedTest
    @EnumSource(InstType.class)
    void instTypeCodeRoundTrips(InstType type) {
        assertThat(InstType.fromCode(type.code())).isSameAs(type);
        assertThat(type.code()).isEqualTo(type.name());
    }

    @Test
    void derivativeFlagMatchesOkxSemantics() {
        assertThat(InstType.SWAP.isDerivative()).isTrue();
        assertThat(InstType.FUTURES.isDerivative()).isTrue();
        assertThat(InstType.OPTION.isDerivative()).isTrue();
        assertThat(InstType.SPOT.isDerivative()).isFalse();
        assertThat(InstType.MARGIN.isDerivative()).isFalse();
    }

    @Test
    void instTypeAcceptsLowerCaseAndTrims() {
        assertThat(InstType.fromCode(" swap ")).isSameAs(InstType.SWAP);
        assertThat(InstType.fromCode("Spot")).isSameAs(InstType.SPOT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> InstType.fromCode("PERP"))
                .withMessageContaining("invalid instType: PERP");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> InstType.fromCode(null))
                .withMessageContaining("instType is required");
    }
}
