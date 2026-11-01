package com.okexalpha.service.backfill;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.okexalpha.client.OkxApiClient;
import com.okexalpha.client.model.CandleParser;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.Bar;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.KlineRow;
import com.okexalpha.domain.KlineType;
import com.okexalpha.repository.KlineRepository;

/** 按 after 游标向前翻页补拉 K 线（CANDLE / MARK，均为永续合约 SWAP）。 */
@Component
public class KlineTaskHandler implements BackfillTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(KlineTaskHandler.class);
    private static final int MAX_API_LIMIT = 300;

    private final OkxApiClient client;
    private final OkxProperties properties;
    private final KlineRepository klineRepository;
    private final BackfillPageWriter pageWriter;
    private final ObjectMapper objectMapper;

    public KlineTaskHandler(OkxApiClient client, OkxProperties properties, KlineRepository klineRepository,
            BackfillPageWriter pageWriter, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.klineRepository = klineRepository;
        this.pageWriter = pageWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DataType> dataTypes() {
        return List.of(DataType.KLINE);
    }

    @Override
    public TaskOutcome execute(BackfillTaskRow task, BackfillControl control) {
        TaskParams params = parse(task);
        KlineType klineType = KlineType.fromCode(params.klineType());
        Bar bar = Bar.fromCode(params.bar());
        long startMs = params.startMs();
        long endMs = params.endMs();
        int limit = params.pageLimit() != null ? params.pageLimit() : properties.backfill().pageLimit();
        limit = Math.max(1, Math.min(limit, MAX_API_LIMIT));

        long cursor = task.cursorMs() != null ? task.cursorMs() : endMs;
        int pageCount = task.pageCount();
        long rowsWritten = task.rowsWritten();

        while (true) {
            if (control.shouldStop()) {
                log.info("task {} stopped at cursor {}", task.taskKey(), cursor);
                return new TaskOutcome(rowsWritten, false, "stopped");
            }
            List<JsonNode> data = client.getCandles(klineType, params.instId(), bar.code(), cursor, null, limit);
            List<KlineRow> page = CandleParser.parseAll(data, klineType, params.instId(), params.instType(),
                    bar.code()).stream()
                    .filter(row -> row.tsMs() >= startMs && row.tsMs() <= endMs)
                    .toList();
            if (page.isEmpty()) {
                break;
            }
            long minTs = page.stream().mapToLong(KlineRow::tsMs).min().orElse(cursor);
            long newCursor = minTs >= cursor ? cursor - 1 : minTs;
            pageCount++;
            rowsWritten += page.size();
            final List<KlineRow> rows = page;
            pageWriter.write(task.id(), () -> klineRepository.upsertAll(rows), newCursor, pageCount, rowsWritten,
                    false);
            cursor = newCursor;
            if (cursor <= startMs) {
                break;
            }
        }
        return new TaskOutcome(rowsWritten, false, null);
    }

    private TaskParams parse(BackfillTaskRow task) {
        try {
            return objectMapper.readValue(task.params(), TaskParams.class);
        } catch (Exception ex) {
            throw new IllegalStateException("bad task params: " + task.taskKey(), ex);
        }
    }
}
