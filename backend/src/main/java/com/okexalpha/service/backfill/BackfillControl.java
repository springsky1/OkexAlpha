package com.okexalpha.service.backfill;

import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.repository.BackfillJobRepository;

/** task 执行期间的控制面：pause/cancel 在页边界生效。 */
public class BackfillControl {

    private final long jobId;
    private final BackfillJobRepository jobRepository;

    public BackfillControl(long jobId, BackfillJobRepository jobRepository) {
        this.jobId = jobId;
        this.jobRepository = jobRepository;
    }

    public long jobId() {
        return jobId;
    }

    public boolean shouldStop() {
        BackfillJobRow job = jobRepository.findJob(jobId);
        if (job == null) {
            return true;
        }
        String status = job.status();
        return "PAUSED".equals(status) || "CANCELED".equals(status) || "FAILED".equals(status);
    }
}
