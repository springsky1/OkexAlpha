package com.okexalpha.service.backfill;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.okexalpha.repository.BackfillJobRepository;

/** 每页"数据 + 游标"写入同一个事务，保证崩溃后可无损续跑。 */
@Component
public class BackfillPageWriter {

    private final BackfillJobRepository jobRepository;

    public BackfillPageWriter(BackfillJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void write(long taskId, Runnable dataWriter, Long cursorMs, int pageCount, long rowsWritten,
            boolean truncated) {
        dataWriter.run();
        jobRepository.updateTaskProgress(taskId, cursorMs, pageCount, rowsWritten, truncated);
    }
}
