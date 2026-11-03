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
import com.okexalpha.strategy.engine.StrategyParams;
import com.okexalpha.web.dto.BacktestRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 * <p>score = 0.6×样本内平均收益率 − 0.3×样本内平均回撤 + 0.4×样本外平均收益率（百分比口径）。
 * 这样既奖励稳定盈利，也惩罚靠单边行情堆出来的高回撤曲线。</p>
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    /** 未指定区间时的默认窗口：90 天。 */
    private static final long DEFAULT_WINDOW_MS = 90L * 86_400_000L;
    private static final long MIN_WINDOW_MS = 7L * 86_400_000L;
    /** 样本内占比（其余为样本外验证段）。 */
    private static final double IN_SAMPLE_RATIO = 0.7d;
    /** 一次批次最多落多少个聚合结果行，避免 400 组全量写库。 */
    private static final int MAX_RESULT_ROWS = 300;
    /** 判定 CANDIDATE 所需的最少总成交笔数（否则样本太少不足信）。 */
    private static final int MIN_TRADES = 10;
    private static final int MAX_COINS = 50;

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

        StrategyParams base;
        try {
            base = new StrategyParams(StrategyParams.LONG, marginUsd, 5, 5, 2.5d, 1.0d, 2.0d, 25.0d, maxHoldHours,
                    properties.strategy().trading().feeTaker(), fundingPct,
                    properties.strategy().trading().slippageBps());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        }

        int maxCombos = Math.max(1, properties.strategy().backtest().maxCombos());
        int want = clamp(request.combos() == null ? properties.strategy().backtest().defaultSampleSize()
                : request.combos(), 1, maxCombos);
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
            rangeTruncated = rangeCombos > maxCombos || rangeCombos > grid.size();
            if (rangeTruncated) {
                log.info("backtest param ranges expand to {} combo(s), over max-combos {} (kept {}), sampling {}",
                        rangeCombos, maxCombos, grid.size(), want);
            }
        }
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
        requestMap.put("maxHoldHours", maxHoldHours);
        requestMap.put("fundingPer8hPct", fundingPct);
        requestMap.put("feeTaker", properties.strategy().trading().feeTaker());
        requestMap.put("slippageBps", properties.strategy().trading().slippageBps());
        requestMap.put("seed", seed);
        if (ranges != null) {
            requestMap.put("paramRanges", rangeMap(ranges));
            requestMap.put("rangeCombos", rangeCombos);
            requestMap.put("rangeTruncated", rangeTruncated);
        }

        return new Plan(bar, startMs, endMs, cutMs, marginUsd, topN, coins, combos, requestMap, request.label(),
                request.strategyId());
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

            List<Ranked> ranked = rank(combos, totals);
            int written = writeAggregates(runId, ranked);
            log.info("backtest {} coarse done: {} combo(s) ranked, {} written", runId, ranked.size(), written);

            if (canceled(runId)) {
                return;
            }
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
            for (int i = 0; i < n; i++) {
                StrategyParams params = combos.get(i);
                if (inSample.size() >= 2) {
                    local[i].addInSample(MartingaleBacktester.run(inSample, params).metrics(), plan.marginUsd());
                }
                if (outOfSample.size() >= 2) {
                    local[i].addOutOfSample(MartingaleBacktester.run(outOfSample, params).metrics(),
                            plan.marginUsd());
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
        coinDone.incrementAndGet();
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

    private List<Ranked> rank(List<StrategyParams> combos, Aggregate[] totals) {
        List<Ranked> ranked = new ArrayList<>(combos.size());
        for (int i = 0; i < combos.size(); i++) {
            ranked.add(new Ranked(combos.get(i), totals[i].summary()));
        }
        ranked.sort(Comparator.comparingDouble((Ranked r) -> -r.summary().score())
                .thenComparingDouble(r -> -r.summary().meanOutOfSampleReturnPct()));
        return ranked;
    }

    private int writeAggregates(long runId, List<Ranked> ranked) {
        int limit = Math.min(ranked.size(), MAX_RESULT_ROWS);
        for (int i = 0; i < limit; i++) {
            Ranked item = ranked.get(i);
            int rank = i + 1;
            Summary s = item.summary();
            repository.insertResult(runId, new BacktestRepository.ResultInsert(item.params().key(),
                    objectMapper.writeValueAsString(item.params()), null, "ALL",
                    objectMapper.writeValueAsString(metricsJson(s)), s.coins(), s.inSampleTrades(), s.winRate(),
                    round2(s.netPnl()), round2(s.meanInSampleReturnPct()), round2(s.meanInSampleDrawdownPct()),
                    round2(s.meanInSampleSharpe()), s.liquidations(), round2(s.meanOutOfSampleReturnPct()),
                    s.verdict(), rank));
        }
        return limit;
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
        metrics.put("benchmark", "score = 0.6*IS + 0.4*OOS - 0.3*ISdd");
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
                        // net_pnl 是美元列，net_pnl_pct 是百分数列；两者不能同值。
                        // 回测器只产出 returnPct（realized / margin × 100），这里按本金反推美元，口径与聚合行一致。
                        round2(m.returnPct() / 100d * item.params().initialMarginUsd()), round2(m.returnPct()),
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
        for (BacktestResultRow row : repository.listResults(runId, MAX_RESULT_ROWS)) {
            if (row.paramKey().equals(paramKey)) {
                return row.id();
            }
        }
        return 0;
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
        try {
            JsonNode params = objectMapper.readTree(target.params());
            JsonNode m = params == null ? null : params.get("initialMarginUsd");
            if (m != null && m.isNumber()) {
                margin = m.asDouble();
            }
        } catch (RuntimeException ex) {
            log.warn("params json of result {} unreadable: {}", target.id(), ex.toString());
        }
        String universe = objectMapper.writeValueAsString(Map.of("instIds", coinsOf(run)));
        long strategyId = repository.promoteStrategy(strategyName, target.params(), universe, margin, runId,
                note == null || note.isBlank() ? "promoted from backtest run " + runId : note);
        return repository.findStrategy(strategyId);
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

    private record Plan(String bar, long startMs, long endMs, long cutMs, double marginUsd, int topN,
            List<String> coins, List<StrategyParams> combos, Map<String, Object> request, String label,
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
        int liquidations;

        void addInSample(BacktestMetrics m, double margin) {
            if (m.trades() <= 0) {
                return;
            }
            coins++;
            isTrades += m.trades();
            isWins += (int) Math.round(m.winRate() * m.trades() / 100d);
            isReturnSum += m.returnPct();
            isDrawdownSum += m.maxDrawdownPct();
            isSharpeSum += m.sharpe();
            isPnlSum += m.returnPct() / 100d * margin;
            liquidations += m.liquidations();
        }

        void addOutOfSample(BacktestMetrics m, double margin) {
            oosTrades += m.trades();
            oosReturnSum += m.returnPct();
            oosDrawdownSum += m.maxDrawdownPct();
            oosPnlSum += m.returnPct() / 100d * margin;
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
            isWins += other.isWins;
            coins += other.coins;
            liquidations += other.liquidations;
        }

        Summary summary() {
            double isReturn = coins == 0 ? 0d : isReturnSum / coins;
            double isDrawdown = coins == 0 ? 0d : isDrawdownSum / coins;
            double oosReturn = coins == 0 ? 0d : oosReturnSum / coins;
            double oosDrawdown = coins == 0 ? 0d : oosDrawdownSum / coins;
            double winRate = isTrades == 0 ? 0d : isWins * 100d / isTrades;
            double score = 0.6d * isReturn + 0.4d * oosReturn - 0.3d * isDrawdown;
            String verdict;
            if (isTrades < MIN_TRADES) {
                verdict = "INSUFFICIENT";
            } else if (isReturn > 0 && oosReturn > 0) {
                verdict = "CANDIDATE";
            } else {
                verdict = "REJECT";
            }
            return new Summary(score, isReturn, isDrawdown, coins == 0 ? 0d : isSharpeSum / coins, winRate,
                    isPnlSum, isTrades, oosTrades, oosReturn, oosDrawdown, liquidations, coins, verdict);
        }
    }

    private record Summary(double score, double meanInSampleReturnPct, double meanInSampleDrawdownPct,
            double meanInSampleSharpe, double winRate, double netPnl, int inSampleTrades, int outOfSampleTrades,
            double meanOutOfSampleReturnPct, double meanOutOfSampleDrawdownPct, int liquidations, int coins,
            String verdict) {

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
