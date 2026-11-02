package com.okexalpha.service.backfill;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.JobStatus;
import com.okexalpha.domain.TaskStatus;
import com.okexalpha.repository.BackfillJobRepository;

/**
 * 单个 job 的执行循环：领取 task → 执行 → 记结果，页边界响应 pause/cancel。
 *
 * <p>task 之间互不重叠（每个 task 是一串 instId/bar/period 的独立时间区间），所以一轮里的
 * pending task 用 {@code okx.backfill.worker-threads} 个线程并发跑，把单请求的延迟藏起来；
 * 真正的上游压力由 {@link com.okexalpha.client.OkxRateLimiter} 的端点组 + 全局窗口兜住，
 * 并发线程只是在限速允许的空档里多占几个在途请求。
 *
 * <p>领取仍然走 {@code claimTask} 的乐观更新（status PENDING → RUNNING），所以同一个 task
 * 不会被两个线程/两个 job 实例重复执行。
 */
@Component
public class BackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_PARALLELISM = 32;

    private final BackfillJobRepository jobRepository;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final int parallelism;
    private final Map<DataType, BackfillTaskHandler> handlers = new EnumMap<>(DataType.class);

    public BackfillRunner(BackfillJobRepository jobRepository, tools.jackson.databind.ObjectMapper objectMapper,
            OkxProperties properties, List<BackfillTaskHandler> handlerList) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        this.parallelism = Math.min(MAX_PARALLELISM, Math.max(1, properties.backfill().workerThreads()));
        for (BackfillTaskHandler handler : handlerList) {
            for (DataType type : handler.dataTypes()) {
                BackfillTaskHandler previous = handlers.put(type, handler);
                if (previous != null) {
                    log.warn("dataType {} is registered by both {} and {}, the latter wins", type,
                            previous.getClass().getSimpleName(), handler.getClass().getSimpleName());
                }
            }
        }
    }

    /** 当前并发度（单测/观测用）。 */
    public int parallelism() {
        return parallelism;
    }

    public void runJob(long jobId) {
        if (!jobRepository.claimJob(jobId)) {
            log.debug("job {} is not claimable, skip", jobId);
            return;
        }
        BackfillControl control = new BackfillControl(jobId, jobRepository);
        try {
            while (true) {
                BackfillJobRow job = jobRepository.findJob(jobId);
                if (job == null) {
                    return;
                }
                String status = job.status();
                if (JobStatus.PAUSED.name().equals(status) || JobStatus.CANCELED.name().equals(status)) {
                    log.info("job {} left running state: {}", jobId, status);
                    return;
                }
                List<BackfillTaskRow> pending = jobRepository.findTasks(jobId, TaskStatus.PENDING);
                if (pending.isEmpty()) {
                    break;
                }
                executeRound(job, pending, control);
                jobRepository.refreshCounters(jobId);
                if (control.shouldStop()) {
                    log.info("job {} stopped with {} task(s) left pending", jobId, pending.size());
                    return;
                }
            }
            jobRepository.refreshCounters(jobId);
            int failed = jobRepository.countTasks(jobId, TaskStatus.FAILED);
            jobRepository.setJobStatus(jobId, failed > 0 ? JobStatus.FAILED : JobStatus.DONE,
                    failed > 0 ? (failed + " task(s) failed") : null);
            log.info("job {} finished: failedTasks={}", jobId, failed);
        } catch (RuntimeException ex) {
            log.error("job {} crashed", jobId, ex);
            jobRepository.setJobStatus(jobId, JobStatus.FAILED, truncate(ex.getMessage()));
        }
    }

    /** 一轮：把当前 pending 列表按并发度分给若干 worker，直到列表跑完或作业被暂停/取消。 */
    private void executeRound(BackfillJobRow job, List<BackfillTaskRow> pending, BackfillControl control) {
        int threads = Math.min(parallelism, pending.size());
        if (threads <= 1) {
            for (BackfillTaskRow task : pending) {
                if (control.shouldStop()) {
                    return;
                }
                runTask(job, task, control);
            }
            return;
        }
        log.info("job {} round: {} pending task(s), parallelism={}", job.id(), pending.size(), threads);
        AtomicInteger index = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "backfill-task-" + job.id());
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    while (!control.shouldStop()) {
                        int next = index.getAndIncrement();
                        if (next >= pending.size()) {
                            return;
                        }
                        runTask(job, pending.get(next), control);
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException ex) {
                    log.error("job {} worker failed", job.id(), ex.getCause());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    private void runTask(BackfillJobRow job, BackfillTaskRow task, BackfillControl control) {
        if (!jobRepository.claimTask(task.id())) {
            return;
        }
        BackfillTaskRow claimed = jobRepository.findTask(task.id());
        if (claimed == null) {
            return;
        }
        executeOne(taskDataType(claimed, job.dataType()), claimed, control);
        jobRepository.refreshCounters(job.id());
    }

    /** 一个 job 里可能混着多种 dataType（market-stats 可同时含持仓量/主动买卖/持仓快照），以任务自身的 dataType 为准。 */
    private String taskDataType(BackfillTaskRow task, String jobDataType) {
        if (task.params() != null) {
            try {
                tools.jackson.databind.JsonNode node = objectMapper.readTree(task.params());
                tools.jackson.databind.JsonNode value = node.get("dataType");
                if (value != null && !value.isNull() && !value.asString().isBlank()) {
                    return value.asString();
                }
            } catch (RuntimeException ex) {
                log.warn("cannot read dataType from params of task {}: {}", task.taskKey(), ex.getMessage());
            }
        }
        return jobDataType;
    }

    private void executeOne(String dataType, BackfillTaskRow task, BackfillControl control) {
        DataType type;
        try {
            type = DataType.valueOf(dataType);
        } catch (IllegalArgumentException ex) {
            // params 里写了不存在的 dataType：不接住的话异常会从 worker 冒到 future.get()，
            // task 永远停在 RUNNING，job 也就永远等不到收敛。
            log.error("task {} has unknown dataType {}", task.taskKey(), dataType);
            jobRepository.finishTask(task.id(), TaskStatus.FAILED, "unknown dataType: " + dataType, false);
            return;
        }
        BackfillTaskHandler handler = handlers.get(type);
        if (handler == null) {
            jobRepository.finishTask(task.id(), TaskStatus.FAILED, "no handler for " + dataType, false);
            return;
        }
        try {
            TaskOutcome outcome = handler.execute(task, control);
            boolean stopped = "stopped".equals(outcome.note());
            jobRepository.finishTask(task.id(), stopped ? TaskStatus.PENDING : TaskStatus.DONE, null,
                    outcome.truncated());
            log.info("task {} {} rows={} truncated={}", task.taskKey(), stopped ? "requeued" : "done",
                    outcome.rowsWritten(), outcome.truncated());
        } catch (RuntimeException ex) {
            if (task.attempts() < MAX_ATTEMPTS) {
                log.warn("task {} attempt {}/{} failed: {}", task.taskKey(), task.attempts(), MAX_ATTEMPTS,
                        ex.getMessage());
                jobRepository.releaseTask(task.id());
            } else {
                log.error("task {} exhausted retries", task.taskKey(), ex);
                jobRepository.finishTask(task.id(), TaskStatus.FAILED, truncate(ex.getMessage()), false);
            }
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
