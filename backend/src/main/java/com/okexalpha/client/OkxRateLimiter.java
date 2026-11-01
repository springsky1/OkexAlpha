package com.okexalpha.client;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按端点组的滑动窗口限流。时钟可注入，便于单测。
 */
public class OkxRateLimiter {

    private final Clock clock;
    private final long windowMs;
    private final Map<String, Integer> limits;
    private final Map<String, Deque<Long>> history = new ConcurrentHashMap<>();

    public OkxRateLimiter(Clock clock, long windowMs, Map<String, Integer> limits) {
        this.clock = clock;
        this.windowMs = windowMs;
        this.limits = Map.copyOf(limits);
    }

    /**
     * 尝试占用一次配额。
     *
     * @return 0 表示可立即执行；>0 表示需要等待的毫秒数（未占配额）
     */
    public synchronized long reserveMs(String group) {
        Deque<Long> hits = history.computeIfAbsent(group, key -> new ArrayDeque<>());
        long now = clock.millis();
        while (!hits.isEmpty() && now - hits.peekFirst() >= windowMs) {
            hits.pollFirst();
        }
        int limit = limits.getOrDefault(group, 1);
        if (hits.size() < limit) {
            hits.addLast(now);
            return 0L;
        }
        long oldest = hits.peekFirst();
        return Math.max(1L, windowMs - (now - oldest));
    }

    /** 阻塞直到拿到配额。 */
    public void acquire(String group) {
        while (true) {
            long waitMs = reserveMs(group);
            if (waitMs == 0L) {
                return;
            }
            try {
                Thread.sleep(Math.min(waitMs, 2_000L));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for okx rate limit", ex);
            }
        }
    }
}
