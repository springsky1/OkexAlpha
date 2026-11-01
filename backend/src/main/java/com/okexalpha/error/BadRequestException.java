package com.okexalpha.error;

/** 参数不合法 -> HTTP 400 / code=INVALID_PARAM（无法映射为 422 之外的校验失败时抛它）。 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
