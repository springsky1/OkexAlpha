package com.okexalpha.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 行情新鲜度探针：okx_kline 是 3 亿行级的 hypertable，按 bar 全表 max() 一次要 33 秒，
 * 而健康检查每 60 秒问一次。这两条断言守住修复的两个要点：
 * ① SQL 必须带 ts_ms 下界（TimescaleDB 才能按 chunk 排除，只扫最近 7 天）；
 * ② 结果要缓存，健康检查与实盘门禁在同一窗口只能各查一次库。
 */
class ShadowRepositoryTest {

    private JdbcTemplate jdbc;
    private ShadowRepository repository;

    private final List<String> sqls = new ArrayList<>();
    private final List<Object[]> argSets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new ShadowRepository(jdbc);
    }

    private void answerWith(Long tsMs) {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenAnswer(invocation -> {
            sqls.add(invocation.getArgument(0));
            argSets.add(new Object[] {invocation.getArgument(2), invocation.getArgument(3)});
            return tsMs;
        });
    }

    @Test
    @DisplayName("latestKlineTs：查询带 ts_ms 下界，且 7 天窗口内只查一次库")
    void latestKlineTsBoundsScanByChunkAndCaches() {
        long before = System.currentTimeMillis();
        answerWith(1_700_000_000_000L);

        Long first = repository.latestKlineTs("1m");
        Long second = repository.latestKlineTs("1m");

        assertThat(first).isEqualTo(1_700_000_000_000L);
        assertThat(second).isEqualTo(first);
        verify(jdbc, times(1)).queryForObject(anyString(), eq(Long.class), any(), any());

        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0)).contains("ts_ms >=");
        long lowerBound = (Long) argSets.get(0)[1];
        long now = System.currentTimeMillis();
        long sevenDaysAgo = before - 7L * 24 * 3600_000L - 60_000L;
        assertThat(lowerBound).isGreaterThanOrEqualTo(sevenDaysAgo).isLessThanOrEqualTo(now);
    }

    @Test
    @DisplayName("latestKlineTs：窗口内没有 K 线时返回 null，缓存也缓存这个 null")
    void latestKlineTsCachesNull() {
        answerWith(null);

        Long first = repository.latestKlineTs("1m");
        Long second = repository.latestKlineTs("1m");

        assertThat(first).isNull();
        assertThat(second).isNull();
        verify(jdbc, times(1)).queryForObject(anyString(), eq(Long.class), any(), any());
    }

    @Test
    @DisplayName("latestKlineTs：不同 bar 各自缓存，互不串味")
    void latestKlineTsCachesPerBar() {
        answerWith(1_700_000_000_000L);

        repository.latestKlineTs("1m");
        repository.latestKlineTs("1H");

        verify(jdbc, times(2)).queryForObject(anyString(), eq(Long.class), any(), any());
    }
}
