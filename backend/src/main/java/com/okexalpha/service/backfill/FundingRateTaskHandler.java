package com.okexalpha.service.backfill;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import com.okexalpha.client.OkxApiClient;
import com.okexalpha.client.model.FundingRateMapper;
import com.okexalpha.client.model.OkxFundingRate;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.FundingRateRow;
import com.okexalpha.repository.FundingRateRepository;

/** 资金费率历史补拉：空页即到达 OKX 保留边界（约 96 天），标记 truncated。 */
@Component
public class FundingRateTaskHandler implements BackfillTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(FundingRateTaskHandler.class);
    private static final int PAGE = 100;

    private final OkxApiClient client;
    private final FundingRateRepository fundingRateRepository;
    private final BackfillPageWriter pageWriter;
    private final ObjectMapper objectMapper;

    public FundingRateTaskHandler(OkxApiClient client, FundingRateRepository fundingRateRepository,
            BackfillPageWriter pageWriter, ObjectMapper objectMapper) {
        this.client = client;
        this.fundingRateRepository = fundingRateRepository;
        this.pageWriter = pageWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DataType> dataTypes() {
        return List.of(DataType.FUNDING_RATE);
    }

    @Override
    public TaskOutcome execute(BackfillTaskRow task, BackfillControl control) {
        TaskParams params = parse(task);
        long startMs = params.startMs();
        long endMs = params.endMs();
        long cursor = task.cursorMs() != null ? task.cursorMs() : endMs;
        int pageCount = task.pageCount();
        long rowsWritten = task.rowsWritten();
        boolean truncated = task.truncated();

        while (true) {
            if (control.shouldStop()) {
                return new TaskOutcome(rowsWritten, truncated, "stopped");
            }
            List<OkxFundingRate> data = client.getFundingRateHistory(params.instId(), cursor, null, PAGE);
            List<FundingRateRow> page = FundingRateMapper.toRows(data, params.instType()).stream()
                    .filter(row -> row.fundingTimeMs() >= startMs && row.fundingTimeMs() <= endMs)
                    .toList();
            if (page.isEmpty()) {
                truncated = true;
                pageWriter.write(task.id(), () -> {
                }, cursor, pageCount, rowsWritten, true);
                log.info("task {} reached retention boundary near {}", task.taskKey(), cursor);
                break;
            }
            long minTs = page.stream().mapToLong(FundingRateRow::fundingTimeMs).min().orElse(cursor);
            long newCursor = minTs >= cursor ? cursor - 1 : minTs;
            pageCount++;
            rowsWritten += page.size();
            final List<FundingRateRow> rows = page;
            pageWriter.write(task.id(), () -> fundingRateRepository.upsertAll(rows), newCursor, pageCount,
                    rowsWritten, false);
            cursor = newCursor;
            if (cursor <= startMs) {
                break;
            }
        }
        return new TaskOutcome(rowsWritten, truncated, null);
    }

    private TaskParams parse(BackfillTaskRow task) {
        try {
            return objectMapper.readValue(task.params(), TaskParams.class);
        } catch (Exception ex) {
            throw new IllegalStateException("bad task params: " + task.taskKey(), ex);
        }
    }
}
