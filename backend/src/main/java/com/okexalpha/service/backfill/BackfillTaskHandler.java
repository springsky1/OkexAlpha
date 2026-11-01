package com.okexalpha.service.backfill;

import java.util.List;

import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;

/** 一类或几类补拉任务的分页执行器。 */
public interface BackfillTaskHandler {

    List<DataType> dataTypes();

    TaskOutcome execute(BackfillTaskRow task, BackfillControl control);
}
