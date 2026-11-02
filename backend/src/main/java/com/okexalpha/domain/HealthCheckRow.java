package com.okexalpha.domain;

import java.time.OffsetDateTime;

/** 健康度体检结论（okx_health_check 一行，每条规则每次一轮写一行）。 */
public record HealthCheckRow(
        long id,
        OffsetDateTime checkedAt,
        String rule,
        String level,
        String scope,
        String instId,
        String message,
        String metric) {
}
