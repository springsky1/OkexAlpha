package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.BacktestResultRow;
import com.okexalpha.domain.BacktestRunRow;
import com.okexalpha.repository.BacktestRepository;
import com.okexalpha.service.strategy.BacktestSeriesLoader;

import tools.jackson.databind.ObjectMapper;

/**
 * 提升为候选策略时的推荐资金：账户权益必须覆盖回测里真正用掉的峰值保证金，并写回
 * {@code params.accountCapitalUsd}（加仓闸门的上限），否则影子会按 100U 权益去跑一个回测里加到 8 层、
 * 峰值占用 3000U 的组合——闸门一层都不放行，"回测 8 层 / 影子 1 层"对不上。
 *
 * <p>{@code initialMarginUsd} 是每笔首层预算，必须保持回测时的值：改了首层名义就和回测脱节。</p>
 */
class BacktestPromoteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BacktestRepository repository;
    private BacktestService service;

    @BeforeEach
    void setUp() {
        repository = mock(BacktestRepository.class);
        BacktestSeriesLoader loader = mock(BacktestSeriesLoader.class);
        service = new BacktestService(repository, loader, MAPPER, OkxProperties.defaults(),
                mock(org.springframework.core.task.AsyncTaskExecutor.class));
        given(repository.findRun(2L)).willReturn(run());
        given(repository.findResult(70L)).willReturn(aggregate(100d));
        given(repository.promoteStrategy(anyString(), anyString(), anyString(), anyDouble(), anyLong(), anyString()))
                .willReturn(7L);
        given(repository.findStrategy(7L)).willReturn(Map.of("id", 7L));
    }

    @Test
    @DisplayName("推荐资金 = 逐币峰值保证金 / 95%（再与批次自带权益取大），写回 params.accountCapitalUsd")
    void capitalComesFromPeakMargin() {
        given(repository.listCoinResultsByKey(2L, "COIN_KEY", 50)).willReturn(List.of(
                coin("BTC-USDT-SWAP", 52.49d, 52.49d),      // peak = 100
                coin("SOL-USDT-SWAP", 2225.75d, 69.2d)));   // peak ≈ 3216.4

        service.promote(2L, 70L, null, null);

        ArgumentCaptor<Double> capital = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<String> params = ArgumentCaptor.forClass(String.class);
        verify(repository).promoteStrategy(anyString(), params.capture(), anyString(), capital.capture(), eq(2L),
                anyString());
        // 峰值 3216.4 只能占用到权益的 95%，所以推荐权益 = 3216.4 / 0.95 ≈ 3385.68
        assertThat(capital.getValue()).isGreaterThan(3380d).isLessThan(3390d);
        assertThat(params.getValue()).contains("\"accountCapitalUsd\":3385.68");
        assertThat(params.getValue()).contains("\"initialMarginUsd\":100");
    }

    @Test
    @DisplayName("逐币行收益为负时无法反算峰值 → 权益回落到 params.initialMarginUsd")
    void fallsBackWhenPeakUnknown() {
        given(repository.listCoinResultsByKey(2L, "COIN_KEY", 50))
                .willReturn(List.of(coin("BTC-USDT-SWAP", -30d, -30d)));

        service.promote(2L, 70L, null, null);

        ArgumentCaptor<Double> capital = ArgumentCaptor.forClass(Double.class);
        verify(repository).promoteStrategy(anyString(), anyString(), anyString(), capital.capture(), eq(2L),
                anyString());
        assertThat(capital.getValue()).isEqualTo(100d);
    }

    private static BacktestResultRow aggregate(double margin) {
        String params = "{\"side\":\"long\",\"initialMarginUsd\":" + margin + ",\"leverage\":5}";
        return new BacktestResultRow(70L, 2L, "COIN_KEY", params, null, "ALL", "{}", 2, 214, 80.37d, 1322.08d, 440.69d,
                40.4d, 4.39d, 0, 535.85d, "CANDIDATE", 1);
    }

    private static BacktestResultRow coin(String instId, double netPnl, double netPnlPct) {
        return new BacktestResultRow(150L, 2L, "COIN_KEY", aggregate(100d).params(), instId, "COIN", "{}", 1, 50,
                70d, netPnl, netPnlPct, 10d, 1d, 0, null, "COIN", null);
    }

    private static BacktestRunRow run() {
        return new BacktestRunRow(2L, null, "demo", "DONE", "1m", 1785748984081L, 1793524984081L, "DONE",
                "{\"instIds\":[\"BTC-USDT-SWAP\",\"SOL-USDT-SWAP\"],\"bar\":\"1m\"}", 2, 2, 80, 80, 70L, null,
                null, null, null);
    }
}
