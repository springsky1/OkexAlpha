package com.okexalpha.web.dto;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.okexalpha.error.BadRequestException;

/** 时间入参：优先 startMs/endMs，其次 ISO-8601 的 start/end。 */
public record TimeArgs(Long startMs, Long endMs) {

    public static TimeArgs of(Long startMs, Long endMs, String start, String end) {
        Long from = startMs != null ? startMs : parseIso(start);
        Long to = endMs != null ? endMs : parseIso(end);
        if (from != null && to != null && from >= to) {
            throw new BadRequestException("startMs must be earlier than endMs");
        }
        return new TimeArgs(from, to);
    }

    private static Long parseIso(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("invalid ISO-8601 time: " + value);
        }
    }
}
