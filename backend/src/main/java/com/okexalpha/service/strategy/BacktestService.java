package com.okexalpha.service.strategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.BacktestResultRow;
import com.okexalpha.domain.BacktestRunRow;
import com.okexalpha.domain.Bar;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.error.ConflictException;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.repository.BacktestRepository;
import com.okexalpha.strategy.engine.BacktestMetrics;
import com.okexalpha.strategy.engine.BacktestOutcome;
import com.okexalpha.strategy.engine.MartingaleBacktester;
import com.okexalpha.strategy.engine.ParamRanges;
import com.okexalpha.strategy.engine.ParamSampler;
import com.okexalpha.strategy.engine.PriceBar;
import com.okexalpha.strategy.engine.ShadowMath;
import com.okexalpha.strategy.engine.StrategyParams;
import com.okexalpha.web.dto.BacktestRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 回测服务（阶段 C / P1）。
 *
 * <p>两阶段寻优，单实例同一时间只允许一个批次（回测是 CPU 密集，串行更容易解释吞吐）：</p>
 * <ol>
 *   <li><b>COARSE</b>：每个币序列只读一次，对抽样出的 N 个参数组合分别跑「样本内（前 70% 区间）」
 *       与「样本外（后 30% 区间）」，得到每组合的跨币聚合指标并按 score 排序；</li>
 *   <li><b>REFINE</b>：对 Top-N 组合逐币跑全区间，落单币明细；冠军组合额外落逐笔成交与资金曲线。</li>
 * </ol>
 *
 * <p>score = 0.6×样本内超额收益 − 0.3×样本内平均回撤 + 0.4×样本外超额收益（百分比口径）。
 * 超额收益 = 策略按初始保证金口径的收益率 − 同窗口同杠杆的买入持有收益率，因此既奖励稳定盈利、
 * 惩罚高回撤曲线，也不会把"单纯吃到单边行情 beta"的组合排到第一。</p>
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    /** 未指定区间时的默认窗口：90 天。 */
    private static final long DEFAULT_WINDOW_MS = 90L * 86_400_000L;
    private static final long MIN_WINDOW_MS = 7L * 86_400_000L;
    /** 样本内占比（其余为样本外验证段）。 */
    private static final double IN_SAMPLE_RATIO = 0.7d;
    /** 判定 CANDIDATE 所需的最少总成交笔数（否则样本太少不足信）。 */
    private static final int MIN_TRADES = 10;
    /** OOS 段最少成交笔数（方案 §2.7：648 组参数 × 90 天，噪声也能凑出"OOS 为正"）。 */
    private static final int MIN_OOS_TRADES = 30;
    /** OOS 段最大回撤上限（%）：马丁的回撤是收敛型亏损的先行指标。 */
    private static final double MAX_OOS_DRAWDOWN_PCT = 25d;
    /** IS 段最大回撤上限（%）：原来只卡 OOS 回撤，样本内 40%+ 回撤的杠杆组合照样能拿第一。 */
    private static final double MAX_IS_DRAWDOWN_PCT = 40d;
    private static final int MAX_COINS = 50;
    /** 未显式给账户权益时，按「首层保证金 × 该层数」估：默认 8 层马丁对应的账户规模。 */
    private static final int DEFAULT_ACCOUNT_LAYERS = 8;

    private final BacktestRepository repository;
    private final BacktestSeriesLoader loader;
    private final ObjectMapper objectMapper;
    private final OkxProperties properties;
    private final AsyncTaskExecutor executor;

    public BacktestService(BacktestRepository repository, BacktestSeriesLoader loader, ObjectMapper objectMapper,
            OkxProperties properties, @Qualifier("strategyBacktestExecutor") AsyncTaskExecutor executor) {
        this.repository = repository;
        this.loader = loader;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.executor = executor;
    }

    // ------------------------------------------------------------------ 提交

    public long submit(BacktestRequest request) {
        if (repository.countActive() > 0) {
            throw new ConflictException("another backtest is running; wait for it or POST /api/v1/strategy/backtests/{id}/cancel");
        }
        Plan plan = plan(request);
        String requestJson = objectMapper.writeValueAsString(plan.request());
        long runId = repository.createRun(plan.strategyId(), plan.label(), plan.bar(), plan.startMs(), plan.endMs(),
                requestJson, plan.coins().size(), plan.combos().size());
        log.info("backtest {} submitted: {} coin(s) x {} combo(s), bar={}, window=[{}, {}]", runId,
                plan.coins().size(), plan.combos().size(), plan.bar(), plan.startMs(), plan.endMs());
        executor.execute(() -> run(runId, plan));
        return runId;
    }

    private Plan plan(BacktestRequest request) {
        String bar = request.bar() == null || request.bar().isBlank() ? "1m" : request.bar().trim();
        try {
            Bar.fromCode(bar);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("invalid bar: " + bar);
        }
        long endMs = request.endMs() != null ? request.endMs() : System.currentTimeMillis();
        long startMs = request.startMs() != null ? request.startMs() : endMs - DEFAULT_WINDOW_MS;
        if (startMs >= endMs) {
            throw new BadRequestException("startMs must be < endMs");
        }
        if (endMs - startMs < MIN_WINDOW_MS) {
            throw new BadRequestException("backtest window too short: need at least 7 days");
        }
        long cutMs = startMs + Math.round((endMs - startMs) * IN_SAMPLE_RATIO);

        int coinLimit = clamp(request.coinLimit() == null ? 12 : request.coinLimit(), 1, MAX_COINS);
        List<String> coins = new ArrayList<>();
        if (request.instIds() != null) {
            for (String id : request.instIds()) {
                if (id != null && !id.isBlank() && !coins.contains(id.trim())) {
                    coins.add(id.trim());
                }
            }
        }
        if (coins.isEmpty()) {
            coins.addAll(loader.liveSwaps(coinLimit));
        }
        if (coins.isEmpty()) {
            throw new BadRequestException("no instrument to backtest: run POST /api/v1/sync/instruments first");
        }
        if (coins.size() > MAX_COINS) {
            coins = new ArrayList<>(coins.subList(0, MAX_COINS));
        }

        double marginUsd = request.marginUsd() != null && request.marginUsd() > 0 ? request.marginUsd()
                : properties.strategy().trading().capitalPerCoinUsd() > 0
                        ? properties.strategy().trading().capitalPerCoinUsd()
                        : 100d;
        int maxHoldHours = clamp(request.maxHoldHours() == null ? 48 : request.maxHoldHours(), 1, 24 * 30);
        double fundingPct = request.fundingPer8hPct() == null ? 0.01d : Math.max(0d, request.fundingPer8hPct());

        // 账户权益：加仓闸门用「已用保证金 + 新层 <= 权益 × 95%」判断这一层开不开。
        // 没给就按首层保证金 × DEFAULT_ACCOUNT_LAYERS 估，恰好等于 8 层打满所需的资金。
        double accountCapitalUsd = request.accountCapitalUsd() != null && request.accountCapitalUsd() > 0
                ? request.accountCapitalUsd()
                : marginUsd * DEFAULT_ACCOUNT_LAYERS;

        StrategyParams base;
        try {
            base = new StrategyParams(StrategyParams.LONG, marginUsd, 5, 5, 2.5d, 1.0d, 2.0d, 25.0d, maxHoldHours,
                    properties.strategy().trading().feeTaker(), properties.strategy().trading().feeMaker(), fundingPct,
                    properties.strategy().trading().slippageBps(), accountCapitalUsd);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        }

        long seed = request.seed() == null ? 20261101L : request.seed();

        // 可选的自定义参数范围：先校验，再按范围展开网格，最后仍按 want 抽样（保证复现性）
        ParamRanges ranges = request.ranges();
        int rangeCombos = 0;
        boolean rangeTruncated = false;
        List<StrategyParams> grid;
        if (ranges == null) {
            grid = ParamSampler.grid(base);
        } else {
            try {
                ranges.validate();
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException(ex.getMessage());
            }
            rangeCombos = ranges.combos();
            grid = ParamSampler.grid(base, ranges);
            // 只有网格展开护栏（ParamRanges.DEFAULT_EXPAND_LIMIT）还会截断；组合数本身已无上限
            rangeTruncated = rangeCombos > grid.size();
            if (rangeTruncated) {
                log.info("backtest param ranges expand to {} combo(s), grid capped at {} by the expand limit",
                        rangeCombos, grid.size());
            }
        }
        int want = wantCombos(request.combos(), properties.strategy().backtest().defaultSampleSize(),
                properties.strategy().backtest().maxCombos(), grid.size());
        List<StrategyParams> combos = ParamSampler.sample(grid, want, seed);
        int topN = clamp(request.topN() == null ? 5 : request.topN(), 1, 20);

        Map<String, Object> requestMap = new LinkedHashMap<>();
        requestMap.put("instIds", coins);
        requestMap.put("bar", bar);
        requestMap.put("startMs", startMs);
        requestMap.put("endMs", endMs);
        requestMap.put("inSampleCutMs", cutMs);
        requestMap.put("combos", combos.size());
        requestMap.put("topN", topN);
        requestMap.put("marginUsd", marginUsd);
        requestMap.put("accountCapitalUsd", accountCapitalUsd);
        requestMap.put("maxHoldHours", maxHoldHours);
        requestMap.put("fundingPer8hPct", fundingPct);
        requestMap.put("feeTaker", properties.strategy().trading().feeTaker());
        requestMap.put("feeMaker", properties.strategy().trading().feeMaker());
        requestMap.put("slippageBps", properties.strategy().trading().slippageBps());
        requestMap.put("seed", seed);
        if (ranges != null) {
            requestMap.put("paramRanges", rangeMap(ranges));
            requestMap.put("rangeCombos", rangeCombos);
            requestMap.put("rangeTruncated", rangeTruncated);
        }

        return new Plan(bar, startMs, endMs, cutMs, marginUsd, accountCapitalUsd, topN, coins, combos, requestMap,
                request.label(), request.strategyId());
    }

    /** 把自定义范围转成可 JSON 化的 map（写进 requestJson，便于日后对照复现）。 */
    private static Map<String, Object> rangeMap(ParamRanges ranges) {
        Map<String, Object> out = new LinkedHashMap<>();
        putRange(out, "leverage", ranges.leverage());
        putRange(out, "maxLayers", ranges.maxLayers());
        putRange(out, "layerStepPct", ranges.layerStepPct());
        putRange(out, "layerScale", ranges.layerScale());
        putRange(out, "takeProfitPct", ranges.takeProfitPct());
        putRange(out, "stopLossPct", ranges.stopLossPct());
        out.put("sides", ranges.sides().isEmpty()
                ? List.of(StrategyParams.LONG, StrategyParams.SHORT) : ranges.sides());
        return out;
    }

    private static void putRange(Map<String, Object> target, String name, ParamRanges.Range range) {
        if (range == null) {
            target.put(name, null);
            return;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("min", range.min());
        value.put("max", range.max());
        value.put("step", range.step());
        target.put(name, value);
    }

    // ------------------------------------------------------------------ 执行

    private void run(long runId, Plan plan) {
        try {
            repository.markRunning(runId);
            List<StrategyParams> combos = plan.combos();
            int n = combos.size();
            Aggregate[] totals = new Aggregate[n];
            for (int i = 0; i < n; i++) {
                totals[i] = new Aggregate();
            }
            AtomicInteger coinDone = new AtomicInteger();
            List<CompletableFuture<Void>> futures = new ArrayList<>(plan.coins().size());
            for (String instId : plan.coins()) {
                futures.add(CompletableFuture.runAsync(
                        () -> coarsePass(runId, plan, instId, combos, totals, coinDone), executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            if (canceled(runId)) {
                log.info("backtest {} canceled during coarse pass", runId);
                return;
            }

            List<Ranked> ranked = rank(combos, totals, plan.accountCapitalUsd());
            int written = writeAggregates(runId, ranked);
            log.info("backtest {} coarse done: {} combo(s) ranked, {} written", runId, ranked.size(), written);

            if (canceled(runId)) {
                return;
            }
            // combos_done 写真实评估数（= 落库行数），前端进度条才和 combos_total 对齐
            repository.updateProgress(runId, "REFINE", plan.coins().size(), written);
            long bestResultId = refine(runId, plan, ranked);
            if (bestResultId > 0) {
                repository.setBestResult(runId, bestResultId);
            }
            repository.finish(runId, "DONE", null);
            log.info("backtest {} done", runId);
        } catch (RuntimeException | Error ex) {
            log.error("backtest {} failed", runId, ex);
            if (!"CANCELED".equals(repository.statusOf(runId))) {
                repository.finish(runId, "FAILED", truncate(String.valueOf(ex), 900));
            }
        }
    }

    /** 单个币的粗筛：序列只读一次，样本内/样本外各跑一遍。 */
    private void coarsePass(long runId, Plan plan, String instId, List<StrategyParams> combos, Aggregate[] totals,
            AtomicInteger coinDone) {
        if (canceled(runId)) {
            return;
        }
        int n = combos.size();
        Aggregate[] local = new Aggregate[n];
        for (int i = 0; i < n; i++) {
            local[i] = new Aggregate();
        }
        try {
            List<PriceBar> bars = loader.load(instId, plan.bar(), plan.startMs(), plan.endMs());
            int cut = cutIndex(bars, plan.cutMs());
            List<PriceBar> inSample = bars.subList(0, cut);
            List<PriceBar> outOfSample = bars.subList(cut, bars.size());
            // 同一段的买入持有基准只算一次：策略与基准都按同一杠杆曝险比较，基准不计手续费/资金费
            double isBenchPct = priceReturnPct(inSample);
            double oosBenchPct = priceReturnPct(outOfSample);
            for (int i = 0; i < n; i++) {
                StrategyParams params = combos.get(i);
                if (inSample.size() >= 2) {
                    local[i].addInSample(MartingaleBacktester.run(inSample, params).metrics(), plan.marginUsd(),
                            isBenchPct, params.leverage());
                }
                if (outOfSample.size() >= 2) {
                    local[i].addOutOfSample(MartingaleBacktester.run(outOfSample, params).metrics(),
                            plan.marginUsd(), oosBenchPct, params.leverage());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("backtest coin {} skipped: {}", instId, ex.toString());
        }
        synchronized (totals) {
            for (int i = 0; i < n; i++) {
                totals[i].merge(local[i]);
            }
        }
        int done = coinDone.incrementAndGet();
        // 粗筛每跑完一个币回报一次：组数按「所有币都跑过的组」折算，
        // 上万组的大批次不会再出现「进度条一直 0、最后一次性跳到 N/N」。
        int groupsDone = (int) Math.round((double) n * done / Math.max(1, plan.coins().size()));
        repository.updateProgress(runId, "COARSE", done, Math.min(groupsDone, n));
    }

    /** 一段行情的买入持有收益率（%，首根收盘 → 末根收盘）。样本不足 2 根返回 0。 */
    private static double priceReturnPct(List<PriceBar> bars) {
        if (bars == null || bars.size() < 2) {
            return 0d;
        }
        double first = bars.get(0).close();
        double last = bars.get(bars.size() - 1).close();
        if (!(first > 0d) || !(last > 0d)) {
            return 0d;
        }
        return (last / first - 1d) * 100d;
    }

    private int cutIndex(List<PriceBar> bars, long cutMs) {
        int lo = 0;
        int hi = bars.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (bars.get(mid).tsMs() <= cutMs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return Math.max(1, Math.min(lo, bars.size() - 1));
    }

    /**
     * 目标组合数：显式请求优先，其次默认抽样数，最后全网格；{@code maxCombos <= 0} 表示不设上限。
     *
     * <p>结果始终落在 {@code [1, gridSize]}：所谓"不设上限"也不会超过网格本身，而网格规模另有
     * {@code ParamRanges.DEFAULT_EXPAND_LIMIT} 的内存护栏。</p>
     */
    static int wantCombos(Integer requested, int defaultSampleSize, int maxCombos, int gridSize) {
        int grid = Math.max(1, gridSize);
        int upper = maxCombos > 0 ? Math.min(maxCombos, grid) : grid;
        int want = requested != null && requested > 0 ? requested
                : defaultSampleSize > 0 ? defaultSampleSize : grid;
        return Math.max(1, Math.min(want, upper));
    }

    private List<Ranked> rank(List<StrategyParams> combos, Aggregate[] totals, double accountCapitalUsd) {
        List<Ranked> ranked = new ArrayList<>(combos.size());
        for (int i = 0; i < combos.size(); i++) {
            ranked.add(new Ranked(combos.get(i), totals[i].summary(accountCapitalUsd)));
        }
        ranked.sort(Comparator.comparingDouble((Ranked r) -> -r.summary().score())
                .thenComparingDouble(r -> -r.summary().meanOutOfSampleReturnPct()));
        return ranked;
    }

    /** 全部排名结果一次落库（不再截断）：批量 INSERT，几千行也只是几次 round-trip。 */
    private int writeAggregates(long runId, List<Ranked> ranked) {
        List<BacktestRepository.ResultInsert> rows = new ArrayList<>(ranked.size());
        int rank = 0;
        for (Ranked item : ranked) {
            rank++;
            Summary s = item.summary();
            rows.add(new BacktestRepository.ResultInsert(item.params().key(),
                    objectMapper.writeValueAsString(item.params()), null, "ALL",
                    objectMapper.writeValueAsString(metricsJson(s)), s.coins(), s.inSampleTrades(), s.winRate(),
                    round2(s.netPnl()), round2(s.meanInSampleReturnPct()), round2(s.meanInSampleDrawdownPct()),
                    round2(s.meanInSampleSharpe()), s.liquidations(), round2(s.meanOutOfSampleReturnPct()),
                    s.verdict(), rank));
        }
        repository.insertResults(runId, rows);
        return rows.size();
    }

    private Map<String, Object> metricsJson(Summary s) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("score", round2(s.score()));
        metrics.put("coinWinRatePct", round2(s.coinWinRatePct()));
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("returnPct", round2(s.meanInSampleReturnPct()));
        in.put("maxDrawdownPct", round2(s.meanInSampleDrawdownPct()));
        in.put("trades", s.inSampleTrades());
        in.put("sharpe", round2(s.meanInSampleSharpe()));
        metrics.put("inSample", in);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("returnPct", round2(s.meanOutOfSampleReturnPct()));
        out.put("maxDrawdownPct", round2(s.meanOutOfSampleDrawdownPct()));
        out.put("trades", s.outOfSampleTrades());
        metrics.put("outOfSample", out);
        metrics.put("coins", s.coins());
        metrics.put("oosCoins", s.oosCoins());
        // 基准：同窗口买入持有（按该组合杠杆放大，不计手续费/资金费）。
        // 只有 excess（策略 − 基准）为正，才说明这套马丁不是在单纯吃行情 beta。
        Map<String, Object> bench = new LinkedHashMap<>();
        bench.put("inSampleBuyHoldPct", round2(s.meanInSampleBenchmarkPct()));
        bench.put("outOfSampleBuyHoldPct", round2(s.meanOutOfSampleBenchmarkPct()));
        bench.put("inSampleExcessPct", round2(s.meanInSampleExcessPct()));
        bench.put("outOfSampleExcessPct", round2(s.meanOutOfSampleExcessPct()));
        bench.put("inSampleCapitalReturnPct", round2(s.meanInSampleCapitalReturnPct()));
        bench.put("outOfSampleCapitalReturnPct", round2(s.meanOutOfSampleCapitalReturnPct()));
        metrics.put("benchmark", bench);
        metrics.put("excessPct", round2(s.meanOutOfSampleExcessPct()));
        // 共享账户：各币峰值保证金之和 vs 账户权益 × 95%（同时满仓的上界，保守）
        Map<String, Object> portfolio = new LinkedHashMap<>();
        portfolio.put("peakMarginUsd", round2(s.portfolioPeakMarginUsd()));
        portfolio.put("note", "各币峰值已用保证金之和（同时满仓上界），需 <= 账户权益 × 95% 才够格上影子");
        metrics.put("portfolio", portfolio);
        metrics.put("scoreFormula", "score = 0.6*ISexcess + 0.4*OOSexcess - 0.3*ISdd");
        metrics.put("verdictRule",
                "CANDIDATE 需同时满足：IS 成交 ≥ " + MIN_TRADES + "、IS 收益 > 0、OOS 收益 > 0、OOS 成交 ≥ "
                        + MIN_OOS_TRADES + "、OOS 回撤 < " + (int) MAX_OOS_DRAWDOWN_PCT + "%、IS 回撤 < "
                        + (int) MAX_IS_DRAWDOWN_PCT + "%、OOS 超额收益 > 0、强平次数 = 0、组合峰值保证金 ≤ "
                        + "账户权益 × 95%");
        return metrics;
    }

    /** 精调：Top-N 组合逐币跑全区间落明细；冠军额外落逐笔成交与资金曲线。返回冠军聚合行 id（无则 0）。 */
    private long refine(long runId, Plan plan, List<Ranked> ranked) {
        int topCount = Math.min(plan.topN(), ranked.size());
        long bestResultId = 0;
        for (Ranked item : ranked.subList(0, topCount)) {
            if (canceled(runId)) {
                return bestResultId;
            }
            boolean champion = item == ranked.get(0);
            if (champion) {
                bestResultId = aggregateId(runId, item.params().key());
            }
            for (String instId : plan.coins()) {
                List<PriceBar> bars = loader.load(instId, plan.bar(), plan.startMs(), plan.endMs());
                if (bars.size() < 2) {
                    continue;
                }
                BacktestOutcome out = MartingaleBacktester.run(bars, item.params());
                BacktestMetrics m = out.metrics();
                long resultId = repository.insertResult(runId, new BacktestRepository.ResultInsert(
                        item.params().key(), objectMapper.writeValueAsString(item.params()), instId, "COIN",
                        objectMapper.writeValueAsString(Map.of("metrics", m)), 1, m.trades(), m.winRate(),
                        // net_pnl 是美元列：直接用引擎的已实现盈亏，不再用 returnPct 反推
                        // （F6 之后 returnPct 的分母是峰值已用保证金，反推会随层数失真）。
                        round2(m.netPnlUsd()), round2(m.returnPct()),
                        round2(m.maxDrawdownPct()),
                        round2(m.sharpe()), m.liquidations(), null, "COIN", null));
                if (champion) {
                    repository.insertTrades(runId, resultId, instId, out.trades());
                    repository.insertEquity(resultId, out.equity());
                }
            }
        }
        return bestResultId;
    }

    private long aggregateId(long runId, String paramKey) {
        return repository.findResultIdByParamKey(runId, paramKey);
    }

    // ------------------------------------------------------------------ 查询

    public Map<String, Object> runs(int limit, int offset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", repository.countRuns());
        body.put("limit", limit);
        body.put("offset", offset);
        body.put("items", repository.listRuns(limit, offset));
        return body;
    }

    public Map<String, Object> detail(long runId) {
        BacktestRunRow run = repository.findRun(runId);
        if (run == null) {
            throw new NotFoundException("backtest run not found: " + runId);
        }
        List<BacktestResultRow> results = repository.listResults(runId, 50);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run", run);
        body.put("results", results);
        body.put("resultTotal", repository.countResults(runId));
        BacktestResultRow best = null;
        if (run.bestResultId() != null) {
            best = repository.findResult(run.bestResultId());
        }
        body.put("best", best);
        return body;
    }

    public List<BacktestResultRow> results(long runId, int limit) {
        requireRun(runId);
        return repository.listResults(runId, limit);
    }

    public List<BacktestResultRow> coinResults(long runId, long resultId, int limit) {
        requireRun(runId);
        BacktestResultRow row = repository.findResult(resultId);
        if (row == null || row.runId() != runId) {
            throw new NotFoundException("backtest result not found: " + resultId);
        }
        return repository.listCoinResultsByKey(runId, row.paramKey(), limit);
    }

    public List<Map<String, Object>> trades(long runId, Long resultId, int limit) {
        requireRun(runId);
        if (resultId == null) {
            return repository.listTrades(runId, null, limit);
        }
        BacktestResultRow row = repository.findResult(resultId);
        if (row == null || row.runId() != runId) {
            throw new NotFoundException("backtest result not found: " + resultId);
        }
        // 聚合行（segment=ALL）没有自己的成交明细，明细挂在该组合的逐币行上，按 param_key 汇总返回
        if ("ALL".equals(row.segment())) {
            return repository.listTradesByParamKey(runId, row.paramKey(), limit);
        }
        return repository.listTrades(runId, resultId, limit);
    }

    /**
     * 资金曲线。只落冠军组合的逐币行（segment=COIN），聚合行天然没有曲线（返回空列表）。
     *
     * <p>必须带 runId 做归属校验：早期版本只按 resultId 查，跨批次也能读到别人的曲线。</p>
     */
    public List<Map<String, Object>> equity(long runId, long resultId, int limit) {
        requireRun(runId);
        BacktestResultRow row = repository.findResult(resultId);
        if (row == null || row.runId() != runId) {
            throw new NotFoundException("backtest result not found: " + resultId);
        }
        return repository.listEquity(resultId, limit);
    }

    public Map<String, Object> cancel(long runId) {
        requireRun(runId);
        boolean changed = repository.markCanceled(runId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("canceled", changed);
        body.put("status", repository.statusOf(runId));
        return body;
    }

    /** 把某个聚合结果提升为候选策略（写入 okx_strategy，status=CANDIDATE，mode 固定 SHADOW）。 */
    public Map<String, Object> promote(long runId, Long resultId, String name, String note) {
        BacktestRunRow run = repository.findRun(runId);
        if (run == null) {
            throw new NotFoundException("backtest run not found: " + runId);
        }
        BacktestResultRow target;
        if (resultId != null) {
            target = repository.findResult(resultId);
            if (target == null || target.runId() != runId) {
                throw new NotFoundException("backtest result not found: " + resultId);
            }
        } else if (run.bestResultId() != null) {
            target = repository.findResult(run.bestResultId());
        } else {
            target = repository.listResults(runId, 1).stream().findFirst().orElse(null);
        }
        if (target == null) {
            throw new NotFoundException("no backtest result to promote in run " + runId);
        }
        if (!"ALL".equals(target.segment())) {
            throw new BadRequestException("only aggregate results (segment=ALL) can be promoted");
        }
        String strategyName = name == null || name.isBlank()
                ? "bt-" + runId + "-" + Integer.toHexString(target.paramKey().hashCode())
                : name.trim();
        double margin = 100d;
        double accountCapital = 0d;
        try {
            JsonNode params = objectMapper.readTree(target.params());
            JsonNode m = params == null ? null : params.get("initialMarginUsd");
            if (m != null && m.isNumber()) {
                margin = m.asDouble();
            }
            JsonNode c = params == null ? null : params.get("accountCapitalUsd");
            if (c != null && c.isNumber()) {
                accountCapital = c.asDouble();
            }
        } catch (RuntimeException ex) {
            log.warn("params json of result {} unreadable: {}", target.id(), ex.toString());
        }
        if (!(accountCapital > 0d)) {
            accountCapital = margin;
        }
        // 账户权益必须覆盖回测里真正用掉的峰值保证金：否则加仓闸门会把回测开过的层全部禁掉，
        // 推荐出来的参数在影子里一层也加不动（"回测 8 层 / 影子 1 层"的对不上）。
        // 闸门只放行到权益的 95%，所以峰值要除以 LAYER_HEADROOM 才能真的重新占用到那个水位
        // （新批次自带的 accountCapitalUsd 已满足该不等式，这里只对旧批次结果起作用）。
        double peak = recommendedCapital(run, target, 0d);
        double recommended = Math.max(accountCapital,
                peak > 0d ? round2(peak / ShadowMath.LAYER_HEADROOM) : 0d);
        if (recommended > accountCapital) {
            log.info("promote result {}: accountCapitalUsd {} -> {} (peak margin of backtest)", target.id(),
                    accountCapital, recommended);
            accountCapital = recommended;
        }
        String universe = objectMapper.writeValueAsString(Map.of("instIds", coinsOf(run)));
        // 列与参数必须一致：capitalPerCoinUsd 表示"这一币能动用的账户权益"，写进 params.accountCapitalUsd；
        // initialMarginUsd 是每笔首层预算，保持回测时的值不动（改了首层名义就和回测脱节）。
        String paramsJson = target.params();
        if (accountCapital > 0d) {
            try {
                ObjectNode node = (ObjectNode) objectMapper.readTree(paramsJson);
                node.put("accountCapitalUsd", accountCapital);
                paramsJson = objectMapper.writeValueAsString(node);
            } catch (RuntimeException ex) {
                log.warn("params json of result {} not rewritable: {}", target.id(), ex.toString());
            }
        }
        long strategyId = repository.promoteStrategy(strategyName, paramsJson, universe, accountCapital, runId,
                note == null || note.isBlank() ? "promoted from backtest run " + runId : note);
        return repository.findStrategy(strategyId);
    }

    /**
     * 回测里该组合逐币的峰值已用保证金（F6：{@code returnPct = netPnl / peakMarginUsed}），取各币最大值。
     *
     * <p>逐币行同时存了 {@code net_pnl} 与 {@code net_pnl_pct}，反算即可拿到当时的峰值占用，
     * 不必把 90 天 1m 序列重新读一遍。反算不到（收益为负）时返回 {@code fallback}。</p>
     */
    private double recommendedCapital(BacktestRunRow run, BacktestResultRow target, double fallback) {
        try {
            List<BacktestResultRow> coins = repository.listCoinResultsByKey(run.id(), target.paramKey(), MAX_COINS);
            double peak = 0d;
            for (BacktestResultRow coin : coins) {
                Double pnl = coin.netPnl();
                Double pct = coin.netPnlPct();
                if (pnl == null || pct == null || pnl <= 0d || pct <= 0d) {
                    continue;
                }
                peak = Math.max(peak, pnl / pct * 100d);
            }
            return peak > 0d ? round2(peak) : fallback;
        } catch (RuntimeException ex) {
            log.warn("peak margin of result {} unreadable: {}", target.id(), ex.toString());
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> coinsOf(BacktestRunRow run) {
        if (run.request() == null || run.request().isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(run.request());
            JsonNode ids = node == null ? null : node.get("instIds");
            if (ids != null && ids.isArray()) {
                List<String> out = new ArrayList<>();
                ids.forEach(item -> out.add(item.asString()));
                return out;
            }
        } catch (RuntimeException ex) {
            log.warn("request json of run {} unreadable: {}", run.id(), ex.toString());
        }
        return List.of();
    }

    private void requireRun(long runId) {
        if (repository.findRun(runId) == null) {
            throw new NotFoundException("backtest run not found: " + runId);
        }
    }

    private boolean canceled(long runId) {
        return "CANCELED".equals(repository.statusOf(runId));
    }

    // ---------------------------------------------------------------- 值类型

    private record Plan(String bar, long startMs, long endMs, long cutMs, double marginUsd, double accountCapitalUsd,
            int topN, List<String> coins, List<StrategyParams> combos, Map<String, Object> request, String label,
            Long strategyId) {
    }

    /** 跨币聚合（样本内 + 样本外）。 */
    private static final class Aggregate {

        double isReturnSum;
        double isDrawdownSum;
        double isSharpeSum;
        double isPnlSum;
        double oosReturnSum;
        double oosDrawdownSum;
        double oosPnlSum;
        int isTrades;
        int oosTrades;
        int isWins;
        int coins;
        int oosCoins;
        int liquidations;
        /** 按「初始保证金」口径的收益率（与买入持有基准同分母）。 */
        double isCapitalSum;
        double oosCapitalSum;
        /** 同期买入持有收益（已按该组合杠杆放大，近似同曝险）%。 */
        double isBenchSum;
        double oosBenchSum;
        /**
         * 各币**峰值已用保证金**之和（样本内）：这是"同时满仓"的上界，用来回答
         * "这套参数在共享账户里到底开不开得出来"。旧版每个币各算各的权益，加起来才暴露超配。
         */
        double isPeakMarginSum;

        void addInSample(BacktestMetrics m, double margin, double priceReturnPct, double leverage) {
            if (m.trades() <= 0) {
                return;
            }
            coins++;
            isTrades += m.trades();
            isWins += (int) Math.round(m.winRate() * m.trades() / 100d);
            isReturnSum += m.returnPct();
            isDrawdownSum += m.maxDrawdownPct();
            isSharpeSum += m.sharpe();
            isPnlSum += m.netPnlUsd();
            liquidations += m.liquidations();
            isCapitalSum += margin <= 0d ? 0d : m.netPnlUsd() / margin * 100d;
            isBenchSum += priceReturnPct * leverage;
            isPeakMarginSum += peakMarginOf(m);
        }

        void addOutOfSample(BacktestMetrics m, double margin, double priceReturnPct, double leverage) {
            // OOS 样本数必须独立统计（F9）：只有 IS 有成交的币不能当 OOS 的分母，
            // 否则"OOS 正收益"只是"少数 OOS 有成交的币"的均值，系统性高估。
            oosCoins++;
            oosTrades += m.trades();
            oosReturnSum += m.returnPct();
            oosDrawdownSum += m.maxDrawdownPct();
            oosPnlSum += m.netPnlUsd();
            oosCapitalSum += margin <= 0d ? 0d : m.netPnlUsd() / margin * 100d;
            oosBenchSum += priceReturnPct * leverage;
        }

        void merge(Aggregate other) {
            isReturnSum += other.isReturnSum;
            isDrawdownSum += other.isDrawdownSum;
            isSharpeSum += other.isSharpeSum;
            isPnlSum += other.isPnlSum;
            oosReturnSum += other.oosReturnSum;
            oosDrawdownSum += other.oosDrawdownSum;
            oosPnlSum += other.oosPnlSum;
            isTrades += other.isTrades;
            oosTrades += other.oosTrades;
            oosCoins += other.oosCoins;
            isWins += other.isWins;
            coins += other.coins;
            liquidations += other.liquidations;
            isCapitalSum += other.isCapitalSum;
            oosCapitalSum += other.oosCapitalSum;
            isBenchSum += other.isBenchSum;
            oosBenchSum += other.oosBenchSum;
            isPeakMarginSum += other.isPeakMarginSum;
        }

        /** 该币的峰值已用保证金：从 returnPct 的原始分母反推（净盈亏 / 收益率 × 100），推不出按 0。 */
        private static double peakMarginOf(BacktestMetrics m) {
            double pnl = Math.abs(m.netPnlUsd());
            double ret = Math.abs(m.returnPct());
            return ret > 0d && pnl > 0d && Double.isFinite(pnl / ret) ? pnl / ret * 100d : 0d;
        }

        Summary summary(double accountCapitalUsd) {
            double isReturn = coins == 0 ? 0d : isReturnSum / coins;
            double isDrawdown = coins == 0 ? 0d : isDrawdownSum / coins;
            // F9：OOS 用自己样本数做分母；用 IS 的 coins 会把"没有 OOS 成交的币"当成 0 收益混进来以外的
            // 相反问题——只有 IS 有成交的币计入分母，少数 OOS 有成交的币被放大。
            double oosReturn = oosCoins == 0 ? 0d : oosReturnSum / oosCoins;
            double oosDrawdown = oosCoins == 0 ? 0d : oosDrawdownSum / oosCoins;
            double winRate = isTrades == 0 ? 0d : isWins * 100d / isTrades;
            // 收益全部按「初始保证金」口径与买入持有基准比较：原来 returnPct 的分母是峰值已用保证金，
            // 层数越多分母越大，基准又不存在，于是"杠杆堆出来的一条单边行情"总能拿最高分。
            double isCapital = coins == 0 ? 0d : isCapitalSum / coins;
            double isBench = coins == 0 ? 0d : isBenchSum / coins;
            double oosCapital = oosCoins == 0 ? 0d : oosCapitalSum / oosCoins;
            double oosBench = oosCoins == 0 ? 0d : oosBenchSum / oosCoins;
            double isExcess = isCapital - isBench;
            double oosExcess = oosCapital - oosBench;
            double score = 0.6d * isExcess + 0.4d * oosExcess - 0.3d * isDrawdown;
            String verdict = verdictFor(isTrades, isReturn, oosReturn, oosTrades, oosDrawdown, liquidations,
                    isDrawdown, oosExcess, isPeakMarginSum, accountCapitalUsd);
            return new Summary(score, isReturn, isDrawdown, coins == 0 ? 0d : isSharpeSum / coins, winRate,
                    isPnlSum, isTrades, oosTrades, oosReturn, oosDrawdown, liquidations, coins, oosCoins, verdict,
                    isCapital, oosCapital, isBench, oosBench, isExcess, oosExcess, isPeakMarginSum);
        }
    }

    /**
     * CANDIDATE 判定（可单测的纯函数）。
     *
     * <p>六条件同时成立才够格进影子（方案 §2.7 + 基准 / IS 回撤两道闸门）：只看收益正负，
     * 会把"凑巧翻正"或"单纯吃到单边行情 beta"的组合推上实盘。</p>
     */
    static String verdictFor(int isTrades, double isReturn, double oosReturn, int oosTrades, double oosDrawdown,
            int liquidations, double isDrawdown, double oosExcess) {
        return verdictFor(isTrades, isReturn, oosReturn, oosTrades, oosDrawdown, liquidations, isDrawdown, oosExcess,
                0d, 0d);
    }

    /**
     * 带共享账户约束的判定：{@code portfolioPeakMarginUsd} 是各币峰值已用保证金之和（同时满仓的上界），
     * 必须落在 {@code accountCapitalUsd × 95%} 以内——否则这套参数在真实账户里根本开不出来，
     * 收益再漂亮也只是纸上仓位。{@code accountCapitalUsd <= 0} 视为未给约束（保持旧行为）。
     */
    static String verdictFor(int isTrades, double isReturn, double oosReturn, int oosTrades, double oosDrawdown,
            int liquidations, double isDrawdown, double oosExcess, double portfolioPeakMarginUsd,
            double accountCapitalUsd) {
        if (isTrades < MIN_TRADES) {
            return "INSUFFICIENT";
        }
        boolean fitsAccount = !(accountCapitalUsd > 0d)
                || ShadowMath.allowsLayer(accountCapitalUsd, 0d, 0d, portfolioPeakMarginUsd);
        if (isReturn > 0d && oosReturn > 0d && oosTrades >= MIN_OOS_TRADES
                && oosDrawdown < MAX_OOS_DRAWDOWN_PCT && liquidations == 0
                && isDrawdown < MAX_IS_DRAWDOWN_PCT && oosExcess > 0d && fitsAccount) {
            return "CANDIDATE";
        }
        return "REJECT";
    }

    private record Summary(double score, double meanInSampleReturnPct, double meanInSampleDrawdownPct,
            double meanInSampleSharpe, double winRate, double netPnl, int inSampleTrades, int outOfSampleTrades,
            double meanOutOfSampleReturnPct, double meanOutOfSampleDrawdownPct, int liquidations, int coins,
            int oosCoins, String verdict, double meanInSampleCapitalReturnPct, double meanOutOfSampleCapitalReturnPct,
            double meanInSampleBenchmarkPct, double meanOutOfSampleBenchmarkPct, double meanInSampleExcessPct,
            double meanOutOfSampleExcessPct, double portfolioPeakMarginUsd) {

        double coinWinRatePct() {
            return winRate;
        }
    }

    private record Ranked(StrategyParams params, Summary summary) {
    }

    // ---------------------------------------------------------------- 工具

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round2(double value) {
        if (!Double.isFinite(value)) {
            return 0d;
        }
        return Math.round(value * 100d) / 100d;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
