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
import com.okexalpha.domain.KlineCoverage;
import com.okexalpha.domain.KlineRow;
import com.okexalpha.domain.KlineType;
import com.okexalpha.repository.KlineRepository;

/**
 * 按 after 游标向前翻页补拉 K 线（CANDLE / MARK，均为永续合约 SWAP）。
 * 每 okx.db.commit-every-pages 页合并一次事务提交（见 {@link PageBatcher}）。
 *
 * <p>默认开启覆盖率跳过（okx.backfill.coverage-skip）：开跑前先查库内已有哪些区间
 * （见 {@link KlineCoverage}），只拉还缺的段，已下过的部分不再消耗上游配额。中间有缺口时
 * 先用 LAG 查询把洞逐段定位出来再分段补拉（见 {@link KlineRepository#gaps}）；定位不了
 * （查询失败/洞太多）才保守整段重拉，无论哪条路都不会跳过缺口。</p>
 */
@Component
public class KlineTaskHandler implements BackfillTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(KlineTaskHandler.class);
    private static final int MAX_API_LIMIT = 300;

    private final OkxApiClient client;
    private final OkxProperties properties;
    private final KlineRepository klineRepository;
    private final PageBatcher pageBatcher;
    private final ObjectMapper objectMapper;

    public KlineTaskHandler(OkxApiClient client, OkxProperties properties, KlineRepository klineRepository,
            PageBatcher pageBatcher, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.klineRepository = klineRepository;
        this.pageBatcher = pageBatcher;
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

        int pageCount = task.pageCount();
        long rowsWritten = task.rowsWritten();
        if (control.shouldStop()) {
            // 暂停的任务连覆盖率查询都不用做
            return new TaskOutcome(rowsWritten, false, "stopped");
        }

        boolean coverageSkip = properties.backfill().coverageSkip();
        List<KlineCoverage.Segment> segments = coverageSkip
                ? planSegments(task, params, klineType, bar, startMs, endMs)
                : List.of(new KlineCoverage.Segment(startMs, endMs));
        if (segments.isEmpty()) {
            log.info("task {} skipped: {} {} {} {}..{} already fully covered in db", task.taskKey(),
                    klineType.name(), params.instId(), bar.code(), startMs, endMs);
            return new TaskOutcome(rowsWritten, false, null);
        }

        PageBatcher.Batch batch = pageBatcher.batch(task.id());
        Long resumeCursor = task.cursorMs();
        // 段按从新到旧处理：最新的缺口优先落地，崩溃/暂停后剩下的都是老数据
        for (int i = segments.size() - 1; i >= 0; i--) {
            KlineCoverage.Segment segment = segments.get(i);
            long segmentStart = segment.fromMs();
            long segmentTop = segment.toMs();
            // 覆盖率模式不信任 cursor_ms：已翻过的页都随同事务落了库，缺失段本身就是从库内实况推导的；
            // 暂停、失败重试、服务重启后重算一次覆盖率即可，旧代码写入的 cursor 也被安全忽略。
            // 关闭 coverage-skip 时保持 legacy 语义：从持久化游标（没有则 endMs）续跑整段。
            // 段顶游标 +1：OKX 的 after 是排他上界（返回 ts < after），不加 1 会永远取不到段顶那一根。
            long cursor = coverageSkip ? segmentTop + 1 : resumeCursor != null ? resumeCursor : segmentTop;
            while (true) {
                if (control.shouldStop()) {
                    batch.flush();
                    log.info("task {} stopped at cursor {}", task.taskKey(), cursor);
                    return new TaskOutcome(rowsWritten, false, "stopped");
                }
                List<JsonNode> data = client.getCandles(klineType, params.instId(), bar.code(), cursor, null, limit);
                List<KlineRow> page = CandleParser.parseAll(data, klineType, params.instId(), params.instType(),
                        bar.code()).stream()
                        .filter(row -> row.tsMs() >= segmentStart && row.tsMs() <= segmentTop)
                        .toList();
                if (page.isEmpty()) {
                    break;
                }
                long minTs = page.stream().mapToLong(KlineRow::tsMs).min().orElse(cursor);
                long newCursor = minTs >= cursor ? cursor - 1 : minTs;
                pageCount++;
                rowsWritten += page.size();
                final List<KlineRow> rows = page;
                batch.add(() -> klineRepository.upsertAll(rows), newCursor, pageCount, rowsWritten, false);
                cursor = newCursor;
                if (cursor <= segmentStart) {
                    break;
                }
            }
        }
        batch.flush();
        return new TaskOutcome(rowsWritten, false, null);
    }

    /**
     * 查库内覆盖率并换算成缺失段，两阶段：便宜的 count 判定先发现「中间有缺」，
     * 再用 LAG 查询把洞逐段定位出来（见 {@link KlineRepository#gaps}），避免整段重拉的放大。
     * 任一查询失败都退化为整段（与优化前一致）：宁可多拉，不可漏。
     */
    private List<KlineCoverage.Segment> planSegments(BackfillTaskRow task, TaskParams params, KlineType klineType,
            Bar bar, long startMs, long endMs) {
        KlineRepository.Coverage coverage;
        try {
            coverage = klineRepository.coverage(klineType.name(), params.instId(), bar.code(), startMs, endMs);
        } catch (RuntimeException ex) {
            log.warn("task {} coverage query failed, falling back to full range: {}", task.taskKey(), ex.toString());
            return List.of(new KlineCoverage.Segment(startMs, endMs));
        }
        Long minTs = coverage == null ? null : coverage.minTs();
        Long maxTs = coverage == null ? null : coverage.maxTs();
        long rows = coverage == null ? 0L : coverage.rows();
        long intervalMs = bar.intervalMs();
        List<KlineCoverage.Segment> holes = null;
        if (minTs != null && maxTs != null && maxTs >= minTs && intervalMs > 0L && (maxTs - minTs) % intervalMs == 0L
                && rows < (maxTs - minTs) / intervalMs + 1L) {
            // 中间有缺：先精确定位洞（洞数超上限时 planGaps 自己会退回整段）
            try {
                holes = klineRepository.gaps(klineType.name(), params.instId(), bar.code(), startMs, endMs, intervalMs)
                        .stream()
                        .map(gap -> new KlineCoverage.Segment(gap.fromMs(), gap.toMs()))
                        .toList();
                if (holes.isEmpty()) {
                    // count 与 gap 两次查询之间缺口被并发作业补上了（更晚的快照没有洞）→ 只补边缘
                    log.info("task {} gap count mismatch resolved by fresher snapshot: no gaps found",
                            task.taskKey());
                } else {
                    log.info("task {} interior incomplete: {} gap segment(s) located instead of full re-download",
                            task.taskKey(), holes.size());
                }
            } catch (RuntimeException ex) {
                log.warn("task {} gap query failed, falling back to full range: {}", task.taskKey(), ex.toString());
                return List.of(new KlineCoverage.Segment(startMs, endMs));
            }
        }
        return KlineCoverage.planGaps(minTs, maxTs, rows, holes, startMs, endMs, intervalMs,
                properties.backfill().coverageRefreshMs(), System.currentTimeMillis());
    }

    private TaskParams parse(BackfillTaskRow task) {
        try {
            return objectMapper.readValue(task.params(), TaskParams.class);
        } catch (Exception ex) {
            throw new IllegalStateException("bad task params: " + task.taskKey(), ex);
        }
    }
}
