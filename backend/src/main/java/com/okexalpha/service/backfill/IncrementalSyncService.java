package com.okexalpha.service.backfill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.okexalpha.domain.Bar;
import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.InstType;
import com.okexalpha.domain.JobStatus;
import com.okexalpha.domain.KlineType;
import com.okexalpha.error.ConflictException;
import com.okexalpha.repository.BackfillJobRepository;
import com.okexalpha.repository.InstrumentRepository;
import com.okexalpha.repository.SyncCursorRepository;

/**
 * <p>定时增量补数据（需求 #2）：按 okx_sync_cursor 里的 (instId, bar, klineType) 水位，
 * 每轮把 K 线补到 now - lag。</p>
 *
 * <p>水位 key 形如 {@code kline|CANDLE|BTC-USDT-SWAP|1m}，值是「已经补到的那根 K 线的开盘时间」。
 * 一轮的流程：</p>
 * <ol>
 *   <li>未启用（okx.sync.incremental.enabled=false）→ 直接返回 disabled；</li>
 *   <li>已有 KLINE 作业在 RUNNING → 本轮跳过，避免和人工补拉撞同一个 task_key；</li>
 *   <li>取最多 maxInstIds 个 live SWAP 品种；</li>
 *   <li>游标为空的组合只播种 now-lookback（写水位但不提交，先建立基线）；</li>
 *   <li>缺口不足一根 K 线 → 跳过；否则按 (bar, klineType) 分组提交补拉作业，start=组内最小游标+1、
 *       end=now-lag。提交成功只是入队，**不立刻推进水位**：先把这一组登记到内存态 pending，
 *       等下一轮（或之后某轮）确认该作业 DONE 后才把游标推进到 end —— 否则作业一旦 FAILED/CANCELED，
 *       这段缺口就永远补不回来（静默丢数据）。</li>
 *   <li>已有同 task_key 未完成任务（ConflictException）：这段确实已被在途作业接管，直接推进水位，
 *       不登记 pending（不会再有第二个作业来确认它）。</li>
 * </ol>
 *
 * <p>调度入口用 fixedDelay（上一轮跑完再等 interval），并用 AtomicBoolean 防重入；
 * 定时触发里的任何异常只落到 lastNote，不影响下一轮。</p>
 */
@Service
public class IncrementalSyncService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalSyncService.class);

    /** 水位 key 前缀；同一个 okx_sync_cursor 表里还有 option_family: 等其它来源的 key。 */
    public static final String CURSOR_PREFIX = "kline|";

    /** status() 里最多回显多少个游标（表里可能有上千个）。 */
    private static final int STATUS_CURSOR_LIMIT = 50;

    /** 统计 RUNNING 作业时最多扫多少行。 */
    private static final int RUNNING_JOB_SCAN = 100;

    /** 等待「作业完成后再推进水位」的内存登记上限；超出丢最旧的（那一段会按旧水位重提，宁重复不丢）。 */
    private static final int PENDING_LIMIT = 200;

    private final BackfillService backfillService;
    private final BackfillJobRepository jobRepository;
    private final SyncCursorRepository cursorRepository;
    private final InstrumentRepository instrumentRepository;
    private final LongSupplier nowMs;

    private final boolean enabled;
    private final long intervalMs;
    private final long lookbackMs;
    private final long lagMs;
    private final List<String> bars;
    private final List<String> klineTypes;
    private final int maxInstIds;

    /** bar code → Bar（构造时校验过，运行期不再解析）。 */
    private final Map<String, Bar> barByCode = new LinkedHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean();

    /** jobId → 待确认推进的水位登记（作业 DONE 后才推进，FAILED/CANCELED 则丢弃让缺口自愈）。 */
    private final Map<Long, PendingAdvance> pending = new ConcurrentHashMap<>();

    private volatile Long lastRunAtMs;
    private volatile String lastNote = "尚未运行";
    private volatile Long lastJobId;

    @Autowired
    public IncrementalSyncService(BackfillService backfillService, BackfillJobRepository jobRepository,
            SyncCursorRepository cursorRepository, InstrumentRepository instrumentRepository,
            @Value("${okx.sync.incremental.enabled:false}") boolean enabled,
            @Value("${okx.sync.incremental.interval-ms:300000}") long intervalMs,
            @Value("${okx.sync.incremental.lookback-ms:3600000}") long lookbackMs,
            @Value("${okx.sync.incremental.lag-ms:60000}") long lagMs,
            @Value("${okx.sync.incremental.bars:1m,5m,15m,1H}") String bars,
            @Value("${okx.sync.incremental.kline-types:CANDLE,MARK}") String klineTypes,
            @Value("${okx.sync.incremental.max-inst-ids:20}") int maxInstIds) {
        this(backfillService, jobRepository, cursorRepository, instrumentRepository, enabled, intervalMs, lookbackMs,
                lagMs, bars, klineTypes, maxInstIds, System::currentTimeMillis);
    }

    /** 单测用：可注入时钟。 */
    IncrementalSyncService(BackfillService backfillService, BackfillJobRepository jobRepository,
            SyncCursorRepository cursorRepository, InstrumentRepository instrumentRepository, boolean enabled,
            long intervalMs, long lookbackMs, long lagMs, String bars, String klineTypes, int maxInstIds,
            LongSupplier nowMs) {
        this.backfillService = backfillService;
        this.jobRepository = jobRepository;
        this.cursorRepository = cursorRepository;
        this.instrumentRepository = instrumentRepository;
        this.nowMs = nowMs;
        this.enabled = enabled;
        this.intervalMs = intervalMs;
        this.lookbackMs = lookbackMs;
        this.lagMs = lagMs;
        this.bars = parseBars(bars);
        this.klineTypes = parseKlineTypes(klineTypes);
        this.maxInstIds = Math.max(1, maxInstIds);
        for (String code : this.bars) {
            barByCode.put(code, Bar.fromCode(code));
        }
    }

    /** GET /api/v1/sync/incremental 的响应体。 */
    public record Status(boolean enabled, long intervalMs, long lookbackMs, long lagMs, List<String> bars,
            List<String> klineTypes, int maxInstIds, Long lastRunAtMs, String lastNote, Long lastJobId,
            int cursorCount, List<CursorView> cursors, int runningKlineJobs, int pendingJobs) {
    }

    /** 单个水位（cursorCount 超出 STATUS_CURSOR_LIMIT 时只回显前若干个）。 */
    public record CursorView(String key, long lastTsMs) {
    }

    /** 一轮 runOnce 的结果（直接作为 POST /api/v1/sync/incremental/run 的响应体）。 */
    public record RunResult(boolean submitted, String skippedReason, Long jobId, List<SubmittedJob> jobs,
            int cursorAdvanced, int seeded, String note) {
    }

    /** 每个 (bar, klineType) 分组提交一个作业，tasks 是分组内的 instId 数。 */
    public record SubmittedJob(long jobId, int taskTotal, String bar, String klineType) {
    }

    /** 一个已提交、等作业 DONE 后才推进的水位登记。 */
    record PendingAdvance(String bar, String klineType, List<String> instIds, long endMs, long submittedAtMs) {
    }

    /** 一轮 reconcile 的结果：推进了几条游标、因失败/失联丢弃了几个登记。 */
    private record Reconcile(int advanced, int dropped) {
    }

    /**
     * 调度入口：固定延迟（默认 300s，上一次执行完再等 interval），首次延迟 45s 避开启动期的
     * schema/迁移与自动恢复。未启用时直接返回；异常只记 lastNote，绝不抛出（否则调度器会静默停掉）。
     */
    @Scheduled(fixedDelayString = "${okx.sync.incremental.interval-ms:300000}", initialDelayString = "45000")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException ex) {
            lastRunAtMs = nowMs.getAsLong();
            lastNote = "定时增量补数据失败：" + ex.getMessage();
            log.warn("定时增量补数据失败：{}", ex.toString());
        }
    }

    /** 当前配置 + 水位概览（状态接口用；不写库）。 */
    public Status status() {
        Map<String, Long> all = cursorRepository.all();
        List<CursorView> cursors = new ArrayList<>();
        int cursorCount = 0;
        for (Map.Entry<String, Long> entry : all.entrySet()) {
            if (!entry.getKey().startsWith(CURSOR_PREFIX) || entry.getValue() == null) {
                continue;
            }
            cursorCount++;
            if (cursors.size() < STATUS_CURSOR_LIMIT) {
                cursors.add(new CursorView(entry.getKey(), entry.getValue()));
            }
        }
        return new Status(enabled, intervalMs, lookbackMs, lagMs, bars, klineTypes, maxInstIds, lastRunAtMs,
                lastNote, lastJobId, cursorCount, List.copyOf(cursors), runningKlineJobs(), pending.size());
    }

    /**
     * 跑一轮增量：播种空游标、算缺口、按 (bar, klineType) 分组提交、成功后推进水位。
     *
     * <p>不做异常兜底：disabled / RUNNING / 缺口不足都是正常返回的 skippedReason，
     * 上游异常（未预期的异常）直接抛给调用方（定时入口自己兜底，手动触发走全局异常映射）。</p>
     */
    public RunResult runOnce() {
        if (!enabled) {
            return skip("disabled", null, "增量补数据未启用（okx.sync.incremental.enabled=false）", 0);
        }
        if (!running.compareAndSet(false, true)) {
            return skip("running", null, "上一轮增量补数据仍在执行，本次跳过", 0);
        }
        try {
            long now = nowMs.getAsLong();
            lastRunAtMs = now;

            // 0) 先对账上一轮登记的 pending：只有作业真的 DONE 才把水位推进到当时的 end
            Reconcile reconcile = reconcile();
            int cursorAdvanced = reconcile.advanced();

            List<BackfillJobRow> runningJobs = jobRepository.findJobs(DataType.KLINE.name(),
                    JobStatus.RUNNING.name(), 1, 0);
            if (!runningJobs.isEmpty()) {
                Long jobId = runningJobs.get(0).id();
                return skip("kline-job-running", jobId,
                        "已有 KLINE 作业在跑（job " + jobId + "），本轮跳过等它跑完" + pendingNote(), cursorAdvanced);
            }

            List<String> instIds = instrumentRepository.findInstIds(InstType.SWAP.code(), null, maxInstIds);
            if (instIds.isEmpty()) {
                return skip("no-instruments", null, "库内没有 live SWAP 品种，本轮跳过", cursorAdvanced);
            }

            long endMs = now - lagMs;
            long seedMs = now - lookbackMs;
            Map<String, Long> cursors = cursorRepository.all();

            // 1) 播种：游标为空的组合只写 now-lookback，本轮不提交（否则第一轮会把整个回看窗口重拉一遍）
            Set<String> seededKeys = new LinkedHashSet<>();
            int seeded = 0;
            for (String barCode : bars) {
                for (String klineType : klineTypes) {
                    for (String instId : instIds) {
                        String key = cursorKey(klineType, instId, barCode);
                        if (cursors.get(key) == null) {
                            cursorRepository.set(key, seedMs);
                            seededKeys.add(key);
                            seeded++;
                        }
                    }
                }
            }

            // 2) 算缺口：缺口不足一根 K 线的组合不提交（避免为几秒的空档白建一个作业）
            Map<String, Group> groups = new LinkedHashMap<>();
            int tooNarrow = 0;
            for (String barCode : bars) {
                Bar bar = barByCode.get(barCode);
                for (String klineType : klineTypes) {
                    for (String instId : instIds) {
                        String key = cursorKey(klineType, instId, barCode);
                        if (seededKeys.contains(key)) {
                            continue;
                        }
                        Long cursor = cursors.get(key);
                        if (cursor == null) {
                            continue;
                        }
                        if (endMs - cursor < bar.intervalMs()) {
                            tooNarrow++;
                            continue;
                        }
                        groups.computeIfAbsent(groupKey(barCode, klineType), k -> new Group(barCode, klineType))
                                .add(instId, cursor);
                    }
                }
            }

            // 3) 每个 (bar, klineType) 提交一个作业；成功后把该组游标推进到 end
            List<SubmittedJob> jobs = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            for (Group group : groups.values()) {
                long startMs = group.minCursor + 1;
                KlineBackfillRequest request = new KlineBackfillRequest(List.copyOf(group.instIds), null, null,
                        List.of(group.bar), List.of(group.klineType), startMs, endMs, null);
                try {
                    BackfillService.JobSubmission submission = backfillService.submitKlines(request);
                    jobs.add(new SubmittedJob(submission.jobId(), submission.taskTotal(), group.bar, group.klineType));
                    // 只登记待推进，不立刻推水位：作业 DONE 后由下一轮 reconcile 推进，
                    // 否则作业若 FAILED/CANCELED，这段缺口就永远补不回来。
                    registerPending(submission.jobId(), new PendingAdvance(group.bar, group.klineType,
                            List.copyOf(group.instIds), endMs, now));
                    log.info("incremental submitted job {} {} {} inst={} {}..{} (waiting DONE to advance)",
                            submission.jobId(), group.klineType, group.bar, group.instIds.size(), startMs, endMs);
                } catch (ConflictException ex) {
                    // 同 task_key 已经有 pending/running 任务：这段已经被排上队，水位照推进，下一轮不再重复提交
                    cursorAdvanced += group.advance(endMs);
                    conflicts.add(group.klineType + ":" + group.bar);
                    log.info("incremental {} {} already queued by an active job, cursor advanced to {}", group.klineType,
                            group.bar, endMs);
                }
            }
            if (!jobs.isEmpty()) {
                lastJobId = jobs.get(jobs.size() - 1).jobId();
            }

            String note = "提交 " + jobs.size() + " 个作业、待完成推进 " + pending.size() + " 条、播种 " + seeded
                    + " 条游标" + (cursorAdvanced > 0 ? "，本轮对账推进 " + cursorAdvanced + " 个游标" : "")
                    + (reconcile.dropped() > 0 ? "，丢弃 " + reconcile.dropped() + " 个失败/失联作业登记（下轮重提）" : "")
                    + (tooNarrow > 0 ? "，" + tooNarrow + " 个组合缺口不足一根已跳过" : "")
                    + (conflicts.isEmpty() ? "" : "，" + String.join(",", conflicts) + " 已有未完成任务")
                    + pendingNote();
            lastNote = note;
            return new RunResult(!jobs.isEmpty(), null, null, List.copyOf(jobs), cursorAdvanced, seeded, note);
        } finally {
            running.set(false);
        }
    }

    /** 水位 key：kline|CANDLE|BTC-USDT-SWAP|1m。 */
    static String cursorKey(String klineType, String instId, String bar) {
        return CURSOR_PREFIX + klineType + "|" + instId + "|" + bar;
    }

    /** 单轮内不推进 lastNote 之外的状态的跳过返回；cursorAdvanced 只带本轮 reconcile 的推进数。 */
    private RunResult skip(String reason, Long jobId, String note, int cursorAdvanced) {
        lastNote = note;
        return new RunResult(false, reason, jobId, List.of(), cursorAdvanced, 0, note);
    }

    /**
     * 对账内存里的 pending：作业 DONE 才推进水位；FAILED/CANCELED 或查不到（异常/null）只丢登记，
     * 让下一轮按旧水位重新提交 —— 宁可重复拉一段，也不能静默丢数据。
     */
    private Reconcile reconcile() {
        int advanced = 0;
        int dropped = 0;
        for (Map.Entry<Long, PendingAdvance> entry : pending.entrySet()) {
            long jobId = entry.getKey();
            PendingAdvance advance = entry.getValue();
            String status;
            try {
                BackfillJobRow job = backfillService.getJob(jobId);
                if (job == null) {
                    pending.remove(jobId);
                    dropped++;
                    continue;
                }
                status = job.status();
            } catch (RuntimeException ex) {
                // 作业行已查不到（被清理等）：不能假设这段补好了，丢登记等重提
                pending.remove(jobId);
                dropped++;
                log.info("增量 pending 作业 {} 查询失败，丢弃登记等重提：{}", jobId, ex.toString());
                continue;
            }
            if (JobStatus.DONE.name().equals(status)) {
                advanced += advanceCursors(advance);
                pending.remove(jobId);
                log.info("增量作业 {} DONE，水位推进到 {}", jobId, advance.endMs());
            } else if (JobStatus.FAILED.name().equals(status) || JobStatus.CANCELED.name().equals(status)) {
                pending.remove(jobId);
                dropped++;
                log.warn("增量作业 {} 结束状态为 {}，水位不推进，下一轮按旧水位重提", jobId, status);
            }
            // PENDING / RUNNING / PAUSED：继续等
        }
        return new Reconcile(advanced, dropped);
    }

    /** 把一个登记里的 instId 水位推到最后一次提交的 end。 */
    private int advanceCursors(PendingAdvance advance) {
        for (String instId : advance.instIds()) {
            cursorRepository.set(cursorKey(advance.klineType(), instId, advance.bar()), advance.endMs());
        }
        return advance.instIds().size();
    }

    /** 登记待推进；超过上限时丢最旧的（那段会按旧水位重提，不会丢数据）。 */
    private void registerPending(long jobId, PendingAdvance advance) {
        pending.put(jobId, advance);
        while (pending.size() > PENDING_LIMIT) {
            Map.Entry<Long, PendingAdvance> oldest = null;
            for (Map.Entry<Long, PendingAdvance> entry : pending.entrySet()) {
                if (oldest == null || entry.getValue().submittedAtMs() < oldest.getValue().submittedAtMs()) {
                    oldest = entry;
                }
            }
            if (oldest == null) {
                break;
            }
            pending.remove(oldest.getKey());
            log.warn("增量 pending 超过上限 {}，丢弃最旧的作业 {}（该段会按旧水位重提）", PENDING_LIMIT, oldest.getKey());
        }
    }

    /** note 里补一句待推进数量。 */
    private String pendingNote() {
        int count = pending.size();
        return count == 0 ? "" : "，" + count + " 个作业待完成后推进水位";
    }

    private int runningKlineJobs() {
        try {
            return jobRepository.findJobs(DataType.KLINE.name(), JobStatus.RUNNING.name(), RUNNING_JOB_SCAN, 0).size();
        } catch (RuntimeException ex) {
            log.warn("统计 RUNNING KLINE 作业失败：{}", ex.toString());
            return 0;
        }
    }

    private static String groupKey(String bar, String klineType) {
        return klineType + '|' + bar;
    }

    private static List<String> parseBars(String raw) {
        List<String> out = new ArrayList<>();
        for (String token : splitTokens(raw)) {
            Bar bar = Bar.fromCode(token);
            if (!out.contains(bar.code())) {
                out.add(bar.code());
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("okx.sync.incremental.bars 不能为空");
        }
        return List.copyOf(out);
    }

    private static List<String> parseKlineTypes(String raw) {
        List<String> out = new ArrayList<>();
        for (String token : splitTokens(raw)) {
            String name = KlineType.fromCode(token).name();
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("okx.sync.incremental.kline-types 不能为空");
        }
        return List.copyOf(out);
    }

    private static List<String> splitTokens(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 一个 (bar, klineType) 分组：待补的 instId + 组内最小水位 + 推进水位。 */
    private final class Group {

        private final String bar;
        private final String klineType;
        private final List<String> instIds = new ArrayList<>();
        private long minCursor = Long.MAX_VALUE;

        private Group(String bar, String klineType) {
            this.bar = bar;
            this.klineType = klineType;
        }

        private void add(String instId, long cursor) {
            instIds.add(instId);
            minCursor = Math.min(minCursor, cursor);
        }

        /** 把本组所有游标推进到 endMs，返回推进的个数。 */
        private int advance(long endMs) {
            for (String instId : instIds) {
                cursorRepository.set(cursorKey(klineType, instId, bar), endMs);
            }
            return instIds.size();
        }
    }
}
