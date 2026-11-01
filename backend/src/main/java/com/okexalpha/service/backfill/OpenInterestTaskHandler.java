package com.okexalpha.service.backfill;

import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.okexalpha.client.OkxApiClient;
import com.okexalpha.client.model.RubikParser;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.OpenInterestRow;
import com.okexalpha.repository.StatisticsRepository;

/** 持仓量快照（OKX 只提供当前值，每跑一次落一行）。 */
@Component
public class OpenInterestTaskHandler implements BackfillTaskHandler {

    private final OkxApiClient client;
    private final StatisticsRepository statisticsRepository;
    private final BackfillPageWriter pageWriter;
    private final ObjectMapper objectMapper;

    public OpenInterestTaskHandler(OkxApiClient client, StatisticsRepository statisticsRepository,
            BackfillPageWriter pageWriter, ObjectMapper objectMapper) {
        this.client = client;
        this.statisticsRepository = statisticsRepository;
        this.pageWriter = pageWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DataType> dataTypes() {
        return List.of(DataType.OPEN_INTEREST);
    }

    @Override
    public TaskOutcome execute(BackfillTaskRow task, BackfillControl control) {
        if (control.shouldStop()) {
            return new TaskOutcome(0L, false, "stopped");
        }
        TaskParams params;
        try {
            params = objectMapper.readValue(task.params(), TaskParams.class);
        } catch (Exception ex) {
            throw new IllegalStateException("bad task params: " + task.taskKey(), ex);
        }
        List<JsonNode> data = client.getOpenInterest(params.instType(), params.instId());
        List<OpenInterestRow> rows = RubikParser.parseOpenInterest(data, params.instId(), params.instType());
        if (rows.isEmpty()) {
            return new TaskOutcome(0L, false, "empty response");
        }
        pageWriter.write(task.id(), () -> statisticsRepository.upsertOpenInterest(rows), null, 1, rows.size(),
                false);
        return new TaskOutcome(rows.size(), false, null);
    }
}
