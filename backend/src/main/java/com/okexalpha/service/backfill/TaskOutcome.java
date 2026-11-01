package com.okexalpha.service.backfill;

/** 单个 task 的执行结果。 */
public record TaskOutcome(long rowsWritten, boolean truncated, String note) {
}
