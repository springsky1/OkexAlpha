package com.okexalpha.client;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按端点组的滑动窗口限流，可选再叠一层全局限流窗口。时钟可注入，便于单测。
 *
 * <p>一次请求可以同时占用多个窗口（端点组 + 全局组）：只有当所有窗口都有余量时才消费配额，
 * 否则一个都不消费，返回最长的等待时间。这样多线程并发时既不会漏过全局限速，
 * 也不会出现"端点配额占到了但全局没占到"的白等。
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
        return reserveMs(List.of(group));
    }

    /**
     * 原子占用多个窗口的配额。
     *
     * @return 0 表示全部窗口都还有余量并已消费；>0 表示需要等待的毫秒数（一个窗口都没消费）
     */
    public synchronized long reserveMs(List<String> groups) {
        long now = clock.millis();
        long wait = 0L;
        for (String group : groups) {
            Deque<Long> hits = history.computeIfAbsent(group, key -> new ArrayDeque<>());
            prune(hits, now);
            int limit = limits.getOrDefault(group, 1);
            if (hits.size() >= limit) {
                long oldest = hits.peekFirst();
                wait = Math.max(wait, Math.max(1L, windowMs - (now - oldest)));
            }
        }
        if (wait > 0L) {
            return wait;
        }
        for (String group : groups) {
            history.get(group).addLast(now);
        }
        return 0L;
    }

    /** 阻塞直到拿到某个端点组的配额。 */
    public void acquire(String group) {
        acquire(List.of(group));
    }

    /** 阻塞直到同时拿到端点组与全局组的配额。 */
    public void acquire(String group, String globalGroup) {
        List<String> groups = new ArrayList<>(2);
        groups.add(group);
        if (globalGroup != null && !globalGroup.equals(group)) {
            groups.add(globalGroup);
        }
        acquire(groups);
    }

    /** 阻塞直到拿到所有给定窗口的配额（空列表直接返回）。 */
    public void acquire(List<String> groups) {
        if (groups.isEmpty()) {
            return;
        }
        while (true) {
            long waitMs = reserveMs(groups);
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

    private void prune(Deque<Long> hits, long now) {
        while (!hits.isEmpty() && now - hits.peekFirst() >= windowMs) {
            hits.pollFirst();
        }
    }
}
