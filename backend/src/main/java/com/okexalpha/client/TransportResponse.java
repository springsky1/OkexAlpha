package com.okexalpha.client;

/** 一次 HTTP 调用的原始结果。statusCode=0 表示连接层失败。 */
public record TransportResponse(int statusCode, String body) {

    public static TransportResponse failed(String message) {
        return new TransportResponse(0, message);
    }
}
