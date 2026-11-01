package com.okexalpha.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.okexalpha.client.OkxApiException.Kind;

/** OkxApiException 的可重试 / 区间耗尽判定。 */
class OkxApiExceptionTest {

    private static OkxApiException exception(Kind kind) {
        return new OkxApiException(kind, "50030", 400, "boom", new TransportResponse(400, "{}"));
    }

    @Test
    void retryableOnlyForTransientKinds() {
        assertThat(exception(Kind.RATE_LIMITED).retryable()).isTrue();
        assertThat(exception(Kind.NETWORK).retryable()).isTrue();
        assertThat(exception(Kind.UPSTREAM_ERROR).retryable()).isTrue();
        assertThat(exception(Kind.INVALID_PARAM).retryable()).isFalse();
        assertThat(exception(Kind.RANGE_EXHAUSTED).retryable()).isFalse();
    }

    @Test
    void rangeExhaustedOnlyForRangeKind() {
        assertThat(exception(Kind.RANGE_EXHAUSTED).rangeExhausted()).isTrue();
        assertThat(exception(Kind.RATE_LIMITED).rangeExhausted()).isFalse();
        assertThat(exception(Kind.INVALID_PARAM).rangeExhausted()).isFalse();
        assertThat(exception(Kind.UPSTREAM_ERROR).rangeExhausted()).isFalse();
        assertThat(exception(Kind.NETWORK).rangeExhausted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void rangeExhaustedMirrorsKindForEveryKind(Kind kind) {
        assertThat(exception(kind).rangeExhausted()).isEqualTo(kind == Kind.RANGE_EXHAUSTED);
    }

    @Test
    void exposesDiagnostics() {
        TransportResponse response = new TransportResponse(429, "{\"code\":\"50011\"}");
        OkxApiException ex = new OkxApiException(Kind.RATE_LIMITED, "50011", 429, "too many requests", response);

        assertThat(ex.kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(ex.okxCode()).isEqualTo("50011");
        assertThat(ex.httpStatus()).isEqualTo(429);
        assertThat(ex.response()).isSameAs(response);
        assertThat(ex).hasMessage("too many requests");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void transportFailureMarkerUsesZeroStatus() {
        TransportResponse failed = TransportResponse.failed("connection refused");

        assertThat(failed.statusCode()).isZero();
        assertThat(failed.body()).isEqualTo("connection refused");
    }
}
