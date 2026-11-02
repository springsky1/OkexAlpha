package com.okexalpha.service.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.okexalpha.config.OkxProperties;

/** PageBatcher：每 N 页合并一次事务提交，flush 幂等，游标取最后一批的页。 */
class PageBatcherTest {

    private BackfillPageWriter pageWriter;
    private final List<Long> cursors = new ArrayList<>();
    private final List<Integer> pageCounts = new ArrayList<>();
    private final List<Long> rowCounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pageWriter = mock(BackfillPageWriter.class);
        doAnswer(invocation -> {
            cursors.add(invocation.getArgument(2));
            pageCounts.add(invocation.getArgument(3));
            rowCounts.add(invocation.getArgument(4));
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(pageWriter).write(anyLong(), any(Runnable.class), any(), anyInt(), anyLong(), anyBoolean());
    }

    private static OkxProperties withCommitEveryPages(int pages) {
        OkxProperties base = OkxProperties.defaults();
        return new OkxProperties(base.baseUrl(), base.connectTimeoutMs(), base.readTimeoutMs(), base.userAgent(),
                base.retry(), base.rateLimit(), base.backfill(), base.sync(), base.schedule(), base.apiLog(),
                new OkxProperties.Db(true, true, true, pages), base.strategy());
    }

    @Test
    void commitsEveryNPagesAndFlushesTheRestOnDemand() {
        PageBatcher batcher = new PageBatcher(pageWriter, withCommitEveryPages(2));
        PageBatcher.Batch batch = batcher.batch(42L);

        batch.add(() -> {
        }, 30L, 1, 10L, false);
        verify(pageWriter, never()).write(anyLong(), any(Runnable.class), any(), anyInt(), anyLong(), anyBoolean());
        assertThat(batch.pendingPages()).isEqualTo(1);

        batch.add(() -> {
        }, 20L, 2, 20L, false);
        assertThat(batch.commits()).isEqualTo(1);

        batch.add(() -> {
        }, 10L, 3, 30L, false);
        batch.flush();
        batch.flush();

        assertThat(batch.commits()).isEqualTo(2);
        assertThat(batch.pendingPages()).isZero();
        assertThat(cursors).containsExactly(20L, 10L);
        assertThat(pageCounts).containsExactly(2, 3);
        assertThat(rowCounts).containsExactly(20L, 30L);
    }

    @Test
    void runsEveryQueuedPageWriterInOneFlush() {
        PageBatcher batcher = new PageBatcher(pageWriter, withCommitEveryPages(3));
        PageBatcher.Batch batch = batcher.batch(7L);
        AtomicInteger writes = new AtomicInteger();

        batch.add(writes::incrementAndGet, 300L, 1, 5L, false);
        batch.add(writes::incrementAndGet, 200L, 2, 10L, false);
        batch.add(writes::incrementAndGet, 100L, 3, 15L, true);

        assertThat(writes.get()).isEqualTo(3);
        verify(pageWriter, times(1)).write(eq(7L), any(Runnable.class), eq(100L), eq(3), eq(15L), eq(true));
    }

    @Test
    void flushWithoutPendingPagesIsNoop() {
        PageBatcher batcher = new PageBatcher(pageWriter, withCommitEveryPages(5));
        PageBatcher.Batch batch = batcher.batch(1L);

        batch.flush();

        assertThat(batch.commits()).isZero();
        verify(pageWriter, never()).write(anyLong(), any(Runnable.class), any(), anyInt(), anyLong(), anyBoolean());
    }

    @Test
    void commitEveryPagesBelowOneIsClampedToOne() {
        PageBatcher zero = new PageBatcher(pageWriter, withCommitEveryPages(0));
        PageBatcher negative = new PageBatcher(pageWriter, withCommitEveryPages(-3));

        assertThat(zero.everyPages()).isEqualTo(1);
        assertThat(negative.everyPages()).isEqualTo(1);

        PageBatcher.Batch batch = zero.batch(1L);
        batch.add(() -> {
        }, 5L, 1, 1L, false);
        assertThat(batch.commits()).isEqualTo(1);
        verify(pageWriter, times(1)).write(eq(1L), any(Runnable.class), eq(5L), eq(1), eq(1L), eq(false));
    }

    @Test
    void defaultsUseFivePagesPerCommit() {
        PageBatcher batcher = new PageBatcher(pageWriter, OkxProperties.defaults());

        assertThat(batcher.everyPages()).isEqualTo(5);
    }
}
