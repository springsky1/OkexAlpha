package com.okexalpha.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.okexalpha.client.OkxApiException;
import com.okexalpha.client.TransportResponse;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.error.ConflictException;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.error.UnprocessableException;
import com.okexalpha.web.dto.ApiResponse;

/** ApiExceptionHandler：异常到 HTTP 状态 + 业务 code 的映射。 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private static void assertMapped(ResponseEntity<ApiResponse<Void>> response, HttpStatus status, String code,
            String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().msg()).isEqualTo(message);
        assertThat(response.getBody().data()).isNull();
    }

    private static OkxApiException okx(OkxApiException.Kind kind) {
        return new OkxApiException(kind, "50011", 400, "okx said no", new TransportResponse(400, "{}"));
    }

    @Test
    void badRequestBecomes400InvalidParam() {
        assertMapped(handler.badRequest(new BadRequestException("bars is required")), HttpStatus.BAD_REQUEST,
                "INVALID_PARAM", "bars is required");
    }

    @Test
    void illegalArgumentBecomes400InvalidParam() {
        assertMapped(handler.illegalArgument(new IllegalArgumentException("invalid bar: 7m")),
                HttpStatus.BAD_REQUEST, "INVALID_PARAM", "invalid bar: 7m");
    }

    @Test
    void unprocessableBecomes422() {
        assertMapped(handler.unprocessable(new UnprocessableException("too many tasks")),
                HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARAM", "too many tasks");
    }

    @Test
    void notFoundBecomes404() {
        assertMapped(handler.notFound(new NotFoundException("job 42 not found")), HttpStatus.NOT_FOUND, "NOT_FOUND",
                "job 42 not found");
    }

    @Test
    void conflictBecomes409() {
        assertMapped(handler.conflict(new ConflictException("job is still running")), HttpStatus.CONFLICT,
                "CONFLICT", "job is still running");
    }

    @Test
    void rateLimitedBecomes429() {
        assertMapped(handler.okx(okx(OkxApiException.Kind.RATE_LIMITED)), HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED", "okx said no");
    }

    @Test
    void invalidParamBecomes422() {
        assertMapped(handler.okx(okx(OkxApiException.Kind.INVALID_PARAM)), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_PARAM", "okx said no");
    }

    @Test
    void rangeExhaustedBecomes422() {
        assertMapped(handler.okx(okx(OkxApiException.Kind.RANGE_EXHAUSTED)), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_PARAM", "okx said no");
    }

    @Test
    void upstreamAndNetworkErrorsBecome502() {
        assertMapped(handler.okx(okx(OkxApiException.Kind.UPSTREAM_ERROR)), HttpStatus.BAD_GATEWAY,
                "UPSTREAM_ERROR", "okx said no");
        assertMapped(handler.okx(okx(OkxApiException.Kind.NETWORK)), HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                "okx said no");
    }

    @Test
    void unexpectedBecomes500InternalError() {
        assertMapped(handler.unexpected(new IllegalStateException("boom")), HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "boom");
    }
}
