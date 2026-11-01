package com.okexalpha.domain;

import java.time.OffsetDateTime;

/** okx_backfill_job 一行。 */
public record BackfillJobRow(
        long id,
        String dataType,
        String request,
        String status,
        int taskTotal,
        int taskDone,
        int taskFailed,
        long rowsWritten,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
