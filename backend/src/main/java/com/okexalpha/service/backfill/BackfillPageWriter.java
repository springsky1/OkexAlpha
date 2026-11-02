package com.okexalpha.service.backfill;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.repository.BackfillJobRepository;

/**
 * 每批"数据 + 游标"写入同一个事务，保证崩溃后可无损续跑（最多回到上个提交点）。
 *
 * <p>okx.db.relax-sync-commit=true 时在事务内 <code>SET LOCAL synchronous_commit = off</code>：
 * 提交不再等 WAL fsync，批量补拉吞吐明显提升；断电最多丢最后几页，重跑幂等。
 */
@Component
public class BackfillPageWriter {

    private final BackfillJobRepository jobRepository;
    private final JdbcTemplate jdbcTemplate;
    private final boolean relaxSyncCommit;

    public BackfillPageWriter(BackfillJobRepository jobRepository, JdbcTemplate jdbcTemplate,
            OkxProperties properties) {
        this.jobRepository = jobRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.relaxSyncCommit = properties.db().relaxSyncCommit();
    }

    @Transactional
    public void write(long taskId, Runnable dataWriter, Long cursorMs, int pageCount, long rowsWritten,
            boolean truncated) {
        if (relaxSyncCommit) {
            jdbcTemplate.execute("SET LOCAL synchronous_commit = off");
        }
        dataWriter.run();
        jobRepository.updateTaskProgress(taskId, cursorMs, pageCount, rowsWritten, truncated);
    }
}
