package com.okexalpha.client;

/** 写入 okx_api_call_log 的一条记录。 */
public record ApiCallRecord(
        String endpoint,
        String instId,
        Integer httpStatus,
        String okxCode,
        String okxMsg,
        Integer rowCount,
        long elapsedMs) {
}
