package com.okexalpha.service.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.okexalpha.client.OkxApiClient;
import com.okexalpha.client.OkxApiException;
import com.okexalpha.client.TransportResponse;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.Period;
import com.okexalpha.domain.RatioType;
import com.okexalpha.repository.StatisticsRepository;
import com.okexalpha.support.BackfillControls;

/** StatisticsTaskHandler：保留期裁剪 + 固定窗口切分 + RANGE_EXHAUSTED 跳过。 */
class StatisticsWindowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    private OkxApiClient client;
    private StatisticsRepository repository;
    private StatisticsTaskHandler handler;
    private final List<Long> cursors = new ArrayList<>();
    private final List<Integer> pageCounts = new ArrayList<>();
    private final List<Boolean> truncationFlags = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = mock(OkxApiClient.class);
        repository = mock(StatisticsRepository.class);
        BackfillPageWriter pageWriter = mock(BackfillPageWriter.class);
        doAnswer(invocation -> {
            cursors.add(invocation.getArgument(2));
            pageCounts.add(invocation.getArgument(3));
            truncationFlags.add(invocation.getArgument(5));
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(pageWriter).write(anyLong(), any(Runnable.class), any(), anyInt(), anyLong(), anyBoolean());
        handler = new StatisticsTaskHandler(client, repository, pageWriter, MAPPER);
    }

    private static List<JsonNode> ratios(long... tsValues) {
        List<JsonNode> rows = new ArrayList<>();
        for (long ts : tsValues) {
            rows.add(MAPPER.readTree("""
                    [%d,"1.25"]
                    """.formatted(ts)));
        }
        return rows;
    }

    private static BackfillTaskRow ratioTask(String period, String ratioType, long startMs, long endMs,
            long cursorMs, int pageCount, long rowsWritten) {
        TaskParams params = new TaskParams(DataType.LONG_SHORT_RATIO.name(), null, null, null, null, ratioType,
                "BTC", RatioType.ScopeKind.CCY.name(), null, period, null, startMs, endMs, null);
        return new BackfillTaskRow(5L, 6L, "RATIO:" + ratioType + ":BTC:" + period,
                MAPPER.writeValueAsString(params), "RUNNING", cursorMs, pageCount, rowsWritten, 1, false, null,
                null, null);
    }

    private static BackfillTaskRow ratioTask(String period, String ratioType, long startMs, long endMs) {
        TaskParams params = new TaskParams(DataType.LONG_SHORT_RATIO.name(), null, null, null, null, ratioType,
                "BTC", RatioType.ScopeKind.CCY.name(), null, period, null, startMs, endMs, null);
        return BackfillControls.task(5L, 6L, "RATIO:" + ratioType + ":BTC:" + period,
                MAPPER.writeValueAsString(params));
    }

    @Test
    void fiveMinuteWindowsClipToFortyEightHourRetention() {
        long now = System.currentTimeMillis();
        long longAgo = now - 60 * DAY;
        when(client.getLongShortRatio(any(), anyString(), any(), anyLong(), anyLong()))
                .thenReturn(ratios(now - HOUR, now - 2 * HOUR));

        TaskOutcome outcome = handler.execute(ratioTask("5m", "LSR_ACCOUNT_CCY", longAgo, now),
                BackfillControls.running(6L));

        assertThat(cursors).hasSize(6);
        assertThat(truncationFlags).allMatch(Boolean::booleanValue);
        assertThat(outcome.truncated()).isTrue();
        assertThat(outcome.rowsWritten()).isEqualTo(12L);
        assertThat(cursors.get(0)).isCloseTo(now - 48 * HOUR + 8 * HOUR, within(5 * 60_000L));
    }

    @Test
    void hourlyWindowsClipToThirtyDayRetention() {
        long now = System.currentTimeMillis();
        when(client.getLongShortRatio(any(), anyString(), eq(Period.H1), anyLong(), anyLong()))
                .thenReturn(List.of());

        TaskOutcome outcome = handler.execute(ratioTask("1H", "LSR_ACCOUNT_CCY", now - 400 * DAY, now),
                BackfillControls.running(6L));

        assertThat(cursors).hasSize(8);
        assertThat(outcome.truncated()).isTrue();
        assertThat(truncationFlags).allMatch(Boolean::booleanValue);
    }

    @Test
    void dailyWindowsClipToHundredEightyDayRetention() {
        long now = System.currentTimeMillis();
        when(client.getLongShortRatio(any(), anyString(), eq(Period.D1), anyLong(), anyLong()))
                .thenReturn(List.of());

        TaskOutcome outcome = handler.execute(ratioTask("1D", "LSR_ACCOUNT_CCY", now - 400 * DAY, now),
                BackfillControls.running(6L));

        assertThat(cursors).hasSize(2);
        assertThat(outcome.truncated()).isTrue();
    }

    @Test
    void windowsAreSlicedAtConfiguredWidth() {
        long now = System.currentTimeMillis();
        when(client.getLongShortRatio(any(), anyString(), any(), anyLong(), anyLong())).thenReturn(List.of());

        handler.execute(ratioTask("5m", "LSR_ACCOUNT_CCY", now - 60 * DAY, now), BackfillControls.running(6L));

        ArgumentCaptor<Long> begin = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> end = ArgumentCaptor.forClass(Long.class);
        verify(client, times(6)).getLongShortRatio(eq(RatioType.LSR_ACCOUNT_CCY), eq("BTC"), eq(Period.M5),
                begin.capture(), end.capture());

        for (int i = 0; i < 6; i++) {
            assertThat(end.getAllValues().get(i) - begin.getAllValues().get(i))
                    .isLessThanOrEqualTo(8 * HOUR)
                    .isPositive();
        }
        assertThat(begin.getAllValues().get(1) - begin.getAllValues().get(0)).isEqualTo(8 * HOUR + 1);
    }

    @Test
    void rangeEntirelyOlderThanRetentionEndsTruncatedWithoutCalls() {
        long now = System.currentTimeMillis();

        TaskOutcome outcome = handler.execute(
                ratioTask("5m", "LSR_ACCOUNT_CCY", now - 200 * DAY, now - 190 * DAY),
                BackfillControls.running(6L));

        assertThat(outcome.rowsWritten()).isZero();
        assertThat(outcome.truncated()).isTrue();
        assertThat(outcome.note()).isEqualTo("requested range is older than retention window");
        assertThat(cursors).isEmpty();
        verify(client, never()).getLongShortRatio(any(), anyString(), any(), anyLong(), anyLong());
    }

    @Test
    void rangeExhaustedWindowsAreSkippedInsteadOfFailing() {
        long now = System.currentTimeMillis();
        when(client.getLongShortRatio(any(), anyString(), any(), anyLong(), anyLong()))
                .thenThrow(new OkxApiException(OkxApiException.Kind.RANGE_EXHAUSTED, "50030", 400,
                        "out of range", new TransportResponse(400, "{}")));

        TaskOutcome outcome = handler.execute(ratioTask("5m", "LSR_ACCOUNT_CCY", now - 60 * DAY, now),
                BackfillControls.running(6L));

        assertThat(outcome.rowsWritten()).isZero();
        assertThat(outcome.truncated()).isTrue();
        assertThat(cursors).hasSize(6);
        verify(repository, never()).upsertRatios(any());
    }

    @Test
    void resumesFromPersistedCursorAndAccumulatesCounters() {
        long now = System.currentTimeMillis();
        long requestedStart = now - 30 * HOUR;
        long endMs = now - 2 * HOUR;
        long cursor = now - 20 * HOUR;
        when(client.getLongShortRatio(any(), anyString(), any(), anyLong(), anyLong()))
                .thenReturn(ratios(now - HOUR));

        TaskOutcome outcome = handler.execute(
                ratioTask("5m", "LSR_ACCOUNT_CCY", requestedStart, endMs, cursor, 2, 7L),
                BackfillControls.running(6L));

        // 从任务行游标续跑： [now-20h, now-12h] -> [now-12h+1, now-4h+1] -> [now-4h+2, now-2h]
        assertThat(cursors).hasSize(3);
        assertThat(cursors.get(0)).isCloseTo(now - 12 * HOUR + 1, within(2_000L));
        // pageCount / rowsWritten 在任务行已有进度上继续累加（2 页 7 行 -> 5 页 10 行）
        assertThat(pageCounts).containsExactly(3, 4, 5);
        assertThat(outcome.rowsWritten()).isEqualTo(10L);
        assertThat(outcome.truncated()).isFalse();
        verify(repository, times(3)).upsertRatios(any());
    }

    @Test
    void ignoresPresetCursorWhenNoPageHasBeenWritten() {
        long now = System.currentTimeMillis();
        long requestedStart = now - 48 * HOUR;
        when(client.getLongShortRatio(any(), anyString(), any(), anyLong(), anyLong()))
                .thenReturn(ratios(now - HOUR));

        // 任务行入库时预置 cursorMs=endMs，但 pageCount=0，统计类必须从 startMs 起算
        handler.execute(ratioTask("5m", "LSR_ACCOUNT_CCY", requestedStart, now, now, 0, 0L),
                BackfillControls.running(6L));

        assertThat(cursors).hasSize(6);
        assertThat(cursors.get(0)).isCloseTo(requestedStart + 8 * HOUR, within(5_000L));
    }

    @Test
    void otherUpstreamErrorsPropagate() {
        long now = System.currentTimeMillis();
        when(client.getLongShortRatio(any(), anyString(), any(), anyLong(), anyLong()))
                .thenThrow(new OkxApiException(OkxApiException.Kind.UPSTREAM_ERROR, "50001", 500,
                        "upstream boom", new TransportResponse(500, "{}")));

        assertThatThrownBy(() -> handler.execute(ratioTask("5m", "LSR_ACCOUNT_CCY", now - 60 * DAY, now),
                BackfillControls.running(6L)))
                .isInstanceOf(OkxApiException.class)
                .hasMessageContaining("upstream boom");
    }

    @Test
    void openInterestVolumeUsesCcyEndpoint() {
        long now = System.currentTimeMillis();
        TaskParams params = new TaskParams(DataType.OPEN_INTEREST_VOLUME.name(), null, null, null, null, null, null,
                null, "BTC", "5m", null, now - 60 * DAY, now, null);
        BackfillTaskRow task = BackfillControls.task(9L, 6L, "OI_VOLUME:BTC:5m",
                MAPPER.writeValueAsString(params));
        when(client.getOpenInterestVolume(anyString(), any(), anyLong(), anyLong())).thenReturn(List.of());

        handler.execute(task, BackfillControls.running(6L));

        verify(client, times(6)).getOpenInterestVolume(eq("BTC"), eq(Period.M5), anyLong(), anyLong());
    }

    @Test
    void takerVolumeChoosesEndpointByScopeKind() {
        long now = System.currentTimeMillis();
        TaskParams instParams = new TaskParams(DataType.TAKER_VOLUME.name(), "BTC-USDT-SWAP", "SWAP", null, null,
                null, "BTC-USDT-SWAP", "INST", "BTC-USDT-SWAP", "5m", null, now - 60 * DAY, now, null);
        when(client.getTakerVolumeByInst(anyString(), any(), anyLong(), anyLong())).thenReturn(List.of());

        handler.execute(BackfillControls.task(10L, 6L, "TAKER_VOLUME:INST:BTC-USDT-SWAP:5m",
                MAPPER.writeValueAsString(instParams)), BackfillControls.running(6L));

        verify(client, times(6)).getTakerVolumeByInst(eq("BTC-USDT-SWAP"), eq(Period.M5), anyLong(), anyLong());
        verify(client, never()).getTakerVolumeByCcy(anyString(), anyString(), any(), anyLong(), anyLong());
    }

    @Test
    void declaresStatisticsDataTypes() {
        assertThat(handler.dataTypes()).containsExactly(DataType.LONG_SHORT_RATIO, DataType.OPEN_INTEREST_VOLUME,
                DataType.TAKER_VOLUME);
    }
}
