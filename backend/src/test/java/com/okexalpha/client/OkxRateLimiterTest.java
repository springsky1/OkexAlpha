package com.okexalpha.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.okexalpha.support.MutableClock;

/** OkxRateLimiter：滑动窗口配额与未知 group 的兜底。 */
class OkxRateLimiterTest {

    private static final long WINDOW_MS = 2_000L;
    private static final long T0 = 1_735_689_600_000L;

    @Test
    void allowsUpToLimitWithinWindow() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_CANDLES, 2));

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();
    }

    @Test
    void reportsRemainingWaitWhenWindowIsSaturated() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_CANDLES, 1));

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();
        clock.advance(500L);

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isEqualTo(1_500L);
    }

    @Test
    void saturationDoesNotConsumeQuota() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_CANDLES, 1));

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();

        long first = limiter.reserveMs(OkxClientConfig.GROUP_CANDLES);
        long second = limiter.reserveMs(OkxClientConfig.GROUP_CANDLES);

        assertThat(first).isPositive();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void allowsAgainAfterWindowRollsOver() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_FUNDING, 1));

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_FUNDING)).isZero();
        clock.advance(WINDOW_MS);

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_FUNDING)).isZero();
    }

    @Test
    void windowsAreIndependentPerGroup() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_CANDLES, 1, OkxClientConfig.GROUP_RUBIK, 1));

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_RUBIK)).isZero();
    }

    @Test
    void unknownGroupFallsBackToSingleSlot() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_INSTRUMENTS, 5));

        assertThat(limiter.reserveMs("not-configured")).isZero();
        assertThat(limiter.reserveMs("not-configured")).isEqualTo(WINDOW_MS);
    }
}
