package com.okexalpha.service.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.okexalpha.client.OkxApiClient;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.KlineType;
import com.okexalpha.repository.KlineRepository;
import com.okexalpha.support.BackfillControls;

/**
 * KlineTaskHandler：after 游标向前翻页、重复 ts 时游标递减、空页收敛；
 * 以及按库内覆盖率只拉缺失段（段顶 + 1、从新到旧、有缺口时保守整段）。
 */
class KlinePaginationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long HOUR = 3_600_000L;
    private static final long END = 1_735_689_600_000L;
    private static final long START = END - 6 * HOUR;

    private OkxApiClient client;
    private KlineRepository repository;
    private KlineTaskHandler handler;
    private BackfillPageWriter pageWriter;
    private final List<Long> cursors = new ArrayList<>();
    private final List<Long> afters = new ArrayList<>();
    private final List<Integer> pageCounts = new ArrayList<>();
    private final List<Boolean> truncationFlags = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = mock(OkxApiClient.class);
        repository = mock(KlineRepository.class);
        pageWriter = mock(BackfillPageWriter.class);
        doAnswer(invocation -> {
            cursors.add(invocation.getArgument(2));
            pageCounts.add(invocation.getArgument(3));
            truncationFlags.add(invocation.getArgument(5));
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(pageWriter).write(anyLong(), any(Runnable.class), any(), anyInt(), anyLong(), anyBoolean());
        // 默认库内没数据 → 覆盖率规划退化成整段，与优化前行为一致
        when(repository.coverage(anyString(), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(new KlineRepository.Coverage(null, null, 0L));

        OkxProperties properties = OkxProperties.defaults();
        handler = new KlineTaskHandler(client, properties, repository, new PageBatcher(pageWriter, properties), MAPPER);
    }

    /** 用指定 backfill 配置造一个 handler（复用同一批 mock）。 */
    private KlineTaskHandler handlerWith(OkxProperties.Backfill backfill) {
        OkxProperties base = OkxProperties.defaults();
        OkxProperties properties = new OkxProperties(base.baseUrl(), base.connectTimeoutMs(), base.readTimeoutMs(),
                base.userAgent(), base.retry(), base.rateLimit(), backfill, base.sync(), base.schedule(),
                base.apiLog(), base.db(), base.strategy());
        return new KlineTaskHandler(client, properties, repository, new PageBatcher(pageWriter, properties), MAPPER);
    }

    private static JsonNode candle(long tsMs) {
        return MAPPER.readTree("""
                [%d,"100.0","101.0","99.0","100.5","10","10","1005","1"]
                """.formatted(tsMs));
    }

    private BackfillTaskRow task(long startMs, long endMs, Integer pageLimit) {
        TaskParams params = new TaskParams(DataType.KLINE.name(), "BTC-USDT-SWAP", "SWAP", "1H",
                KlineType.CANDLE.name(), null, null, null, null, null, null, startMs, endMs, pageLimit);
        return BackfillControls.task(7L, 11L, "KLINE:CANDLE:BTC-USDT-SWAP:1H", MAPPER.writeValueAsString(params));
    }

    private void stubPages(List<JsonNode>... pages) {
        AtomicInteger call = new AtomicInteger();
        when(client.getCandles(any(KlineType.class), anyString(), anyString(), any(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    afters.add(invocation.getArgument(3));
                    int index = call.getAndIncrement();
                    return index < pages.length ? pages[index] : List.<JsonNode>of();
                });
    }

    @Test
    void walksTwoPagesThenStopsOnEmptyPage() {
        stubPages(List.of(candle(END - HOUR), candle(END - 2 * HOUR)),
                List.of(candle(END - 3 * HOUR), candle(END - 4 * HOUR)));

        TaskOutcome outcome = handler.execute(task(START, END, null), BackfillControls.running(11L));

        assertThat(outcome.rowsWritten()).isEqualTo(4L);
        assertThat(outcome.truncated()).isFalse();
        assertThat(outcome.note()).isNull();
        assertThat(cursors).containsExactly(END - 4 * HOUR);
        assertThat(pageCounts).containsExactly(2);
        assertThat(truncationFlags).containsExactly(false);
        verify(repository, times(2)).upsertAll(anyList());
    }

    @Test
    void repeatedTimestampsDecrementCursorInsteadOfLoopingForever() {
        long start = END - 3;
        AtomicInteger calls = new AtomicInteger();
        when(client.getCandles(any(KlineType.class), anyString(), anyString(), any(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    return List.of(candle(END));
                });

        TaskOutcome outcome = handler.execute(task(start, END, null), BackfillControls.running(11L));

        // 段顶游标是 toMs + 1（OKX 的 after 是排他上界，见 KlineTaskHandler），
        // 起始游标比段顶高 1ms，所以要多翻一页才收敛到 start。
        assertThat(calls.get()).isEqualTo(4);
        assertThat(outcome.rowsWritten()).isEqualTo(4L);
        assertThat(cursors).containsExactly(start);
        assertThat(pageCounts).containsExactly(4);
    }

    @Test
    void emptyFirstPageEndsImmediately() {
        stubPages();

        TaskOutcome outcome = handler.execute(task(START, END, null), BackfillControls.running(11L));

        assertThat(outcome.rowsWritten()).isZero();
        assertThat(outcome.truncated()).isFalse();
        assertThat(cursors).isEmpty();
        verify(repository, never()).upsertAll(anyList());
    }

    @Test
    void rowsOutsideRequestedRangeAreDiscarded() {
        stubPages(List.of(candle(END + HOUR), candle(START - HOUR)));

        TaskOutcome outcome = handler.execute(task(START, END, null), BackfillControls.running(11L));

        assertThat(outcome.rowsWritten()).isZero();
        verify(repository, never()).upsertAll(anyList());
    }

    @Test
    void pausedJobStopsBeforeCallingUpstream() {
        stubPages(List.of(candle(END - HOUR)));

        TaskOutcome outcome = handler.execute(task(START, END, null), BackfillControls.paused(11L));

        assertThat(outcome.note()).isEqualTo("stopped");
        assertThat(cursors).isEmpty();
        verify(client, never()).getCandles(any(), anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    void pageLimitIsClampedToThreeHundred() {
        stubPages();
        when(client.getCandles(eq(KlineType.CANDLE), eq("BTC-USDT-SWAP"), eq("1H"), any(), isNull(), eq(300)))
                .thenReturn(List.of());

        handler.execute(task(START, END, 1000), BackfillControls.running(11L));

        verify(client).getCandles(eq(KlineType.CANDLE), eq("BTC-USDT-SWAP"), eq("1H"), any(), isNull(), eq(300));
    }

    @Test
    void commitsEveryFivePagesInOneTransaction() {
        long start = END - 10 * HOUR;
        stubPages(List.of(candle(END - HOUR)),
                List.of(candle(END - 2 * HOUR)),
                List.of(candle(END - 3 * HOUR)),
                List.of(candle(END - 4 * HOUR)),
                List.of(candle(END - 5 * HOUR)),
                List.of(candle(END - 6 * HOUR)));

        TaskOutcome outcome = handler.execute(task(start, END, null), BackfillControls.running(11L));

        assertThat(outcome.rowsWritten()).isEqualTo(6L);
        assertThat(cursors).containsExactly(END - 5 * HOUR, END - 6 * HOUR);
        assertThat(pageCounts).containsExactly(5, 6);
        verify(repository, times(6)).upsertAll(anyList());
    }

    @Test
    void coverageFullyCoveredSkipsUpstream() {
        // 库内 START..END 每根都有，窗口末端又早已确认 → 一次上游请求都不发
        when(repository.coverage(anyString(), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(new KlineRepository.Coverage(START, END, 7L));

        TaskOutcome outcome = handler.execute(task(START, END, null), BackfillControls.running(11L));

        assertThat(outcome.rowsWritten()).isZero();
        assertThat(outcome.note()).isNull();
        assertThat(cursors).isEmpty();
        verify(client, never()).getCandles(any(), anyString(), anyString(), any(), any(), anyInt());
        verify(repository, never()).upsertAll(anyList());
    }

    @Test
    void coverageMissingEdgesFetchesOnlyThoseSegments() {
        // 库内 [START+2h, END-2h] 连续五根：只缺左右两段，且按从新到旧拉
        when(repository.coverage(anyString(), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(new KlineRepository.Coverage(START + 2 * HOUR, END - 2 * HOUR, 5L));
        stubPages(List.of(candle(END), candle(END - HOUR)), List.of(candle(START + HOUR), candle(START)));

        TaskOutcome outcome = handler.execute(task(START, END, null), BackfillControls.running(11L));

        assertThat(outcome.rowsWritten()).isEqualTo(4L);
        // 两段各自从「段顶 + 1」起翻（左缘段顶 = START + 1h），游标不串段
        assertThat(afters).containsExactly(END + 1, START + HOUR + 1);
        assertThat(cursors).containsExactly(START);
        verify(repository, times(2)).upsertAll(anyList());
    }

    @Test
    void coverageInteriorGapFallsBackToFullRange() {
        // 中间缺一根 → 保守整段重拉，绝不跳过缺口
        when(repository.coverage(anyString(), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(new KlineRepository.Coverage(START, END, 6L));
        stubPages(List.of(candle(END - HOUR)));

        TaskOutcome outcome = handler.execute(task(START, END, null), BackfillControls.running(11L));

        assertThat(outcome.rowsWritten()).isEqualTo(1L);
        assertThat(afters).containsExactly(END + 1, END - HOUR);
    }

    @Test
    void coverageDisabledKeepsLegacySinglePass() {
        KlineTaskHandler legacy = handlerWith(new OkxProperties.Backfill(8, 64, 300, true, 5000, false, 0L));
        // 即使库内已全覆盖，关掉 coverage-skip 后仍从 endMs 整段重拉（legacy 语义）
        stubPages(List.of(candle(END - HOUR), candle(END - 2 * HOUR)));

        TaskOutcome outcome = legacy.execute(task(START, END, null), BackfillControls.running(11L));

        assertThat(outcome.rowsWritten()).isEqualTo(2L);
        assertThat(afters).containsExactly(END, END - 2 * HOUR);
        verify(repository, never()).coverage(anyString(), anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void segmentTopBarIsFetched() {
        // 只缺右缘 [END, END]：段顶那一根必须取到。OKX 的 after 是排他上界（返回 ts < after），
        // 所以游标必须取「段顶 + 1」；用 after = 段顶会让这一根永远取不到、每次都留下同一个缺口。
        when(repository.coverage(anyString(), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(new KlineRepository.Coverage(START, END - HOUR, 7L));
        stubPages(List.of(candle(END)));

        TaskOutcome outcome = handler.execute(task(START, END, null), BackfillControls.running(11L));

        assertThat(outcome.rowsWritten()).isEqualTo(1L);
        assertThat(afters).containsExactly(END + 1);
    }

    @Test
    void declaresKlineDataType() {
        assertThat(handler.dataTypes()).containsExactly(DataType.KLINE);
    }
}
