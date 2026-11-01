package com.okexalpha.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.okexalpha.client.ApiCallLogSink;
import com.okexalpha.client.ApiCallRecord;
import com.okexalpha.config.OkxProperties;

/** 把每次上游调用写入 okx_api_call_log（okx.api-log.enabled=false 时静默丢弃）。 */
@Component
public class ApiCallLogSinkImpl implements ApiCallLogSink {

    private static final String INSERT = """
            INSERT INTO okx_api_call_log (endpoint, inst_id, http_status, okx_code, okx_msg, row_count, elapsed_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final OkxProperties properties;

    public ApiCallLogSinkImpl(JdbcTemplate jdbcTemplate, OkxProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public void log(ApiCallRecord record) {
        if (!properties.apiLog().enabled()) {
            return;
        }
        jdbcTemplate.update(INSERT,
                record.endpoint(),
                record.instId(),
                record.httpStatus(),
                record.okxCode(),
                record.okxMsg(),
                record.rowCount(),
                record.elapsedMs());
    }
}
