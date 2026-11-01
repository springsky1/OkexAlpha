package com.okexalpha.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.JobStatus;
import com.okexalpha.domain.TaskStatus;

/** okx_backfill_job / okx_backfill_task 的读写。 */
@Repository
public class BackfillJobRepository {

    private static final String JOB_INSERT = """
            INSERT INTO okx_backfill_job (data_type, request, status, task_total, created_at, updated_at)
            VALUES (?, ?::jsonb, 'PENDING', ?, now(), now())
            RETURNING id
            """;

    private static final String TASK_INSERT = """
            INSERT INTO okx_backfill_task (job_id, task_key, params, status, cursor_ms, updated_at)
            VALUES (?, ?, ?::jsonb, 'PENDING', ?, now())
            ON CONFLICT (job_id, task_key) DO NOTHING
            """;

    private static final String JOB_COLUMNS = """
            id, data_type, request::text AS request, status, task_total, task_done, task_failed,
            rows_written, error, created_at, started_at, finished_at
            """;

    private static final String TASK_COLUMNS = """
            id, job_id, task_key, params::text AS params, status, cursor_ms, page_count, rows_written,
            attempts, truncated, last_error, started_at, finished_at
            """;

    private static final RowMapper<BackfillJobRow> JOB_MAPPER = (ResultSet rs, int rowNum) -> new BackfillJobRow(
            rs.getLong("id"),
            rs.getString("data_type"),
            rs.getString("request"),
            rs.getString("status"),
            rs.getInt("task_total"),
            rs.getInt("task_done"),
            rs.getInt("task_failed"),
            rs.getLong("rows_written"),
            rs.getString("error"),
            offset(rs, "created_at"),
            offset(rs, "started_at"),
            offset(rs, "finished_at"));

    private static final RowMapper<BackfillTaskRow> TASK_MAPPER = (ResultSet rs, int rowNum) -> new BackfillTaskRow(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getString("task_key"),
            rs.getString("params"),
            rs.getString("status"),
            rs.getObject("cursor_ms") == null ? null : rs.getLong("cursor_ms"),
            rs.getInt("page_count"),
            rs.getLong("rows_written"),
            rs.getInt("attempts"),
            rs.getBoolean("truncated"),
            rs.getString("last_error"),
            offset(rs, "started_at"),
            offset(rs, "finished_at"));

    private static OffsetDateTime offset(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private final JdbcTemplate jdbcTemplate;

    public BackfillJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ job

    public long createJob(DataType dataType, String requestJson, List<BackfillTaskSpec> tasks) {
        Long id = jdbcTemplate.queryForObject(JOB_INSERT, Long.class,
                dataType.name(), requestJson, tasks.size());
        if (id == null) {
            throw new IllegalStateException("cannot create backfill job");
        }
        if (!tasks.isEmpty()) {
            List<Object[]> batch = new ArrayList<>(tasks.size());
            for (BackfillTaskSpec spec : tasks) {
                batch.add(new Object[] { id, spec.taskKey(), spec.paramsJson(),
                        spec.endMs() != null ? spec.endMs() : spec.startMs() });
            }
            jdbcTemplate.batchUpdate(TASK_INSERT, batch);
        }
        refreshCounters(id);
        return id;
    }

    public BackfillJobRow findJob(long id) {
        List<BackfillJobRow> rows = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM okx_backfill_job WHERE id = ?", JOB_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<BackfillJobRow> findJobs(String dataType, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + JOB_COLUMNS + " FROM okx_backfill_job WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (dataType != null && !dataType.isBlank()) {
            sql.append(" AND data_type = ?");
            args.add(dataType);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), JOB_MAPPER, args.toArray());
    }

    public int setJobStatus(long jobId, JobStatus status, String error) {
        String sql = switch (status) {
            case RUNNING -> "UPDATE okx_backfill_job SET status = ?, started_at = coalesce(started_at, now()), error = ?, updated_at = now() WHERE id = ?";
            case DONE, FAILED, CANCELED -> "UPDATE okx_backfill_job SET status = ?, finished_at = now(), error = ?, updated_at = now() WHERE id = ?";
            default -> "UPDATE okx_backfill_job SET status = ?, error = ?, updated_at = now() WHERE id = ?";
        };
        // error 取值即真相：DONE 传 null 时必须清空历史错误，否则成功作业会残留 "N task(s) failed"。
        return jdbcTemplate.update(sql, status.name(), error, jobId);
    }

    /** 仅当 job 仍是 PENDING 时置为 RUNNING（多线程安全）。 */
    public boolean claimJob(long jobId) {
        return jdbcTemplate.update("""
                UPDATE okx_backfill_job SET status = 'RUNNING', started_at = coalesce(started_at, now()), updated_at = now()
                 WHERE id = ? AND status = 'PENDING'
                """, jobId) > 0;
    }

    /** 启动恢复：中断的 RUNNING job -> PAUSED，RUNNING task -> PENDING。 */
    public int markInterrupted() {
        return jdbcTemplate.update("""
                UPDATE okx_backfill_job SET status = 'PAUSED', error = 'interrupted by restart', updated_at = now()
                 WHERE status = 'RUNNING'
                """);
    }

    public int resetRunningTasks() {
        return jdbcTemplate.update("""
                UPDATE okx_backfill_task SET status = 'PENDING', updated_at = now() WHERE status = 'RUNNING'
                """);
    }

    /** 因重启中断而暂停的 job（供 auto-resume 使用）。 */
    public List<Long> findInterruptedJobIds() {
        return jdbcTemplate.queryForList("""
                SELECT id FROM okx_backfill_job
                 WHERE status = 'PAUSED' AND error = 'interrupted by restart'
                 ORDER BY id
                """, Long.class);
    }

    public List<Long> findJobIds(JobStatus status) {
        return jdbcTemplate.queryForList("SELECT id FROM okx_backfill_job WHERE status = ? ORDER BY id", Long.class,
                status.name());
    }

    public int resetFailedTasks(long jobId) {
        return jdbcTemplate.update("""
                UPDATE okx_backfill_task SET status = 'PENDING', last_error = null, updated_at = now()
                 WHERE job_id = ? AND status = 'FAILED'
                """, jobId);
    }

    public int countTasks(long jobId, TaskStatus status) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM okx_backfill_task WHERE job_id = ? AND status = ?", Integer.class,
                jobId, status.name());
        return value == null ? 0 : value;
    }

    public void refreshCounters(long jobId) {
        jdbcTemplate.update("""
                UPDATE okx_backfill_job j SET
                    task_total = (SELECT count(*) FROM okx_backfill_task t WHERE t.job_id = j.id),
                    task_done = (SELECT count(*) FROM okx_backfill_task t WHERE t.job_id = j.id AND t.status = 'DONE'),
                    task_failed = (SELECT count(*) FROM okx_backfill_task t WHERE t.job_id = j.id AND t.status = 'FAILED'),
                    rows_written = (SELECT coalesce(sum(t.rows_written), 0) FROM okx_backfill_task t WHERE t.job_id = j.id),
                    updated_at = now()
                 WHERE j.id = ?
                """, jobId);
    }

    // ------------------------------------------------------------------ task

    public BackfillTaskRow findTask(long taskId) {
        List<BackfillTaskRow> rows = jdbcTemplate.query(
                "SELECT " + TASK_COLUMNS + " FROM okx_backfill_task WHERE id = ?", TASK_MAPPER, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<BackfillTaskRow> findTasks(long jobId) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM okx_backfill_task WHERE job_id = ? ORDER BY id",
                TASK_MAPPER, jobId);
    }

    public List<BackfillTaskRow> findTasks(long jobId, TaskStatus status) {
        return jdbcTemplate.query(
                "SELECT " + TASK_COLUMNS + " FROM okx_backfill_task WHERE job_id = ? AND status = ? ORDER BY id",
                TASK_MAPPER, jobId, status.name());
    }

    public boolean claimTask(long taskId) {
        return jdbcTemplate.update("""
                UPDATE okx_backfill_task SET status = 'RUNNING', started_at = coalesce(started_at, now()),
                       attempts = attempts + 1, updated_at = now()
                 WHERE id = ? AND status = 'PENDING'
                """, taskId) > 0;
    }

    public int updateTaskProgress(long taskId, Long cursorMs, int pageCount, long rowsWritten, boolean truncated) {
        return jdbcTemplate.update("""
                UPDATE okx_backfill_task SET cursor_ms = ?, page_count = ?, rows_written = ?,
                       truncated = ?, updated_at = now()
                 WHERE id = ?
                """, cursorMs, pageCount, rowsWritten, truncated, taskId);
    }

    public int finishTask(long taskId, TaskStatus status, String error, boolean truncated) {
        return jdbcTemplate.update("""
                UPDATE okx_backfill_task SET status = ?, last_error = ?, truncated = ?,
                       finished_at = coalesce(finished_at, now()), updated_at = now()
                 WHERE id = ?
                """, status.name(), error, truncated, taskId);
    }

    public int releaseTask(long taskId) {
        return jdbcTemplate.update("""
                UPDATE okx_backfill_task SET status = 'PENDING', updated_at = now() WHERE id = ?
                """, taskId);
    }

    /** 同一 data_type + task_key 是否还有未执行完的任务（用于去重）；已 DONE/FAILED 的历史任务不算。 */
    public boolean existsActiveTask(DataType dataType, String taskKey) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM okx_backfill_task t
                  JOIN okx_backfill_job j ON j.id = t.job_id
                 WHERE j.data_type = ? AND t.task_key = ?
                   AND j.status IN ('PENDING', 'RUNNING', 'PAUSED')
                   AND t.status IN ('PENDING', 'RUNNING')
                """, Integer.class, dataType.name(), taskKey);
        return count != null && count > 0;
    }

    public List<java.util.Map<String, Object>> statusSummary() {
        return jdbcTemplate.queryForList("""
                SELECT data_type, status, count(*) AS jobs FROM okx_backfill_job GROUP BY 1, 2 ORDER BY 1, 2
                """);
    }
}
