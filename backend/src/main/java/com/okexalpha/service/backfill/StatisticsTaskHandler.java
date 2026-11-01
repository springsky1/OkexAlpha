package com.okexalpha.service.backfill;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.okexalpha.client.OkxApiClient;
import com.okexalpha.client.OkxApiException;
import com.okexalpha.client.model.RubikParser;
import com.okexalpha.domain.BackfillTaskRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.OpenInterestVolumeRow;
import com.okexalpha.domain.Period;
import com.okexalpha.domain.RatioRow;
import com.okexalpha.domain.RatioType;
import com.okexalpha.domain.TakerVolumeRow;
import com.okexalpha.repository.StatisticsRepository;

/** rubik 统计补拉：按 period 固定窗口切分，超出保留期则截断并从保留边界起算。 */
@Component
public class StatisticsTaskHandler implements BackfillTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(StatisticsTaskHandler.class);

    private final OkxApiClient client;
    private final StatisticsRepository statisticsRepository;
    private final BackfillPageWriter pageWriter;
    private final ObjectMapper objectMapper;

    public StatisticsTaskHandler(OkxApiClient client, StatisticsRepository statisticsRepository,
            BackfillPageWriter pageWriter, ObjectMapper objectMapper) {
        this.client = client;
        this.statisticsRepository = statisticsRepository;
        this.pageWriter = pageWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DataType> dataTypes() {
        return List.of(DataType.LONG_SHORT_RATIO, DataType.OPEN_INTEREST_VOLUME, DataType.TAKER_VOLUME);
    }

    @Override
    public TaskOutcome execute(BackfillTaskRow task, BackfillControl control) {
        TaskParams params = parse(task);
        DataType dataType = DataType.valueOf(params.dataType());
        Period period = Period.fromCode(params.period());
        long requestedStart = params.startMs();
        long endMs = params.endMs();
        long retentionStart = System.currentTimeMillis() - period.retentionMs();
        long startMs = Math.max(requestedStart, retentionStart);
        boolean truncated = requestedStart < retentionStart;
        if (startMs >= endMs) {
            return new TaskOutcome(0L, true, "requested range is older than retention window");
        }

        // 统计类从旧到新推进，因此首次执行必须从 startMs 开始（不能沿用任务行里预置的 endMs 游标）。
        long cursor = task.pageCount() > 0 && task.cursorMs() != null
                ? Math.max(task.cursorMs(), startMs)
                : startMs;
        int pageCount = task.pageCount();
        long rowsWritten = task.rowsWritten();

        while (cursor < endMs) {
            if (control.shouldStop()) {
                return new TaskOutcome(rowsWritten, truncated, "stopped");
            }
            long windowEnd = Math.min(cursor + period.windowMs(), endMs);
            final long from = cursor;
            final long to = windowEnd;
            int[] written = new int[1];
            Runnable persist = switch (dataType) {
                case LONG_SHORT_RATIO -> persistRatios(task, params, period, from, to, written);
                case OPEN_INTEREST_VOLUME -> persistOpenInterestVolume(task, params, period, from, to, written);
                case TAKER_VOLUME -> persistTakerVolume(task, params, period, from, to, written);
                default -> throw new IllegalStateException("unsupported statistics type " + dataType);
            };
            pageCount++;
            rowsWritten += written[0];
            long nextCursor = windowEnd + 1;
            final int count = written[0];
            final boolean trunc = truncated;
            pageWriter.write(task.id(), persist, nextCursor, pageCount, rowsWritten, trunc);
            log.debug("task {} window [{}, {}] -> {} rows", task.taskKey(), from, to, count);
            cursor = nextCursor;
        }
        return new TaskOutcome(rowsWritten, truncated, null);
    }

    /** 先取数（HTTP 在事务外），再把写入动作交给 pageWriter 与游标更新同事务提交。 */
    private Runnable persistRatios(BackfillTaskRow task, TaskParams params, Period period, long from, long to,
            int[] written) {
        RatioType ratioType = RatioType.fromCode(params.ratioType());
        List<RatioRow> rows = fetch(task, () -> RubikParser.parseRatios(
                client.getLongShortRatio(ratioType, params.scope(), period, from, to),
                ratioType.name(), params.scope(), params.scopeKind(), period.code()));
        written[0] = rows.size();
        return () -> {
            if (!rows.isEmpty()) {
                statisticsRepository.upsertRatios(rows);
            }
        };
    }

    private Runnable persistOpenInterestVolume(BackfillTaskRow task, TaskParams params, Period period, long from,
            long to, int[] written) {
        List<OpenInterestVolumeRow> rows = fetch(task, () -> RubikParser.parseOpenInterestVolume(
                client.getOpenInterestVolume(params.ccy(), period, from, to), params.ccy(), period.code()));
        written[0] = rows.size();
        return () -> {
            if (!rows.isEmpty()) {
                statisticsRepository.upsertOpenInterestVolume(rows);
            }
        };
    }

    private Runnable persistTakerVolume(BackfillTaskRow task, TaskParams params, Period period, long from, long to,
            int[] written) {
        List<TakerVolumeRow> rows = fetch(task, () -> RubikParser.parseTakerVolume(
                "INST".equals(params.scopeKind())
                        ? client.getTakerVolumeByInst(params.scope(), period, from, to)
                        : client.getTakerVolumeByCcy(params.ccy(), params.marketType(), period, from, to),
                params.scopeKind(), params.scope(), params.marketType(), period.code()));
        written[0] = rows.size();
        return () -> {
            if (!rows.isEmpty()) {
                statisticsRepository.upsertTakerVolume(rows);
            }
        };
    }

    /** 50030 = 该窗口超出 OKX 可回溯范围，跳过该窗口但不让任务失败。 */
    private <T> List<T> fetch(BackfillTaskRow task, java.util.function.Supplier<List<T>> call) {
        try {
            return call.get();
        } catch (OkxApiException ex) {
            if (ex.rangeExhausted()) {
                log.debug("task {} window out of OKX range: {}", task.taskKey(), ex.getMessage());
                return List.of();
            }
            throw ex;
        }
    }

    private TaskParams parse(BackfillTaskRow task) {
        try {
            return objectMapper.readValue(task.params(), TaskParams.class);
        } catch (Exception ex) {
            throw new IllegalStateException("bad task params: " + task.taskKey(), ex);
        }
    }
}
