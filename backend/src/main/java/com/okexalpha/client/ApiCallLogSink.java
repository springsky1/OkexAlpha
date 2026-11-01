package com.okexalpha.client;

/** 上游调用日志出口（okx.api-log.enabled=false 时实现方可静默丢弃）。 */
public interface ApiCallLogSink {

    void log(ApiCallRecord record);
}
