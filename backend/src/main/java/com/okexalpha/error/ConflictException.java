package com.okexalpha.error;

/** 冲突（如重复补拉同一区间）-> HTTP 409。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
