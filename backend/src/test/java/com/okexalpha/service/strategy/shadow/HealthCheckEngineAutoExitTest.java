package com.okexalpha.service.strategy.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.PortfolioSnapshotRow;
import com.okexalpha.domain.ShadowPositionRow;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.security.OkxCredentials;
import com.okexalpha.strategy.engine.StrategyParams;

import tools.jackson.databind.ObjectMapper;

/**
 * 自动平仓必须逐笔精确制导：只有本轮命中 FAIL（距强平过近）的持仓才允许被平，
 * 同一轮里健康的持仓一笔都不能动。旧实现是遍历全部持仓全平（2026-11-06 01:03 事故）。
 */
class HealthCheckEngineAutoExitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShadowRepository repository;
    private ShadowEngine shadowEngine;
    private OkxProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(ShadowRepository.class);
        shadowEngine = mock(ShadowEngine.class);
        properties = withAutoExit(true);

        given(shadowEngine.activeStrategies()).willReturn(List.of());
        given(shadowEngine.strategyParams()).willReturn(Map.of());
        given(shadowEngine.lastTickAtMs()).willReturn(System.currentTimeMillis());
        given(shadowEngine.defaultParams()).willReturn(mock(StrategyParams.class));
        given(repository.latestKlineTs("1m")).willReturn(System.currentTimeMillis() - 60_000L);
        given(repository.latestPortfolio()).willReturn((PortfolioSnapshotRow) null);
        given(repository.latestFundingRate(any())).willReturn(null);
        given(repository.apiOutcomeCounts(anyInt())).willReturn(new long[] {100L, 0L});
        given(repository.countRunningBackfills()).willReturn(0L);
    }

    @Test
    @DisplayName("只平命中 FAIL 的那一笔，健康持仓不动")
    void onlyFailingPositionIsClosed() {
        ShadowPositionRow dangerous = position(1L, "BTC-USDT-SWAP", 100d, 95d);
        ShadowPositionRow healthy = position(2L, "ETH-USDT-SWAP", 100d, 60d);
        given(repository.openPositions()).willReturn(List.of(dangerous, healthy));
        given(shadowEngine.forceClose(same(dangerous), any(), eq("AUTO_EXIT"), eq(100d))).willReturn(true);

        HealthCheckEngine.CheckResult result = new HealthCheckEngine(repository, shadowEngine, properties,
                new OkxCredentials(), MAPPER).check();

        verify(shadowEngine, times(1)).forceClose(same(dangerous), any(), eq("AUTO_EXIT"), anyDouble());
        verify(shadowEngine, never()).forceClose(same(healthy), any(), any(), anyDouble());
        assertThat(result.autoExited()).isEqualTo(1);
    }

    @Test
    @DisplayName("auto-exit 关闭时即便命中 FAIL 也不平仓")
    void autoExitDisabledLeavesPositionsAlone() {
        ShadowPositionRow dangerous = position(1L, "BTC-USDT-SWAP", 100d, 95d);
        given(repository.openPositions()).willReturn(List.of(dangerous));

        HealthCheckEngine.CheckResult result = new HealthCheckEngine(repository, shadowEngine, withAutoExit(false),
                new OkxCredentials(), MAPPER).check();

        verify(shadowEngine, never()).forceClose(any(), any(), any(), anyDouble());
        assertThat(result.autoExited()).isZero();
    }

    @Test
    @DisplayName("强制平仓失败时不计入 autoExited，也不写 AUTO_EXIT 记录")
    void failedForceCloseIsNotCounted() {
        ShadowPositionRow dangerous = position(1L, "BTC-USDT-SWAP", 100d, 95d);
        given(repository.openPositions()).willReturn(List.of(dangerous));
        given(shadowEngine.forceClose(any(), any(), any(), anyDouble())).willReturn(false);

        HealthCheckEngine.CheckResult result = new HealthCheckEngine(repository, shadowEngine, properties,
                new OkxCredentials(), MAPPER).check();

        assertThat(result.autoExited()).isZero();
        verify(repository, never()).insertHealth(any(), eq("AUTO_EXIT"), any(), any(), any(), any(), any());
    }

    /** mark=100、liq 参数决定距强平百分比：liq=95 → 5%（FAIL），liq=60 → 40%（健康）。 */
    private static ShadowPositionRow position(long id, String instId, double mark, double liq) {
        return new ShadowPositionRow(id, null, instId, StrategyParams.LONG, 1d, mark, mark, 100d, 0d, 5d, liq, 20d,
                "OPEN", OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    private static OkxProperties withAutoExit(boolean autoExit) {
        OkxProperties d = OkxProperties.defaults();
        OkxProperties.Strategy s = d.strategy();
        return new OkxProperties(d.baseUrl(), d.connectTimeoutMs(), d.readTimeoutMs(), d.userAgent(), d.retry(),
                d.rateLimit(), d.backfill(), d.sync(), d.schedule(), d.apiLog(), d.db(),
                new OkxProperties.Strategy(s.enabled(), s.shadow(),
                        new OkxProperties.Strategy.Health(60_000L, autoExit, 20, 60), s.backtest(), s.trading()));
    }
}
