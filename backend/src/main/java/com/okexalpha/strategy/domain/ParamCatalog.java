package com.okexalpha.strategy.domain;

import java.util.List;

/**
 * 一个接口的完整参数契约（官方文档核对结果）。
 *
 * @param endpoint 接口路径（如 /api/v5/trade/order）
 * @param method   HTTP 方法
 * @param docUrl   官方文档地址
 * @param checkedAt 核对日期（ISO-8601，缺省表示未完成核对）
 * @param params   业务参数白名单
 * @param headers  必须/可选请求头白名单（含签名头）
 * @param notes    取值域与踩坑补充
 */
public record ParamCatalog(
        String endpoint,
        String method,
        String docUrl,
        String checkedAt,
        List<ParamSpec> params,
        List<ParamSpec> headers,
        List<String> notes) {

    public ParamCatalog {
        params = params == null ? List.of() : List.copyOf(params);
        headers = headers == null ? List.of() : List.copyOf(headers);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
