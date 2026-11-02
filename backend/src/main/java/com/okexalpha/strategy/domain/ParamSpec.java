package com.okexalpha.strategy.domain;

import java.util.List;

/**
 * 一个交易/账户接口参数的白名单条目（P0 参数契约）。
 *
 * @param name     参数名（与官方文档一致，大小写敏感）
 * @param location 位置：QUERY / BODY / HEADER
 * @param required 是否必填（条件必填见 note）
 * @param type     string / number / boolean / array / enum
 * @param allowed  type=enum 时的合法取值
 * @param anchor   官方文档锚点或章节标题（便于人工复核）
 * @param note     条件必填、互斥、已废弃等说明
 */
public record ParamSpec(
        String name,
        String location,
        boolean required,
        String type,
        List<String> allowed,
        String anchor,
        String note) {

    public static final String QUERY = "QUERY";
    public static final String BODY = "BODY";
    public static final String HEADER = "HEADER";

    public static ParamSpec of(String name, String location, boolean required, String type) {
        return new ParamSpec(name, location, required, type, List.of(), null, null);
    }

    public static ParamSpec enumOf(String name, String location, boolean required, List<String> allowed, String note) {
        return new ParamSpec(name, location, required, "enum", allowed, null, note);
    }

    public boolean isEnum() {
        return "enum".equals(type) && !allowed.isEmpty();
    }

    public boolean accepts(String value) {
        if (!isEnum() || value == null) {
            return true;
        }
        return allowed.contains(value);
    }
}
