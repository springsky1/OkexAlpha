package com.okexalpha.service.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.JobStatus;
import com.okexalpha.error.ConflictException;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.repository.BackfillJobRepository;
import com.okexalpha.repository.InstrumentRepository;
import com.okexalpha.repository.SyncCursorRepository;

/**
 * IncrementalSyncService：播种 / 缺口提交 / 水位推进 / RUNNING 跳过 / 开关关闭。
 *
 * <p>纯单元测试：四个依赖（补拉编排、作业库、水位库、品种库）全部 mock，时钟注入，
 * 不启 Spring、不连库、不联网。</p>
 */
class IncrementalSyncServiceTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long LOOKBACK = 3_600_000L;
    private static final long LAG = 60_000L;
    private static final String INST = "BTC-USDT-SWAP";
    private static final String KEY = "kline|CANDLE|" + INST + "|1m";

    private BackfillService backfillService;
    private BackfillJobRepository jobRepository;
    private SyncCursorRepository cursorRepository;
    private InstrumentRepository instrumentRepository;

    /** 水位库的假实现：all() 返回快照，set() 直接落进这个 map。 */
    private final Map<String, Long> cursors = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        backfillService = mock(BackfillService.class);
        jobRepository = mock(BackfillJobRepository.class);
        cursorRepository = mock(SyncCursorRepository.class);
        instrumentRepository = mock(InstrumentRepository.class);
        cursors.clear();

        when(cursorRepository.all()).thenAnswer(invocation -> new LinkedHashMap<>(cursors));
        doAnswer(invocation -> {
            cursors.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(cursorRepository).set(any(), anyLong());
        when(instrumentRepository.findInstIds(eq("SWAP"), any(), anyInt())).thenReturn(List.of(INST));
    }

    private IncrementalSyncService service(boolean enabled) {
        return new IncrementalSyncService(backfillService, jobRepository, cursorRepository, instrumentRepository,
                enabled, 300_000L, LOOKBACK, LAG, "1m", "CANDLE", 20, () -> NOW);
    }

    @Test
    @DisplayName("enabled=false：直接返回 disabled，不碰任何仓储")
    void disabledSkipsWithoutTouchingRepositories() {
        IncrementalSyncService.RunResult result = service(false).runOnce();

        assertThat(result.submitted()).isFalse();
        assertThat(result.skippedReason()).isEqualTo("disabled");
        assertThat(result.jobs()).isEmpty();
        assertThat(result.seeded()).isZero();
        assertThat(result.cursorAdvanced()).isZero();
        verifyNoInteractions(backfillService, jobRepository, cursorRepository, instrumentRepository);
    }

    @Test
    @DisplayName("已有 KLINE 作业在 RUNNING：本轮跳过并回显 jobId")
    void skipsWhileKlineJobRunning() {
        when(jobRepository.findJobs(DataType.KLINE.name(), JobStatus.RUNNING.name(), 1, 0))
                .thenReturn(List.of(job(77L)));

        IncrementalSyncService.RunResult result = service(true).runOnce();

        assertThat(result.submitted()).isFalse();
        assertThat(result.skippedReason()).isEqualTo("kline-job-running");
        assertThat(result.jobId()).isEqualTo(77L);
        assertThat(result.note()).contains("job 77");
        verify(cursorRepository, never()).set(any(), anyLong());
        verify(backfillService, never()).submitKlines(any());
    }

    @Test
    @DisplayName("游标为空：只播种 now-lookback，本轮不提交任何作业")
    void emptyCursorOnlySeeds() {
        IncrementalSyncService service = new IncrementalSyncService(backfillService, jobRepository, cursorRepository,
                instrumentRepository, true, 300_000L, LOOKBACK, LAG, "1m", "CANDLE", 20, () -> NOW);

        IncrementalSyncService.RunResult result = service.runOnce();

        assertThat(result.submitted()).isFalse();
        assertThat(result.skippedReason()).isNull();
        assertThat(result.seeded()).isEqualTo(1);
        assertThat(result.cursorAdvanced()).isZero();
        assertThat(result.jobs()).isEmpty();
        assertThat(cursors).containsEntry(KEY, NOW - LOOKBACK);
        verify(cursorRepository).set(KEY, NOW - LOOKBACK);
        verify(backfillService, never()).submitKlines(any());
    }

    @Test
    @DisplayName("有缺口：按 (bar,klineType) 提交 start=游标+1..now-lag，但本轮不推进水位（等作业 DONE）")
    void submitsGapAndAdvancesCursor() {
        long end = NOW - LAG;
        cursors.put(KEY, end - 300_000L);
        when(backfillService.submitKlines(any()))
                .thenReturn(new BackfillService.JobSubmission(42L, 1));

        IncrementalSyncService.RunResult result = service(true).runOnce();

        assertThat(result.submitted()).isTrue();
        assertThat(result.skippedReason()).isNull();
        assertThat(result.jobId()).isNull();
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).jobId()).isEqualTo(42L);
        assertThat(result.jobs().get(0).taskTotal()).isEqualTo(1);
        assertThat(result.jobs().get(0).bar()).isEqualTo("1m");
        assertThat(result.jobs().get(0).klineType()).isEqualTo("CANDLE");
        // 提交只是入队：水位必须等作业真的 DONE 才推进，否则 FAILED/CANCELED 会静默丢数据
        assertThat(result.cursorAdvanced()).isZero();
        assertThat(result.seeded()).isZero();
        assertThat(result.note()).contains("待完成推进 1 条");

        ArgumentCaptor<KlineBackfillRequest> captor = ArgumentCaptor.forClass(KlineBackfillRequest.class);
        verify(backfillService).submitKlines(captor.capture());
        KlineBackfillRequest request = captor.getValue();
        assertThat(request.instIds()).containsExactly(INST);
        assertThat(request.instType()).isNull();
        assertThat(request.baseCcy()).isNull();
        assertThat(request.bars()).containsExactly("1m");
        assertThat(request.klineTypes()).containsExactly("CANDLE");
        assertThat(request.startMs()).isEqualTo(end - 300_000L + 1);
        assertThat(request.endMs()).isEqualTo(end);
        assertThat(request.pageLimit()).isNull();

        assertThat(cursors).containsEntry(KEY, end - 300_000L);
        verify(cursorRepository, never()).set(any(), anyLong());
    }

    @Test
    @DisplayName("ConflictException（同 task_key 已有未完成任务）：视为已覆盖，游标照样推进")
    void conflictStillAdvancesCursor() {
        long end = NOW - LAG;
        cursors.put(KEY, end - 300_000L);
        when(backfillService.submitKlines(any()))
                .thenThrow(new ConflictException("all 1 task(s) are already pending or running"));

        IncrementalSyncService.RunResult result = service(true).runOnce();

        assertThat(result.submitted()).isFalse();
        assertThat(result.jobs()).isEmpty();
        assertThat(result.cursorAdvanced()).isEqualTo(1);
        assertThat(result.note()).contains("CANDLE:1m");
        assertThat(cursors).containsEntry(KEY, end);
        verify(cursorRepository).set(KEY, end);
    }

    @Test
    @DisplayName("缺口不足一根 K 线：跳过，不提交也不动游标")
    void skipsWhenGapShorterThanOneBar() {
        long end = NOW - LAG;
        cursors.put(KEY, end - 30_000L); // 1m 周期下只差 30s，凑不满一根

        IncrementalSyncService.RunResult result = service(true).runOnce();

        assertThat(result.submitted()).isFalse();
        assertThat(result.skippedReason()).isNull();
        assertThat(result.jobs()).isEmpty();
        assertThat(result.cursorAdvanced()).isZero();
        assertThat(result.note()).contains("缺口不足一根");
        assertThat(cursors).containsEntry(KEY, end - 30_000L);
        verify(cursorRepository, never()).set(any(), anyLong());
        verify(backfillService, never()).submitKlines(any());
    }

    @Test
    @DisplayName("多个 instId 同组：一个作业覆盖全部，start 取组内最小游标，全部推进到 end")
    void groupsInstIdsIntoOneJobPerBarAndKlineType() {
        long end = NOW - LAG;
        String second = "ETH-USDT-SWAP";
        when(instrumentRepository.findInstIds(eq("SWAP"), any(), anyInt())).thenReturn(List.of(INST, second));
        cursors.put(KEY, end - 600_000L);
        cursors.put("kline|CANDLE|" + second + "|1m", end - 120_000L);
        when(backfillService.submitKlines(any())).thenReturn(new BackfillService.JobSubmission(9L, 2));

        IncrementalSyncService.RunResult result = service(true).runOnce();

        assertThat(result.jobs()).hasSize(1);
        assertThat(result.cursorAdvanced()).isZero();
        ArgumentCaptor<KlineBackfillRequest> captor = ArgumentCaptor.forClass(KlineBackfillRequest.class);
        verify(backfillService).submitKlines(captor.capture());
        assertThat(captor.getValue().instIds()).containsExactly(INST, second);
        assertThat(captor.getValue().startMs()).isEqualTo(end - 600_000L + 1);
        // 水位等作业 DONE 后才推进
        assertThat(cursors).containsEntry(KEY, end - 600_000L);
        assertThat(cursors).containsEntry("kline|CANDLE|" + second + "|1m", end - 120_000L);
    }

    @Test
    @DisplayName("多个 bar 各自一个作业：不同周期缺口不同，start 不串台")
    void submitsOneJobPerBar() {
        long end = NOW - LAG;
        IncrementalSyncService service = new IncrementalSyncService(backfillService, jobRepository, cursorRepository,
                instrumentRepository, true, 300_000L, LOOKBACK, LAG, "1m,1H", "CANDLE", 20, () -> NOW);
        cursors.put(KEY, end - 120_000L);
        cursors.put("kline|CANDLE|" + INST + "|1H", end - 7_200_000L);
        when(backfillService.submitKlines(any()))
                .thenReturn(new BackfillService.JobSubmission(1L, 1))
                .thenReturn(new BackfillService.JobSubmission(2L, 1));

        IncrementalSyncService.RunResult result = service.runOnce();

        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs().get(0).bar()).isEqualTo("1m");
        assertThat(result.jobs().get(0).jobId()).isEqualTo(1L);
        assertThat(result.jobs().get(1).bar()).isEqualTo("1H");
        assertThat(result.jobs().get(1).jobId()).isEqualTo(2L);
        assertThat(result.cursorAdvanced()).isZero();
        verify(backfillService, org.mockito.Mockito.times(2)).submitKlines(any());
    }

    @Test
    @DisplayName("作业 DONE 后：下一轮把水位推进到当时的 end，pending 清空")
    void advancesCursorOnlyAfterJobDone() {
        long end = NOW - LAG;
        cursors.put(KEY, end - 300_000L);
        when(backfillService.submitKlines(any())).thenReturn(new BackfillService.JobSubmission(42L, 1));

        IncrementalSyncService service = service(true);
        IncrementalSyncService.RunResult first = service.runOnce();

        assertThat(first.cursorAdvanced()).isZero();
        assertThat(service.status().pendingJobs()).isEqualTo(1);
        assertThat(cursors).containsEntry(KEY, end - 300_000L);

        // 作业跑完：第二轮先对账，把水位推进到第一轮记录的 end；此时已无缺口，不再提交
        when(backfillService.getJob(42L)).thenReturn(jobWithStatus(42L, JobStatus.DONE));
        IncrementalSyncService.RunResult second = service.runOnce();

        assertThat(second.cursorAdvanced()).isEqualTo(1);
        assertThat(second.submitted()).isFalse();
        assertThat(second.note()).contains("本轮对账推进 1 个游标");
        assertThat(cursors).containsEntry(KEY, end);
        verify(cursorRepository).set(KEY, end);
        assertThat(service.status().pendingJobs()).isZero();
    }

    @Test
    @DisplayName("作业 FAILED：不推进水位，下一轮按旧水位重新提交（缺口自愈）")
    void keepsCursorWhenJobFailed() {
        long end = NOW - LAG;
        cursors.put(KEY, end - 300_000L);
        when(backfillService.submitKlines(any()))
                .thenReturn(new BackfillService.JobSubmission(43L, 1))
                .thenReturn(new BackfillService.JobSubmission(44L, 1));
        when(backfillService.getJob(43L)).thenReturn(jobWithStatus(43L, JobStatus.FAILED));

        IncrementalSyncService service = service(true);
        service.runOnce();
        IncrementalSyncService.RunResult second = service.runOnce();

        assertThat(second.cursorAdvanced()).isZero();
        assertThat(second.submitted()).isTrue();
        assertThat(second.jobs()).extracting(IncrementalSyncService.SubmittedJob::jobId).containsExactly(44L);
        assertThat(second.note()).contains("丢弃 1 个失败/失联作业登记");
        assertThat(cursors).containsEntry(KEY, end - 300_000L);
        verify(backfillService, org.mockito.Mockito.times(2)).submitKlines(any());
    }

    @Test
    @DisplayName("作业被 CANCELED：同样不推进水位，下一轮重提")
    void keepsCursorWhenJobCanceled() {
        long end = NOW - LAG;
        cursors.put(KEY, end - 300_000L);
        when(backfillService.submitKlines(any())).thenReturn(new BackfillService.JobSubmission(47L, 1));
        when(backfillService.getJob(47L)).thenReturn(jobWithStatus(47L, JobStatus.CANCELED));

        IncrementalSyncService service = service(true);
        service.runOnce();
        IncrementalSyncService.RunResult second = service.runOnce();

        assertThat(second.cursorAdvanced()).isZero();
        assertThat(cursors).containsEntry(KEY, end - 300_000L);
        verify(cursorRepository, never()).set(any(), anyLong());
    }

    @Test
    @DisplayName("getJob 抛异常（作业行已查不到）：丢弃登记但不阻塞本轮，缺口会重提")
    void survivesGetJobFailure() {
        long end = NOW - LAG;
        cursors.put(KEY, end - 300_000L);
        when(backfillService.submitKlines(any()))
                .thenReturn(new BackfillService.JobSubmission(45L, 1))
                .thenReturn(new BackfillService.JobSubmission(46L, 1));
        when(backfillService.getJob(45L)).thenThrow(new NotFoundException("job not found: 45"));

        IncrementalSyncService service = service(true);
        service.runOnce();
        IncrementalSyncService.RunResult second = service.runOnce();

        assertThat(second.cursorAdvanced()).isZero();
        assertThat(second.jobs()).extracting(IncrementalSyncService.SubmittedJob::jobId).containsExactly(46L);
        assertThat(cursors).containsEntry(KEY, end - 300_000L);
        // 第一轮的登记已丢弃，只剩第二轮新登记的 1 条
        assertThat(service.status().pendingJobs()).isEqualTo(1);
    }

    @Test
    @DisplayName("status()：带配置回显，只统计 kline| 游标，cursors 最多 50 条")
    void statusReportsConfigAndCappedCursors() {
        Map<String, Long> many = new LinkedHashMap<>();
        many.put("option_family:BTC-USD", 1L);
        for (int i = 0; i < 60; i++) {
            many.put("kline|CANDLE|SYM" + i + "-USDT-SWAP|1m", NOW - i);
        }
        when(cursorRepository.all()).thenReturn(many);
        when(jobRepository.findJobs(DataType.KLINE.name(), JobStatus.RUNNING.name(), 100, 0))
                .thenReturn(List.of(job(1L), job(2L)));

        IncrementalSyncService.Status status = service(true).status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.intervalMs()).isEqualTo(300_000L);
        assertThat(status.lookbackMs()).isEqualTo(LOOKBACK);
        assertThat(status.lagMs()).isEqualTo(LAG);
        assertThat(status.bars()).containsExactly("1m");
        assertThat(status.klineTypes()).containsExactly("CANDLE");
        assertThat(status.maxInstIds()).isEqualTo(20);
        assertThat(status.cursorCount()).isEqualTo(60);
        assertThat(status.cursors()).hasSize(50);
        assertThat(status.cursors().get(0).key()).isEqualTo("kline|CANDLE|SYM0-USDT-SWAP|1m");
        assertThat(status.runningKlineJobs()).isEqualTo(2);
        assertThat(status.pendingJobs()).isZero();
        assertThat(status.lastNote()).isEqualTo("尚未运行");
        assertThat(status.lastRunAtMs()).isNull();
        assertThat(status.lastJobId()).isNull();
    }

    @Test
    @DisplayName("定时入口在关闭时直接返回，不做任何事")
    void scheduledRunIsNoOpWhenDisabled() {
        service(false).scheduledRun();

        verifyNoInteractions(backfillService, jobRepository, cursorRepository, instrumentRepository);
    }

    @Test
    @DisplayName("定时入口吞掉异常并只记 lastNote（否则调度器会静默停掉）")
    void scheduledRunSwallowsFailures() {
        IncrementalSyncService service = service(true);
        when(instrumentRepository.findInstIds(eq("SWAP"), any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        service.scheduledRun();

        assertThat(service.status().lastNote()).contains("db down");
        assertThat(service.status().lastRunAtMs()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("播种后隔一轮即可补数据：播种写 lookback，下一轮按缺口提交")
    void seedsThenBackfillsOnNextRound() {
        AtomicLong clock = new AtomicLong(NOW);
        IncrementalSyncService service = new IncrementalSyncService(backfillService, jobRepository, cursorRepository,
                instrumentRepository, true, 300_000L, LOOKBACK, LAG, "1m", "CANDLE", 20, clock::get);

        IncrementalSyncService.RunResult first = service.runOnce();
        assertThat(first.seeded()).isEqualTo(1);
        assertThat(first.submitted()).isFalse();

        // 5 分钟后再跑一轮（播种水位 now-lookback 已经落后 5 分钟）
        clock.addAndGet(300_000L);
        when(backfillService.submitKlines(any())).thenReturn(new BackfillService.JobSubmission(5L, 1));
        IncrementalSyncService.RunResult second = service.runOnce();

        assertThat(second.seeded()).isZero();
        assertThat(second.submitted()).isTrue();
        ArgumentCaptor<KlineBackfillRequest> captor = ArgumentCaptor.forClass(KlineBackfillRequest.class);
        verify(backfillService).submitKlines(captor.capture());
        assertThat(captor.getValue().startMs()).isEqualTo(NOW - LOOKBACK + 1);
        assertThat(captor.getValue().endMs()).isEqualTo(NOW + 300_000L - LAG);
    }

    private static BackfillJobRow job(long id) {
        return jobWithStatus(id, JobStatus.RUNNING);
    }

    private static BackfillJobRow jobWithStatus(long id, JobStatus status) {
        return new BackfillJobRow(id, DataType.KLINE.name(), "{}", status.name(), 1, 0, 0, 0L, null, null, null,
                null);
    }
}
