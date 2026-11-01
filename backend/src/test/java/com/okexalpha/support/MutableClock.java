package com.okexalpha.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 可手动推进的 Clock，用于限流窗口/时间敏感逻辑的单测。 */
public final class MutableClock extends Clock {

    private volatile long millis;

    public MutableClock(long millis) {
        this.millis = millis;
    }

    public void advance(long deltaMs) {
        this.millis = this.millis + deltaMs;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
