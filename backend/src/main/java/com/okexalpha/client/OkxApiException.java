package com.okexalpha.client;

/** OKX 业务错误或传输错误。 */
public class OkxApiException extends RuntimeException {

    public enum Kind {
        RATE_LIMITED, INVALID_PARAM, RANGE_EXHAUSTED, UPSTREAM_ERROR, NETWORK
    }

    private final Kind kind;
    private final String okxCode;
    private final int httpStatus;
    private final transient TransportResponse response;

    public OkxApiException(Kind kind, String okxCode, int httpStatus, String message, TransportResponse response) {
        super(message);
        this.kind = kind;
        this.okxCode = okxCode;
        this.httpStatus = httpStatus;
        this.response = response;
    }

    public Kind kind() {
        return kind;
    }

    public String okxCode() {
        return okxCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public TransportResponse response() {
        return response;
    }

    public boolean retryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.NETWORK || kind == Kind.UPSTREAM_ERROR;
    }

    /** 50030：请求区间已超出上游保留期。 */
    public boolean rangeExhausted() {
        return kind == Kind.RANGE_EXHAUSTED;
    }
}
