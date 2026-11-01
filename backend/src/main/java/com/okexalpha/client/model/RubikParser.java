package com.okexalpha.client.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import com.okexalpha.domain.OpenInterestRow;
import com.okexalpha.domain.OpenInterestVolumeRow;
import com.okexalpha.domain.RatioRow;
import com.okexalpha.domain.TakerVolumeRow;

/** rubik 系列数组解析：多空比 [ts,ratio]、OI/成交量 [ts,oi,vol]、主动买卖 [ts,sellVol,buyVol]。 */
public final class RubikParser {

    private RubikParser() {
    }

    public static List<RatioRow> parseRatios(List<JsonNode> data, String ratioType, String scope,
            String scopeKind, String period) {
        List<RatioRow> rows = new ArrayList<>();
        if (data == null) {
            return rows;
        }
        for (JsonNode node : data) {
            if (!node.isArray() || node.size() < 2) {
                continue;
            }
            BigDecimal ratio = decimal(node.get(1));
            if (ratio == null) {
                continue;
            }
            rows.add(new RatioRow(ratioType, scope, period, node.get(0).asLong(), scopeKind, ratio));
        }
        return rows;
    }

    public static List<OpenInterestVolumeRow> parseOpenInterestVolume(List<JsonNode> data, String ccy,
            String period) {
        List<OpenInterestVolumeRow> rows = new ArrayList<>();
        if (data == null) {
            return rows;
        }
        for (JsonNode node : data) {
            if (!node.isArray() || node.size() < 2) {
                continue;
            }
            rows.add(new OpenInterestVolumeRow(ccy, period, node.get(0).asLong(),
                    decimal(node.get(1)), node.size() > 2 ? decimal(node.get(2)) : null));
        }
        return rows;
    }

    public static List<TakerVolumeRow> parseTakerVolume(List<JsonNode> data, String scopeKind, String scope,
            String marketType, String period) {
        List<TakerVolumeRow> rows = new ArrayList<>();
        if (data == null) {
            return rows;
        }
        for (JsonNode node : data) {
            if (!node.isArray() || node.size() < 2) {
                continue;
            }
            rows.add(new TakerVolumeRow(scopeKind, scope, marketType, period, node.get(0).asLong(),
                    decimal(node.get(1)), node.size() > 2 ? decimal(node.get(2)) : null));
        }
        return rows;
    }

    /** 持仓量快照对象 {instId,instType,oi,oiCcy,oiUsd,ts}。 */
    public static List<OpenInterestRow> parseOpenInterest(List<JsonNode> data, String defaultInstId,
            String defaultInstType) {
        List<OpenInterestRow> rows = new ArrayList<>();
        if (data == null) {
            return rows;
        }
        for (JsonNode node : data) {
            if (!node.isObject()) {
                continue;
            }
            String instId = text(node, "instId") != null ? text(node, "instId") : defaultInstId;
            String instType = text(node, "instType") != null ? text(node, "instType") : defaultInstType;
            Long ts = longOrNull(node.get("ts"));
            if (instId == null || ts == null) {
                continue;
            }
            rows.add(new OpenInterestRow(instId, instType, ts, decimal(node.get("oi")),
                    decimal(node.get("oiCcy")), decimal(node.get("oiUsd"))));
        }
        return rows;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text;
    }

    private static Long longOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asString();
        if (text == null || text.isBlank()) {
            return null;
        }
        return new BigDecimal(text.trim());
    }
}
