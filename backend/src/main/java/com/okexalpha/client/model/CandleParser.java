package com.okexalpha.client.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import com.okexalpha.domain.KlineRow;
import com.okexalpha.domain.KlineType;

/**
 * K 线数组解析。CANDLE 9 列：ts,o,h,l,c,vol,volCcy,volCcyQuote,confirm；MARK 6 列：ts,o,h,l,c,confirm。
 */
public final class CandleParser {

    private CandleParser() {
    }

    public static KlineRow parse(JsonNode node, KlineType klineType, String instId, String instType, String bar) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("candle element is not an array: " + node);
        }
        int required = klineType.hasVolume() ? 9 : 6;
        if (node.size() < required) {
            throw new IllegalArgumentException("candle element needs " + required + " columns but got " + node.size());
        }
        long ts = node.get(0).asLong();
        BigDecimal open = decimal(node.get(1));
        BigDecimal high = decimal(node.get(2));
        BigDecimal low = decimal(node.get(3));
        BigDecimal close = decimal(node.get(4));
        BigDecimal vol = null;
        BigDecimal volCcy = null;
        BigDecimal volCcyQuote = null;
        int confirmIndex = 5;
        if (klineType.hasVolume()) {
            vol = decimal(node.get(5));
            volCcy = decimal(node.get(6));
            volCcyQuote = decimal(node.get(7));
            confirmIndex = 8;
        }
        int confirm = node.get(confirmIndex).asInt(0);
        return new KlineRow(klineType.name(), instId, bar, ts, instType,
                open, high, low, close, vol, volCcy, volCcyQuote, confirm);
    }

    public static List<KlineRow> parseAll(List<JsonNode> data, KlineType klineType, String instId,
            String instType, String bar) {
        List<KlineRow> rows = new ArrayList<>();
        if (data == null) {
            return rows;
        }
        for (JsonNode node : data) {
            rows.add(parse(node, klineType, instId, instType, bar));
        }
        return rows;
    }

    /** OKX 偶发空字符串，按 null 处理。 */
    static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return new BigDecimal(text.trim());
    }
}
