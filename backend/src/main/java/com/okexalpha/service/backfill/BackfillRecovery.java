package com.okexalpha.service.backfill;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.JobStatus;
import com.okexalpha.repository.BackfillJobRepository;

/** 重启恢复：把中断的 RUNNING job 置 PAUSED、task 置 PENDING；auto-resume 时自动重排。 */
@Component
@Order(100)
public class BackfillRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRecovery.class);

    private final BackfillJobRepository jobRepository;
    private final BackfillService backfillService;
    private final OkxProperties properties;

    public BackfillRecovery(BackfillJobRepository jobRepository, BackfillService backfillService,
            OkxProperties properties) {
        this.jobRepository = jobRepository;
        this.backfillService = backfillService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        int interrupted = jobRepository.markInterrupted();
        int resetTasks = jobRepository.resetRunningTasks();
        if (interrupted == 0 && resetTasks == 0) {
            return;
        }
        log.warn("recovered from restart: {} job(s) paused, {} task(s) reset to PENDING", interrupted, resetTasks);
        if (!properties.backfill().autoResume()) {
            return;
        }
        List<Long> resumable = jobRepository.findInterruptedJobIds();
        for (Long jobId : resumable) {
            jobRepository.setJobStatus(jobId, JobStatus.PENDING, null);
            jobRepository.refreshCounters(jobId);
            backfillService.dispatch(jobId);
            log.info("auto-resumed job {}", jobId);
        }
    }
}
