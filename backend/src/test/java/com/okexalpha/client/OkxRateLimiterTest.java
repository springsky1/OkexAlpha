package com.okexalpha.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
    void multiGroupReserveIsAtomicWhenAGroupIsSaturated() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_CANDLES, 1, OkxClientConfig.GROUP_GLOBAL, 5));

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();
        clock.advance(500L);

        long wait = limiter.reserveMs(List.of(OkxClientConfig.GROUP_CANDLES, OkxClientConfig.GROUP_GLOBAL));

        assertThat(wait).isEqualTo(1_500L);
        // 组合占用失败时全局窗口一个配额都不能被消费：单独取 global 仍然放行
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_GLOBAL)).isZero();
        // candles 也仍然饱和，等待时间不受刚才那次失败尝试影响
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isEqualTo(1_500L);
    }

    @Test
    void globalWindowBlocksEvenWhenTheGroupStillHasRoom() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_CANDLES, 5, OkxClientConfig.GROUP_GLOBAL, 1));

        assertThat(limiter.reserveMs(List.of(OkxClientConfig.GROUP_CANDLES, OkxClientConfig.GROUP_GLOBAL))).isZero();
        clock.advance(200L);

        assertThat(limiter.reserveMs(List.of(OkxClientConfig.GROUP_CANDLES, OkxClientConfig.GROUP_GLOBAL)))
                .isEqualTo(1_800L);
        // 端点组自己还有 4 个余量，说明阻塞纯粹来自全局窗口
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();
    }

    @Test
    void groupsDoNotInterfereWhenBothStayUnderLimit() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_CANDLES, 2, OkxClientConfig.GROUP_RUBIK, 2));

        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_RUBIK)).isZero();
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_RUBIK)).isZero();

        // 两个组各自独立饱和，互不计数
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isEqualTo(WINDOW_MS);
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_RUBIK)).isEqualTo(WINDOW_MS);
    }

    @Test
    void globalWindowCountsRequestsFromEveryGroup() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS, Map.of(
                OkxClientConfig.GROUP_CANDLES, 2, OkxClientConfig.GROUP_FUNDING, 2, OkxClientConfig.GROUP_GLOBAL, 2));

        assertThat(limiter.reserveMs(List.of(OkxClientConfig.GROUP_CANDLES, OkxClientConfig.GROUP_GLOBAL))).isZero();
        assertThat(limiter.reserveMs(List.of(OkxClientConfig.GROUP_FUNDING, OkxClientConfig.GROUP_GLOBAL))).isZero();

        // 两个端点组都还有余量，但全局 2 个配额已被上面两次请求用掉
        assertThat(limiter.reserveMs(List.of(OkxClientConfig.GROUP_CANDLES, OkxClientConfig.GROUP_GLOBAL)))
                .isEqualTo(WINDOW_MS);
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_CANDLES)).isZero();
    }

    @Test
    void acquireReturnsImmediatelyWhileQuotaIsAvailable() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS,
                Map.of(OkxClientConfig.GROUP_FUNDING, 3, OkxClientConfig.GROUP_GLOBAL, 3));

        limiter.acquire(List.of(OkxClientConfig.GROUP_FUNDING, OkxClientConfig.GROUP_GLOBAL));
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_FUNDING)).isZero();
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_GLOBAL)).isZero();

        // globalGroup 为 null 时只消费端点组，不碰全局窗口
        limiter.acquire(OkxClientConfig.GROUP_FUNDING, null);
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_FUNDING)).isEqualTo(WINDOW_MS);
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_GLOBAL)).isZero();

        // 空组列表直接返回，不占任何配额
        limiter.acquire(List.of());
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_FUNDING)).isEqualTo(WINDOW_MS);
    }

    @Test
    void acquireDeduplicatesWhenGroupAndGlobalAreTheSame() {
        MutableClock clock = new MutableClock(T0);
        OkxRateLimiter limiter = new OkxRateLimiter(clock, WINDOW_MS, Map.of(OkxClientConfig.GROUP_GLOBAL, 2));

        limiter.acquire(OkxClientConfig.GROUP_GLOBAL, OkxClientConfig.GROUP_GLOBAL);

        // 同一个窗口只算一次，2 个配额还剩 1 个
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_GLOBAL)).isZero();
        assertThat(limiter.reserveMs(OkxClientConfig.GROUP_GLOBAL)).isEqualTo(WINDOW_MS);
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
