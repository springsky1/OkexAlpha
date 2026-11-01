package com.okexalpha.service.backfill;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.JobStatus;
import com.okexalpha.domain.TaskStatus;
import com.okexalpha.repository.BackfillJobRepository;

/** 单个 job 的执行循环：领取 task → 执行 → 记结果，页边界响应 pause/cancel。 */
@Component
public class BackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);
    private static final int MAX_ATTEMPTS = 3;

    private final BackfillJobRepository jobRepository;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final Map<DataType, BackfillTaskHandler> handlers = new EnumMap<>(DataType.class);

    public BackfillRunner(BackfillJobRepository jobRepository,
            tools.jackson.databind.ObjectMapper objectMapper, List<BackfillTaskHandler> handlerList) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        for (BackfillTaskHandler handler : handlerList) {
            for (DataType type : handler.dataTypes()) {
                handlers.put(type, handler);
            }
        }
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
                boolean stopped = false;
                for (BackfillTaskRow task : pending) {
                    if (control.shouldStop()) {
                        stopped = true;
                        break;
                    }
                    if (!jobRepository.claimTask(task.id())) {
                        continue;
                    }
                    BackfillTaskRow claimed = jobRepository.findTask(task.id());
                    if (claimed == null) {
                        continue;
                    }
                    executeOne(taskDataType(claimed, job.dataType()), claimed, control);
                    jobRepository.refreshCounters(jobId);
                }
                if (stopped) {
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
        BackfillTaskHandler handler = handlers.get(DataType.valueOf(dataType));
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
