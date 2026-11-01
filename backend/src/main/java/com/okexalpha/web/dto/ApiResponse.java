package com.okexalpha.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 统一响应体：{code,msg,data}，成功 code="0"。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(String code, String msg, T data) {

    public static final String OK = "0";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(OK, "", data);
    }

    public static <T> ApiResponse<T> error(String code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
