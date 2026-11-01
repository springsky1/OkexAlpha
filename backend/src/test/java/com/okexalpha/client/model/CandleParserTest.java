package com.okexalpha.client.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.okexalpha.domain.KlineRow;
import com.okexalpha.domain.KlineType;
import com.okexalpha.support.Fixtures;

/** CandleParser：9 列 CANDLE / 6 列 MARK / 脏数据。 */
class CandleParserTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesNineColumnCandleRows() {
        List<JsonNode> data = Fixtures.data("okx-candles-9col.json");

        List<KlineRow> rows = CandleParser.parseAll(data, KlineType.CANDLE, "BTC-USDT-SWAP", "SWAP", "1H");

        assertThat(rows).hasSize(3);
        KlineRow first = rows.get(0);
        assertThat(first.klineType()).isEqualTo("CANDLE");
        assertThat(first.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(first.instType()).isEqualTo("SWAP");
        assertThat(first.bar()).isEqualTo("1H");
        assertThat(first.tsMs()).isEqualTo(1735686000000L);
        assertThat(first.open()).isEqualByComparingTo("93450.1");
        assertThat(first.high()).isEqualByComparingTo("93600.0");
        assertThat(first.low()).isEqualByComparingTo("93300.5");
        assertThat(first.close()).isEqualByComparingTo("93550.2");
        assertThat(first.vol()).isEqualByComparingTo("120.55");
        assertThat(first.volCcy()).isEqualByComparingTo("120.55");
        assertThat(first.volCcyQuote()).isEqualByComparingTo("11278000.0");
        assertThat(first.confirm()).isZero();

        KlineRow second = rows.get(1);
        assertThat(second.tsMs()).isEqualTo(1735689600000L);
        assertThat(second.close()).isEqualByComparingTo("93510.7");
        assertThat(second.vol()).isEqualByComparingTo("98.31");
        assertThat(second.confirm()).isEqualTo(1);
    }

    @Test
    void parsesSixColumnMarkCandlesWithoutVolume() {
        List<JsonNode> data = Fixtures.data("okx-candles-index-6col.json");

        List<KlineRow> rows = CandleParser.parseAll(data, KlineType.MARK, "BTC-USDT-SWAP", "SWAP", "1H");

        assertThat(rows).hasSize(2);
        KlineRow first = rows.get(0);
        assertThat(first.klineType()).isEqualTo("MARK");
        assertThat(first.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(first.tsMs()).isEqualTo(1735686000000L);
        assertThat(first.open()).isEqualByComparingTo("93450.1");
        assertThat(first.close()).isEqualByComparingTo("93550.2");
        assertThat(first.vol()).isNull();
        assertThat(first.volCcy()).isNull();
        assertThat(first.volCcyQuote()).isNull();
        assertThat(first.confirm()).isZero();
        assertThat(rows.get(1).confirm()).isEqualTo(1);
    }

    @Test
    void parsesMarkCandlesAsSixColumns() {
        JsonNode node = MAPPER.readTree("""
                ["1735693200000","93000.0","93100.0","92900.0","93050.0","1"]
                """);

        KlineRow row = CandleParser.parse(node, KlineType.MARK, "BTC-USDT-SWAP", "SWAP", "1D");

        assertThat(row.klineType()).isEqualTo("MARK");
        assertThat(row.tsMs()).isEqualTo(1735693200000L);
        assertThat(row.close()).isEqualByComparingTo("93050.0");
        assertThat(row.vol()).isNull();
        assertThat(row.confirm()).isEqualTo(1);
    }

    @Test
    void treatsEmptyNumbersAsNull() {
        List<JsonNode> data = Fixtures.data("okx-candles-9col.json");
        KlineRow blankVolume = CandleParser.parseAll(data, KlineType.CANDLE, "BTC-USDT-SWAP", "SWAP", "1H").get(2);
        assertThat(blankVolume.vol()).isNull();
        assertThat(blankVolume.volCcy()).isNull();
        assertThat(blankVolume.volCcyQuote()).isNull();
        assertThat(blankVolume.close()).isEqualByComparingTo("93600.0");

        JsonNode allBlank = MAPPER.readTree("""
                ["1735693200000","","  ","","","","","","0"]
                """);
        KlineRow row = CandleParser.parse(allBlank, KlineType.CANDLE, "BTC-USDT-SWAP", "SWAP", "1H");
        assertThat(row.open()).isNull();
        assertThat(row.high()).isNull();
        assertThat(row.close()).isNull();
        assertThat(row.vol()).isNull();
    }

    @Test
    void rejectsCandleRowWithTooFewColumns() {
        JsonNode eightColumns = MAPPER.readTree("""
                ["1735686000000","1","2","3","4","5","6","7"]
                """);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CandleParser.parse(eightColumns, KlineType.CANDLE, "BTC-USDT-SWAP", "SWAP", "1H"))
                .withMessageContaining("needs 9 columns but got 8");
    }

    @Test
    void rejectsMarkRowWithTooFewColumns() {
        JsonNode fiveColumns = MAPPER.readTree("""
                ["1735686000000","1","2","3","4"]
                """);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CandleParser.parse(fiveColumns, KlineType.MARK, "BTC-USDT-SWAP", "SWAP", "1H"))
                .withMessageContaining("needs 6 columns but got 5");
    }

    @Test
    void rejectsNonArrayNode() {
        JsonNode textNode = MAPPER.readTree("\"oops\"");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CandleParser.parse(textNode, KlineType.CANDLE, "BTC-USDT-SWAP", "SWAP", "1H"))
                .withMessageContaining("not an array");
    }

    @Test
    void parseAllToleratesNullInput() {
        assertThat(CandleParser.parseAll(null, KlineType.CANDLE, "BTC-USDT-SWAP", "SWAP", "1H")).isEmpty();
    }

    @Test
    void parsesDecimalAsBigDecimal() {
        JsonNode node = MAPPER.readTree("""
                ["1735686000000","0.00000001","1.5","0.5","2.25","3","4","5","0"]
                """);

        KlineRow row = CandleParser.parse(node, KlineType.CANDLE, "BTC-USDT-SWAP", "SWAP", "1m");

        assertThat(row.open()).isEqualTo(new BigDecimal("0.00000001"));
        assertThat(row.high()).isEqualByComparingTo("1.5");
    }
}
