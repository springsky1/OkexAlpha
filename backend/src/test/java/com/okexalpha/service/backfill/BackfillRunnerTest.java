package com.okexalpha.service.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.JobStatus;
import com.okexalpha.domain.TaskStatus;
import com.okexalpha.repository.BackfillJobRepository;

/**
 * BackfillRunner：task 级并发、claim 幂等、重试与失败收敛。
 *
 * <p>并发用例用 CountDownLatch 让 handler 卡住而不是 sleep：只有真的起了多个 worker，
 * “两个 task 同时进入 execute”这个条件才会成立，否则 latch 会超时失败。</p>
 */
class BackfillRunnerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long JOB = 11L;

    private BackfillJobRepository repository;
    private BackfillTaskHandler handler;

    @BeforeEach
    void setUp() {
        repository = mock(BackfillJobRepository.class);
        handler = mock(BackfillTaskHandler.class);
    }

    @Test
    @DisplayName("pending task 按 worker-threads 并发执行，且每个 task 只被 claim 一次")
    void executesPendingTasksInParallel() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch twoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(handler.dataTypes()).thenReturn(List.of(DataType.KLINE));
        when(handler.execute(any(), any())).thenAnswer(invocation -> {
            int running = active.incrementAndGet();
            peak.accumulateAndGet(running, Math::max);
            twoStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            active.decrementAndGet();
            return new TaskOutcome(2L, false, null);
        });
        stubJob("KLINE", 3);

        BackfillRunner runner = runner(3, handler);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> job = pool.submit(() -> runner.runJob(JOB));

            assertThat(twoStarted.await(2, TimeUnit.SECONDS)).as("两个 task 同时进入 execute").isTrue();
            assertThat(peak.get()).isGreaterThanOrEqualTo(2);

            release.countDown();
            job.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(runner.parallelism()).isEqualTo(3);
        verify(repository, times(1)).claimTask(1L);
        verify(repository, times(1)).claimTask(2L);
        verify(repository, times(1)).claimTask(3L);
        verify(handler, times(3)).execute(any(), any());
        verify(repository, times(3)).finishTask(anyLong(), eq(TaskStatus.DONE), isNull(), eq(false));
        verify(repository).setJobStatus(JOB, JobStatus.DONE, null);
    }

    @Test
    @DisplayName("worker-threads=1 时退化成顺序执行，不产生并发")
    void sequentialModeRunsOneTaskAtATime() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(handler.dataTypes()).thenReturn(List.of(DataType.KLINE));
        when(handler.execute(any(), any())).thenAnswer(invocation -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            active.decrementAndGet();
            return new TaskOutcome(1L, false, null);
        });
        stubJob("KLINE", 2);

        runner(1, handler).runJob(JOB);

        assertThat(peak.get()).isEqualTo(1);
        verify(repository, times(1)).claimTask(1L);
        verify(repository, times(1)).claimTask(2L);
        verify(handler, times(2)).execute(any(), any());
        verify(repository).setJobStatus(JOB, JobStatus.DONE, null);
    }

    @Test
    @DisplayName("handler 抛异常时 releaseTask 重试，attempts 用尽后 finishTask(FAILED)")
    void retriesUntilAttemptsAreExhausted() {
        AtomicInteger pendingCalls = new AtomicInteger();
        AtomicInteger attempt = new AtomicInteger();
        BackfillTaskRow failing = task(7L, "KLINE", 1);
        when(repository.findTasks(JOB, TaskStatus.PENDING))
                .thenAnswer(invocation -> pendingCalls.getAndIncrement() < 3 ? List.of(failing) : List.of());
        when(repository.claimTask(7L)).thenReturn(true);
        when(repository.findTask(7L)).thenAnswer(invocation -> task(7L, "KLINE", attempt.incrementAndGet()));
        when(repository.countTasks(JOB, TaskStatus.FAILED)).thenReturn(1);
        when(handler.dataTypes()).thenReturn(List.of(DataType.KLINE));
        when(handler.execute(any(), any())).thenThrow(new IllegalStateException("boom"));
        stubJobHeader("KLINE");

        runner(1, handler).runJob(JOB);

        verify(repository, times(3)).claimTask(7L);
        verify(repository, times(2)).releaseTask(7L);
        verify(repository, times(1)).finishTask(eq(7L), eq(TaskStatus.FAILED), contains("boom"), eq(false));
        verify(repository).setJobStatus(eq(JOB), eq(JobStatus.FAILED), contains("1 task(s) failed"));
    }

    @Test
    @DisplayName("outcome.note=stopped 的任务回到 PENDING 而不是 DONE")
    void stoppedOutcomeRequeuesTask() {
        BackfillTaskRow stopped = task(5L, "KLINE", 1);
        AtomicInteger pendingCalls = new AtomicInteger();
        when(repository.findTasks(JOB, TaskStatus.PENDING))
                .thenAnswer(invocation -> pendingCalls.getAndIncrement() < 1 ? List.of(stopped) : List.of());
        when(repository.claimTask(5L)).thenReturn(true);
        when(repository.findTask(5L)).thenReturn(stopped);
        when(handler.dataTypes()).thenReturn(List.of(DataType.KLINE));
        when(handler.execute(any(), any())).thenReturn(new TaskOutcome(0L, false, "stopped"));
        stubJobHeader("KLINE");

        runner(1, handler).runJob(JOB);

        verify(repository).finishTask(5L, TaskStatus.PENDING, null, false);
        verify(repository, never()).finishTask(eq(5L), eq(TaskStatus.DONE), any(), eq(false));
    }

    @Test
    @DisplayName("没有对应 handler 的 dataType 直接判失败，不调用任何 handler")
    void taskWithoutHandlerFailsImmediately() {
        BackfillTaskRow orphan = task(9L, """
                {"dataType":"OPEN_INTEREST"}
                """, 1);
        AtomicInteger pendingCalls = new AtomicInteger();
        when(repository.findTasks(JOB, TaskStatus.PENDING))
                .thenAnswer(invocation -> pendingCalls.getAndIncrement() < 1 ? List.of(orphan) : List.of());
        when(repository.claimTask(9L)).thenReturn(true);
        when(repository.findTask(9L)).thenReturn(orphan);
        when(handler.dataTypes()).thenReturn(List.of(DataType.KLINE));
        stubJobHeader("KLINE");

        runner(1, handler).runJob(JOB);

        verify(repository).finishTask(9L, TaskStatus.FAILED, "no handler for OPEN_INTEREST", false);
        verify(handler, never()).execute(any(), any());
    }

    @Test
    @DisplayName("params 里没有 dataType 时回落到 job 的 data_type")
    void fallsBackToJobDataTypeWhenParamsMissIt() {
        BackfillTaskRow task = task(3L, "{}", 1);
        AtomicInteger pendingCalls = new AtomicInteger();
        when(repository.findTasks(JOB, TaskStatus.PENDING))
                .thenAnswer(invocation -> pendingCalls.getAndIncrement() < 1 ? List.of(task) : List.of());
        when(repository.claimTask(3L)).thenReturn(true);
        when(repository.findTask(3L)).thenReturn(task);
        when(handler.dataTypes()).thenReturn(List.of(DataType.KLINE));
        when(handler.execute(any(), any())).thenReturn(new TaskOutcome(4L, false, null));
        stubJobHeader("KLINE");

        runner(1, handler).runJob(JOB);

        verify(handler).execute(any(), any());
        verify(repository).finishTask(3L, TaskStatus.DONE, null, false);
    }

    @Test
    @DisplayName("params 不是合法 JSON 时也回落到 job 的 data_type")
    void fallsBackToJobDataTypeWhenParamsAreMalformed() {
        BackfillTaskRow task = task(4L, "not-json", 1);
        AtomicInteger pendingCalls = new AtomicInteger();
        when(repository.findTasks(JOB, TaskStatus.PENDING))
                .thenAnswer(invocation -> pendingCalls.getAndIncrement() < 1 ? List.of(task) : List.of());
        when(repository.claimTask(4L)).thenReturn(true);
        when(repository.findTask(4L)).thenReturn(task);
        when(handler.dataTypes()).thenReturn(List.of(DataType.KLINE));
        when(handler.execute(any(), any())).thenReturn(new TaskOutcome(0L, false, null));
        stubJobHeader("KLINE");

        runner(1, handler).runJob(JOB);

        verify(handler).execute(any(), any());
        verify(repository).finishTask(4L, TaskStatus.DONE, null, false);
    }

    @Test
    @DisplayName("job 已是 PAUSED 时不领取任何 task")
    void pausedJobStopsBeforeClaimingTasks() {
        when(repository.claimJob(JOB)).thenReturn(true);
        when(repository.findJob(JOB)).thenReturn(jobRow("KLINE", "PAUSED"));

        runner(2, handler).runJob(JOB);

        verify(repository, never()).findTasks(anyLong(), any());
        verify(repository, never()).claimTask(anyLong());
        verify(repository, never()).setJobStatus(anyLong(), any(), any());
    }

    @Test
    @DisplayName("claimJob 失败（别的实例已被领走）时整轮跳过")
    void skipsWhenJobIsNotClaimable() {
        when(repository.claimJob(JOB)).thenReturn(false);

        runner(2, handler).runJob(JOB);

        verify(repository, never()).findJob(anyLong());
        verify(repository, never()).claimTask(anyLong());
    }

    @Test
    @DisplayName("parallelism 被夹到 1..32")
    void parallelismIsClamped() {
        assertThat(runner(0).parallelism()).isEqualTo(1);
        assertThat(runner(4).parallelism()).isEqualTo(4);
        assertThat(runner(99).parallelism()).isEqualTo(32);
    }

    // ------------------------------------------------------------ 辅助

    private BackfillRunner runner(int workerThreads) {
        return runner(workerThreads, new BackfillTaskHandler[0]);
    }

    private BackfillRunner runner(int workerThreads, BackfillTaskHandler... handlers) {
        return new BackfillRunner(repository, MAPPER, props(workerThreads), List.of(handlers));
    }

    /** 只把 job 置成可领取 + RUNNING，pending/findTask 交给各用例自行编排。 */
    private void stubJobHeader(String dataType) {
        when(repository.claimJob(JOB)).thenReturn(true);
        when(repository.findJob(JOB)).thenReturn(jobRow(dataType, "RUNNING"));
    }

    /** 一轮 pending 列表：id 1..count，params 自带 dataType 覆盖。 */
    private void stubJob(String dataType, int count) {
        stubJobHeader(dataType);
        List<BackfillTaskRow> pending = new java.util.ArrayList<>();
        Map<Long, BackfillTaskRow> rows = new java.util.HashMap<>();
        for (int i = 1; i <= count; i++) {
            BackfillTaskRow row = task(i, dataType, 1);
            pending.add(row);
            rows.put((long) i, row);
            when(repository.claimTask(i)).thenReturn(true);
        }
        when(repository.findTasks(JOB, TaskStatus.PENDING)).thenReturn(pending, List.of());
        when(repository.findTask(anyLong())).thenAnswer(invocation -> rows.get(invocation.getArgument(0, Long.class)));
    }

    private static BackfillJobRow jobRow(String dataType, String status) {
        return new BackfillJobRow(JOB, dataType, "{}", status, 1, 0, 0, 0L, null, null, null, null);
    }

    private static BackfillTaskRow task(long id, String params, int attempts) {
        return new BackfillTaskRow(id, JOB, "TASK:" + id, params, "RUNNING", null, 0, 0L, attempts, false,
                null, null, null);
    }

    private static OkxProperties props(int workerThreads) {
        OkxProperties base = OkxProperties.defaults();
        return new OkxProperties(base.baseUrl(), base.connectTimeoutMs(), base.readTimeoutMs(), base.userAgent(),
                base.retry(), base.rateLimit(),
                new OkxProperties.Backfill(workerThreads, base.backfill().queueCapacity(), base.backfill().pageLimit(),
                        base.backfill().autoResume(), base.backfill().maxTasksPerJob(), base.backfill().coverageSkip(),
                        base.backfill().coverageRefreshMs()),
                base.sync(), base.schedule(), base.apiLog(), base.db(), base.strategy());
    }
}
