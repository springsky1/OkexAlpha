package com.okexalpha.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.repository.BackfillJobRepository;
import com.okexalpha.service.backfill.BackfillControl;

/** 补拉任务执行器的测试夹具：始终 RUNNING 的作业控制面 + 任务行。 */
public final class BackfillControls {

    private BackfillControls() {
    }

    /** job 状态固定 RUNNING，shouldStop() 恒为 false。 */
    public static BackfillControl running(long jobId) {
        BackfillJobRepository jobRepository = mock(BackfillJobRepository.class);
        when(jobRepository.findJob(jobId)).thenReturn(new BackfillJobRow(
                jobId, "KLINE", "{}", "RUNNING", 1, 0, 0, 0L, null, null, null, null));
        return new BackfillControl(jobId, jobRepository);
    }

    /** 状态固定 PAUSED，shouldStop() 恒为 true。 */
    public static BackfillControl paused(long jobId) {
        BackfillJobRepository jobRepository = mock(BackfillJobRepository.class);
        when(jobRepository.findJob(jobId)).thenReturn(new BackfillJobRow(
                jobId, "KLINE", "{}", "PAUSED", 1, 0, 0, 0L, null, null, null, null));
        return new BackfillControl(jobId, jobRepository);
    }

    public static BackfillTaskRow task(long id, long jobId, String taskKey, String paramsJson) {
        return new BackfillTaskRow(id, jobId, taskKey, paramsJson, "RUNNING", null, 0, 0L, 1, false,
                null, null, null);
    }
}
