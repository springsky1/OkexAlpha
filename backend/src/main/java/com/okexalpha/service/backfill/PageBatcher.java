package com.okexalpha.service.backfill;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.okexalpha.config.OkxProperties;

/**
 * 把连续多页数据合并成一个事务提交（okx.db.commit-every-pages，默认 5）。
 *
 * <p>补拉是"每页 300 行 upsert + 更新游标"的形态，逐页提交在 8 线程并发时会产生大量
 * WAL fsync 与提交开销；合并 5 页后提交次数降到 1/5。代价是崩溃时最多回到上一次提交点，
 * 丢掉最近几页的游标进度——写入是幂等 upsert，重跑结果一致，所以可以安全接受。
 *
 * <p>每个 task 用一个独立的 {@link Batch}（内部持有待提交数据，不共享给其它线程）。
 */
@Component
public class PageBatcher {

    private final BackfillPageWriter pageWriter;
    private final int everyPages;

    public PageBatcher(BackfillPageWriter pageWriter, OkxProperties properties) {
        this.pageWriter = pageWriter;
        this.everyPages = Math.max(1, properties.db().commitEveryPages());
    }

    public int everyPages() {
        return everyPages;
    }

    public Batch batch(long taskId) {
        return new Batch(taskId);
    }

    /** 单个 task 的提交缓冲；用完必须调用 {@link Batch#flush()}。 */
    public final class Batch {

        private final long taskId;
        private final List<Runnable> pendingWriters = new ArrayList<>();
        private Long cursorMs;
        private int pageCount;
        private long rowsWritten;
        private boolean truncated;
        private int pendingPages;
        private int commits;

        private Batch(long taskId) {
            this.taskId = taskId;
        }

        /** 缓存一页；达到 commit-every-pages 时立即提交。 */
        public void add(Runnable dataWriter, Long nextCursor, int pageCount, long rowsWritten, boolean truncated) {
            pendingWriters.add(dataWriter);
            this.cursorMs = nextCursor;
            this.pageCount = pageCount;
            this.rowsWritten = rowsWritten;
            this.truncated = truncated;
            pendingPages++;
            if (pendingPages >= everyPages) {
                flush();
            }
        }

        /** 提交尚未落库的页（幂等，空缓冲时是 no-op）。 */
        public void flush() {
            if (pendingPages == 0) {
                return;
            }
            List<Runnable> writers = List.copyOf(pendingWriters);
            pageWriter.write(taskId, () -> writers.forEach(Runnable::run), cursorMs, pageCount, rowsWritten,
                    truncated);
            pendingWriters.clear();
            pendingPages = 0;
            commits++;
        }

        public int commits() {
            return commits;
        }

        public int pendingPages() {
            return pendingPages;
        }
    }
}
