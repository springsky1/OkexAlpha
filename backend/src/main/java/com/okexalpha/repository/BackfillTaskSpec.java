package com.okexalpha.repository;

/** Planner 产出的一个待落库任务。paramsJson 是 JSON 字符串（写入 params jsonb）。 */
public record BackfillTaskSpec(String taskKey, String paramsJson, Long startMs, Long endMs) {
}
