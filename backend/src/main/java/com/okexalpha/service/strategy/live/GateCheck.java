package com.okexalpha.service.strategy.live;

import java.util.Map;

/**
 * 一条门禁检查结论。
 *
 * @param id      规则标识（大写蛇形）
 * @param level   PASS / WARN / BLOCK
 * @param message 人类可读说明
 * @param metric  关键指标（数量、阈值等）
 */
public record GateCheck(String id, String level, String message, Map<String, Object> metric) {

    public static final String PASS = "PASS";
    public static final String WARN = "WARN";
    public static final String BLOCK = "BLOCK";

    public static GateCheck pass(String id, String message, Map<String, Object> metric) {
        return new GateCheck(id, PASS, message, metric == null ? Map.of() : metric);
    }

    public static GateCheck warn(String id, String message, Map<String, Object> metric) {
        return new GateCheck(id, WARN, message, metric == null ? Map.of() : metric);
    }

    public static GateCheck block(String id, String message, Map<String, Object> metric) {
        return new GateCheck(id, BLOCK, message, metric == null ? Map.of() : metric);
    }

    public boolean blocking() {
        return BLOCK.equals(level);
    }
}
