package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.repository.BacktestRepository;
import com.okexalpha.strategy.engine.PriceBar;
import com.okexalpha.web.dto.BacktestRequest;

import tools.jackson.databind.ObjectMapper;

/**
 * 批次落库不该有行数上限：以前 {@code MAX_RESULT_ROWS = 300} 把 2000 组粗筛结果砍到 300 行，
 * 前端又把 combos_done 当进度条，于是所有大批次都"卡"在 300/2000。
 *
 * <p>这里用内联执行器把 submit 跑完，断言<b>每一组排名结果都落了库</b>，且进度写的是真实评估数。</p>
 */
class BacktestAllResultsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long END_MS = 1_800_000_000_000L;
    private static final long START_MS = END_MS - 90L * 86_400_000L;

    private BacktestRepository repository;
    private BacktestSeriesLoader loader;
    private BacktestService service;

    @BeforeEach
    void setUp() {
        repository = mock(BacktestRepository.class);
        loader = mock(BacktestSeriesLoader.class);
        // 内联执行器：submit 里的 run() 和粗筛的 CompletableFuture 都在当前线程跑完
        AsyncTaskExecutor inline = mock(AsyncTaskExecutor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(inline).execute(any(Runnable.class));
        service = new BacktestService(repository, loader, MAPPER, OkxProperties.defaults(), inline);
        given(repository.countActive()).willReturn(0);
        given(repository.createRun(any(), any(), anyString(), anyLong(), anyLong(), anyString(), anyInt(), anyInt()))
                .willReturn(1L);
        given(repository.statusOf(1L)).willReturn("RUNNING");
        given(loader.load(anyString(), anyString(), anyLong(), anyLong())).willReturn(bars());
    }

    @Test
    @DisplayName("350 组参数 → 350 行聚合结果全部落库，combos_done 也是 350")
    void persistsEveryAggregateRow() {
        service.submit(request(350));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BacktestRepository.ResultInsert>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).insertResults(anyLong(), rows.capture());
        List<BacktestRepository.ResultInsert> written = rows.getValue();
        assertThat(written).hasSize(350);
        assertThat(written.get(0).paramKey()).isNotBlank();
        // 排名连续 1..350，且没有重复名次
        assertThat(written.stream().map(BacktestRepository.ResultInsert::rank).distinct()).hasSize(350);

        // 粗筛期间每跑完一个币回报一次（COARSE），收尾再写一次 REFINE；最后一次必须是 350
        ArgumentCaptor<String> phase = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> done = ArgumentCaptor.forClass(Integer.class);
        verify(repository, atLeastOnce()).updateProgress(anyLong(), phase.capture(), anyInt(), done.capture());
        assertThat(done.getAllValues()).contains(350);
        assertThat(done.getValue()).isEqualTo(350);
        assertThat(phase.getValue()).isEqualTo("REFINE");
        assertThat(phase.getAllValues()).contains("COARSE");
    }

    private static BacktestRequest request(int combos) {
        return new BacktestRequest(List.of("BTC-USDT-SWAP"), "1m", START_MS, END_MS, null, combos, 1, 100d, 800d,
                48, 0.01d, 1L, "all", null, null);
    }

    /** 30 根等距 1m K 线：样本内/样本外都够跑，价格来回震荡以产生成交。 */
    private static List<PriceBar> bars() {
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double px = 100d + (i % 6) * 0.8d;
            bars.add(new PriceBar(START_MS + i * 86_400_000L, px, px * 1.01d, px * 0.99d, px));
        }
        return bars;
    }
}
