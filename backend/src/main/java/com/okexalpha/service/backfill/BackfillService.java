package com.okexalpha.service.backfill;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.JobStatus;
import com.okexalpha.error.ConflictException;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.repository.BackfillJobRepository;
import com.okexalpha.repository.BackfillTaskSpec;

/** 补拉编排：规划任务 → 落库 → 异步执行；提供 pause/resume/cancel。 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    private final BackfillPlanner planner;
    private final BackfillJobRepository jobRepository;
    private final BackfillRunner runner;
    private final TaskExecutor executor;
    private final ObjectMapper objectMapper;

    public BackfillService(BackfillPlanner planner, BackfillJobRepository jobRepository, BackfillRunner runner,
            @Qualifier("backfillExecutor") TaskExecutor executor, ObjectMapper objectMapper) {
        this.planner = planner;
        this.jobRepository = jobRepository;
        this.runner = runner;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    public record JobSubmission(long jobId, int taskTotal) {
    }

    public JobSubmission submitKlines(KlineBackfillRequest request) {
        return submit(DataType.KLINE, request, planner.planKlines(request));
    }

    public JobSubmission submitFundingRate(FundingBackfillRequest request) {
        return submit(DataType.FUNDING_RATE, request, planner.planFundingRate(request));
    }

    public JobSubmission submitRatios(RatioBackfillRequest request) {
        return submit(DataType.LONG_SHORT_RATIO, request, planner.planRatios(request));
    }

    public JobSubmission submitMarketStats(MarketStatsBackfillRequest request) {
        return submit(DataType.OPEN_INTEREST_VOLUME, request, planner.planMarketStats(request));
    }

    private JobSubmission submit(DataType dataType, Object request, List<BackfillTaskSpec> planned) {
        List<BackfillTaskSpec> fresh = planned.stream()
                .filter(spec -> !jobRepository.existsActiveTask(dataType, spec.taskKey()))
                .toList();
        if (fresh.isEmpty()) {
            throw new ConflictException("all " + planned.size() + " task(s) are already pending or running");
        }
        long jobId = jobRepository.createJob(dataType, toJson(request), fresh);
        dispatch(jobId);
        log.info("submitted job {} dataType={} tasks={} (skipped {} active)", jobId, dataType, fresh.size(),
                planned.size() - fresh.size());
        return new JobSubmission(jobId, fresh.size());
    }

    public void dispatch(long jobId) {
        executor.execute(() -> {
            try {
                runner.runJob(jobId);
            } catch (RuntimeException ex) {
                log.error("unexpected failure running job {}", jobId, ex);
            }
        });
    }

    public BackfillJobRow pause(long jobId) {
        BackfillJobRow job = requireJob(jobId);
        String status = job.status();
        if (!JobStatus.PENDING.name().equals(status) && !JobStatus.RUNNING.name().equals(status)) {
            throw new ConflictException("only PENDING or RUNNING jobs can be paused, job " + jobId + " is " + status);
        }
        jobRepository.setJobStatus(jobId, JobStatus.PAUSED, null);
        return jobRepository.findJob(jobId);
    }

    public BackfillJobRow resume(long jobId) {
        BackfillJobRow job = requireJob(jobId);
        String status = job.status();
        if (!JobStatus.PAUSED.name().equals(status) && !JobStatus.FAILED.name().equals(status)) {
            throw new ConflictException("only PAUSED or FAILED jobs can be resumed, job " + jobId + " is " + status);
        }
        jobRepository.resetFailedTasks(jobId);
        jobRepository.setJobStatus(jobId, JobStatus.PENDING, null);
        jobRepository.refreshCounters(jobId);
        dispatch(jobId);
        return jobRepository.findJob(jobId);
    }

    public BackfillJobRow cancel(long jobId) {
        BackfillJobRow job = requireJob(jobId);
        if (JobStatus.DONE.name().equals(job.status()) || JobStatus.CANCELED.name().equals(job.status())) {
            throw new ConflictException("job " + jobId + " already finished with status " + job.status());
        }
        jobRepository.setJobStatus(jobId, JobStatus.CANCELED, null);
        return jobRepository.findJob(jobId);
    }

    public BackfillJobRow getJob(long jobId) {
        return requireJob(jobId);
    }

    public List<BackfillJobRow> listJobs(String dataType, String status, int limit, int offset) {
        return jobRepository.findJobs(dataType, status, limit, offset);
    }

    public List<BackfillTaskRowView> listTasks(long jobId) {
        requireJob(jobId);
        return jobRepository.findTasks(jobId).stream()
                .map(task -> new BackfillTaskRowView(task.id(), task.taskKey(), task.status(), task.cursorMs(),
                        task.pageCount(), task.rowsWritten(), task.attempts(), task.truncated(), task.lastError()))
                .toList();
    }

    public record BackfillTaskRowView(long id, String taskKey, String status, Long cursorMs, int pageCount,
            long rowsWritten, int attempts, boolean truncated, String lastError) {
    }

    private BackfillJobRow requireJob(long jobId) {
        BackfillJobRow job = jobRepository.findJob(jobId);
        if (job == null) {
            throw new NotFoundException("backfill job not found: " + jobId);
        }
        return job;
    }

    private String toJson(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot serialize request", ex);
        }
    }
}
