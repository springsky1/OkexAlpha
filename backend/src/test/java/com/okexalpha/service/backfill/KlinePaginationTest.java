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

/** KlineTaskHandler：after 游标向前翻页、重复 ts 时游标递减、空页收敛。 */
class KlinePaginationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long HOUR = 3_600_000L;
    private static final long END = 1_735_689_600_000L;
    private static final long START = END - 6 * HOUR;

    private OkxApiClient client;
    private KlineRepository repository;
    private KlineTaskHandler handler;
    private final List<Long> cursors = new ArrayList<>();
    private final List<Integer> pageCounts = new ArrayList<>();
    private final List<Boolean> truncationFlags = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = mock(OkxApiClient.class);
        repository = mock(KlineRepository.class);
        BackfillPageWriter pageWriter = mock(BackfillPageWriter.class);
        doAnswer(invocation -> {
            cursors.add(invocation.getArgument(2));
            pageCounts.add(invocation.getArgument(3));
            truncationFlags.add(invocation.getArgument(5));
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(pageWriter).write(anyLong(), any(Runnable.class), any(), anyInt(), anyLong(), anyBoolean());

        handler = new KlineTaskHandler(client, OkxProperties.defaults(), repository, pageWriter, MAPPER);
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
        assertThat(cursors).containsExactly(END - 2 * HOUR, END - 4 * HOUR);
        assertThat(pageCounts).containsExactly(1, 2);
        assertThat(truncationFlags).containsExactly(false, false);
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

        assertThat(calls.get()).isEqualTo(3);
        assertThat(outcome.rowsWritten()).isEqualTo(3L);
        assertThat(cursors).containsExactly(END - 1, END - 2, start);
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
    void declaresKlineDataType() {
        assertThat(handler.dataTypes()).containsExactly(DataType.KLINE);
    }
}
