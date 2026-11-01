package com.okexalpha.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.okexalpha.client.OkxApiException;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.error.ConflictException;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.error.UnprocessableException;
import com.okexalpha.web.dto.ApiResponse;

/** 全局异常映射：保留 HTTP 语义，响应体统一为 {code,msg,data}。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(BadRequestException ex) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAM", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> illegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAM", ex.getMessage());
    }

    @ExceptionHandler(UnprocessableException.class)
    public ResponseEntity<ApiResponse<Void>> unprocessable(UnprocessableException ex) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARAM", ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(ConflictException ex) {
        return body(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> badParameter(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAM", ex.getMessage());
    }

    @ExceptionHandler(OkxApiException.class)
    public ResponseEntity<ApiResponse<Void>> okx(OkxApiException ex) {
        return switch (ex.kind()) {
            case RATE_LIMITED -> body(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", ex.getMessage());
            case INVALID_PARAM, RANGE_EXHAUSTED ->
                body(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARAM", ex.getMessage());
            default -> body(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", ex.getMessage());
        };
    }

    /** 静态资源/未匹配路径：保持 404 语义，不要被兜底 500 吞掉。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> noResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "no resource: /" + ex.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
        log.error("unhandled request failure", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> body(HttpStatus status, String code, String msg) {
        return ResponseEntity.status(status).body(ApiResponse.error(code, msg));
    }
}
