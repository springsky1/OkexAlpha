package com.okexalpha.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.okexalpha.domain.OpenInterestRow;
import com.okexalpha.domain.OpenInterestVolumeRow;
import com.okexalpha.domain.RatioRow;
import com.okexalpha.domain.TakerVolumeRow;
import com.okexalpha.support.Fixtures;

/** RubikParser：rubik 数组/对象形状的解析与脏行跳过。 */
class RubikParserTest {

    @Test
    void parsesRatioArraysAndSkipsBlankOrTruncatedRows() {
        List<RatioRow> rows = RubikParser.parseRatios(Fixtures.data("okx-rubik-ratio.json"),
                "LSR_ACCOUNT_CCY", "BTC", "CCY", "5m");

        assertThat(rows).hasSize(2);
        RatioRow first = rows.get(0);
        assertThat(first.ratioType()).isEqualTo("LSR_ACCOUNT_CCY");
        assertThat(first.scope()).isEqualTo("BTC");
        assertThat(first.scopeKind()).isEqualTo("CCY");
        assertThat(first.period()).isEqualTo("5m");
        assertThat(first.tsMs()).isEqualTo(1735686000000L);
        assertThat(first.longShortRatio()).isEqualByComparingTo("1.2345");
        assertThat(rows.get(1).longShortRatio()).isEqualByComparingTo("1.2401");
    }

    @Test
    void parseRatiosToleratesNullInput() {
        assertThat(RubikParser.parseRatios(null, "LSR_ACCOUNT_CCY", "BTC", "CCY", "5m")).isEmpty();
    }

    @Test
    void parsesOpenInterestVolumeWithMissingVolume() {
        List<OpenInterestVolumeRow> rows = RubikParser.parseOpenInterestVolume(
                Fixtures.data("okx-rubik-oi-volume.json"), "BTC", "5m");

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).ccy()).isEqualTo("BTC");
        assertThat(rows.get(0).period()).isEqualTo("5m");
        assertThat(rows.get(0).tsMs()).isEqualTo(1735686000000L);
        assertThat(rows.get(0).oi()).isEqualByComparingTo("120000.5");
        assertThat(rows.get(0).vol()).isEqualByComparingTo("3400.25");
        assertThat(rows.get(2).oi()).isEqualByComparingTo("122000.0");
        assertThat(rows.get(2).vol()).isNull();
    }

    @Test
    void parsesTakerVolumeArrays() {
        List<TakerVolumeRow> rows = RubikParser.parseTakerVolume(Fixtures.data("okx-rubik-taker-volume.json"),
                "CCY", "BTC", "CONTRACTS", "1H");

        assertThat(rows).hasSize(2);
        TakerVolumeRow first = rows.get(0);
        assertThat(first.scopeKind()).isEqualTo("CCY");
        assertThat(first.scope()).isEqualTo("BTC");
        assertThat(first.marketType()).isEqualTo("CONTRACTS");
        assertThat(first.period()).isEqualTo("1H");
        assertThat(first.sellVol()).isEqualByComparingTo("560.5");
        assertThat(first.buyVol()).isEqualByComparingTo("610.25");
    }

    @Test
    void parsesOpenInterestSnapshotsWithDefaultsAndSkipsRowsWithoutTs() {
        List<OpenInterestRow> rows = RubikParser.parseOpenInterest(Fixtures.data("okx-open-interest.json"),
                "DEFAULT-ID", "SWAP");

        assertThat(rows).hasSize(2);
        OpenInterestRow first = rows.get(0);
        assertThat(first.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(first.instType()).isEqualTo("SWAP");
        assertThat(first.tsMs()).isEqualTo(1735689600000L);
        assertThat(first.oi()).isEqualByComparingTo("12345.6");
        assertThat(first.oiCcy()).isEqualByComparingTo("12345.6");
        assertThat(first.oiUsd()).isEqualByComparingTo("1180000000.0");

        OpenInterestRow fallback = rows.get(1);
        assertThat(fallback.instId()).isEqualTo("DEFAULT-ID");
        assertThat(fallback.instType()).isEqualTo("SWAP");
        assertThat(fallback.oi()).isEqualByComparingTo("88.25");
    }

    @Test
    void parseOpenInterestIgnoresNonObjectNodes() {
        assertThat(RubikParser.parseOpenInterest(List.of(), "BTC-USDT-SWAP", "SWAP")).isEmpty();
        assertThat(RubikParser.parseOpenInterest(null, "BTC-USDT-SWAP", "SWAP")).isEmpty();
    }
}
