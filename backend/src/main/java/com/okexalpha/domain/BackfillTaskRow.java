package com.okexalpha.domain;

import java.time.OffsetDateTime;

/** okx_backfill_task 一行。 */
public record BackfillTaskRow(
        long id,
        long jobId,
        String taskKey,
        String params,
        String status,
        Long cursorMs,
        int pageCount,
        long rowsWritten,
        int attempts,
        boolean truncated,
        String lastError,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
