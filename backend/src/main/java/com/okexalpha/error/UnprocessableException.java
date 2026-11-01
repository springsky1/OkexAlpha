package com.okexalpha.error;

/** 语义校验失败（如任务数超上限）-> HTTP 422。 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
