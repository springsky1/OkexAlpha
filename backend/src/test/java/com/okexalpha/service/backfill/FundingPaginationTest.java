package com.okexalpha.service.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

import tools.jackson.databind.json.JsonMapper;

import com.okexalpha.client.OkxApiClient;
import com.okexalpha.client.model.OkxFundingRate;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.repository.FundingRateRepository;
import com.okexalpha.support.BackfillControls;

/** FundingRateTaskHandler：空页 = 到达 OKX 保留边界，任务以 truncated=true 正常结束。 */
class FundingPaginationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long DAY = 86_400_000L;
    private static final long END = 1_735_689_600_000L;
    private static final long START = END - 5 * DAY;

    private OkxApiClient client;
    private FundingRateRepository repository;
    private FundingRateTaskHandler handler;
    private final List<Long> cursors = new ArrayList<>();
    private final List<Boolean> truncationFlags = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = mock(OkxApiClient.class);
        repository = mock(FundingRateRepository.class);
        BackfillPageWriter pageWriter = mock(BackfillPageWriter.class);
        doAnswer(invocation -> {
            cursors.add(invocation.getArgument(2));
            truncationFlags.add(invocation.getArgument(5));
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(pageWriter).write(anyLong(), any(Runnable.class), any(), anyInt(), anyLong(), anyBoolean());

        OkxProperties properties = OkxProperties.defaults();
        handler = new FundingRateTaskHandler(client, repository, new PageBatcher(pageWriter, properties), MAPPER);
    }

    private static OkxFundingRate funding(long fundingTimeMs) {
        return MAPPER.readValue("{\"instId\":\"BTC-USDT-SWAP\",\"instType\":\"SWAP\",\"fundingTime\":\""
                + fundingTimeMs + "\"}", OkxFundingRate.class);
    }

    private BackfillTaskRow task() {
        TaskParams params = new TaskParams(DataType.FUNDING_RATE.name(), "BTC-USDT-SWAP", "SWAP", null, null, null,
                null, null, null, null, null, START, END, null);
        return BackfillControls.task(3L, 4L, "FUNDING:BTC-USDT-SWAP", MAPPER.writeValueAsString(params));
    }

    @Test
    void emptyFirstPageMarksTaskTruncated() {
        when(client.getFundingRateHistory(anyString(), any(), isNull(), anyInt())).thenReturn(List.of());

        TaskOutcome outcome = handler.execute(task(), BackfillControls.running(4L));

        assertThat(outcome.rowsWritten()).isZero();
        assertThat(outcome.truncated()).isTrue();
        assertThat(outcome.note()).isNull();
        assertThat(cursors).containsExactly(END);
        assertThat(truncationFlags).containsExactly(true);
        verify(repository, never()).upsertAll(anyList());
    }

    @Test
    void onePageThenEmptyPageWritesRowsAndMarksTruncated() {
        long older = END - 8 * 3_600_000L;
        AtomicInteger call = new AtomicInteger();
        when(client.getFundingRateHistory(anyString(), any(), isNull(), anyInt())).thenAnswer(invocation -> {
            int index = call.getAndIncrement();
            return index == 0 ? List.of(funding(END - 3_600_000L), funding(older)) : List.<OkxFundingRate>of();
        });

        TaskOutcome outcome = handler.execute(task(), BackfillControls.running(4L));

        assertThat(outcome.rowsWritten()).isEqualTo(2L);
        assertThat(outcome.truncated()).isTrue();
        assertThat(cursors).containsExactly(older);
        assertThat(truncationFlags).containsExactly(true);
        verify(repository, times(1)).upsertAll(anyList());
    }

    @Test
    void pagesUseHundredRowLimit() {
        when(client.getFundingRateHistory(anyString(), any(), isNull(), anyInt())).thenReturn(List.of());

        handler.execute(task(), BackfillControls.running(4L));

        verify(client).getFundingRateHistory("BTC-USDT-SWAP", END, null, 100);
    }

    @Test
    void rowsOutsideRangeAreDropped() {
        when(client.getFundingRateHistory(anyString(), any(), isNull(), anyInt()))
                .thenReturn(List.of(funding(END + DAY), funding(START - DAY)));

        TaskOutcome outcome = handler.execute(task(), BackfillControls.running(4L));

        assertThat(outcome.rowsWritten()).isZero();
        assertThat(outcome.truncated()).isTrue();
        verify(repository, never()).upsertAll(anyList());
    }

    @Test
    void pausedJobStopsWithoutUpstreamCalls() {
        TaskOutcome outcome = handler.execute(task(), BackfillControls.paused(4L));

        assertThat(outcome.note()).isEqualTo("stopped");
        verify(client, never()).getFundingRateHistory(anyString(), any(), any(), anyInt());
    }

    @Test
    void declaresFundingDataType() {
        assertThat(handler.dataTypes()).containsExactly(DataType.FUNDING_RATE);
    }
}
