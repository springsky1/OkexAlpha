package com.okexalpha.strategy.engine;

import java.util.ArrayList;
import java.util.List;

import com.okexalpha.domain.TimeAxis;

/**
 * 马丁回测引擎：单币、单方向、单参数一次完整回放，<b>纯函数无 IO</b>。
 *
 * <p>决策顺序（每根 bar，保守优先）：强平 → 止损 → 逆势加仓 → 止盈 → 超时平仓。
 * 同一根 bar 同时触及止盈与止损时按止损处理（悲观假设）。</p>
 *
 * <p>I0 口径（方案 §2.2-§2.6）：</p>
 * <ul>
 *   <li>F1：首仓由 {@link EntryRule} 决定，默认 NEXT_OPEN —— 信号 bar 的下一根开盘成交；
 *       平仓后下一根再开，不再"看到什么价就按什么价开"。</li>
 *   <li>F3：加仓按预挂限价单口径（long {@code min(trigger, open)}）；实盘没预挂单时必须切
 *       {@link FillMode#SIGNAL_CLOSE} 口径，否则收益虚高。</li>
 *   <li>F4：止损按跳空更差价 + 滑点成交；止盈按 tpPx（不给跳空红利）。</li>
 *   <li>F5：资金费按 {@link FundingPoint} 的真实结算时刻逐次扣，不再"开仓 bar + n×8h"。</li>
 *   <li>F6：{@code returnPct} 分母是 {@code peakMarginUsed}，不是初始保证金。</li>
 *   <li>F7：强平价用逐仓精确式 {@link #liqPrice}，MMR 由配置给（分档后按仓位取真实值）。</li>
 *   <li>F9：加仓受账户权益约束（{@link ShadowMath#allowsLayer}，权益的 95%），
 *       不再"只看层数"无限放大名义。</li>
 *   <li>F10：费率分吃单/挂单（{@link StrategyParams#feeMaker}）——开仓与止损/超时是吃单，
 *       止盈是限价单按挂单费率，一律按吃单算会系统性低估收益。</li>
 *   <li>F11：滑点随当根振幅缩放（{@link ShadowMath#slippageBps}），固定 2bp 会低估插针行情的成交损耗。</li>
 *   <li>不再有 {@code END_OF_DATA} 强制平仓：窗口末只做 mark 估值，持仓通过 {@link EngineState} 续跑。</li>
 * </ul>
 */
public final class MartingaleBacktester {

    /** 内存里保留的逐笔成交上限（超出只影响明细，不影响指标）。 */
    public static final int MAX_STORED_TRADES = 2_000;

    private MartingaleBacktester() {
    }

    /** 旧签名：NEXT_OPEN + ALWAYS_IN + 0.5% MMR + 常量资金费。 */
    public static BacktestOutcome run(List<PriceBar> bars, StrategyParams params) {
        return run(bars, params, EngineConfig.standard(), null);
    }

    public static BacktestOutcome run(List<PriceBar> bars, StrategyParams params, EngineConfig config) {
        return run(bars, params, config, null);
    }

    /** 状态续跑：{@code start} 为上一窗口的 {@link BacktestOutcome#endState()}，null = 从空仓开始。 */
    public static BacktestOutcome run(List<PriceBar> bars, StrategyParams params, EngineConfig config,
            EngineState start) {
        EngineConfig cfg = config == null ? EngineConfig.standard() : config;
        if (bars == null || bars.isEmpty()) {
            EngineState empty = start == null ? EngineState.flat(params.initialMarginUsd()) : start;
            return new BacktestOutcome(BacktestMetrics.empty(), List.of(), List.of(), empty);
        }
        return new Sim(bars, params, cfg, start).run();
    }

    // ---------------------------------------------------------------- 纯公式

    /** 第 n 层（1-based）的名义价值。 */
    static double notionalFor(StrategyParams params, int layer) {
        return params.initialMarginUsd() * params.leverage() * Math.pow(params.layerScale(), layer - 1);
    }

    /** 止损触发价：亏掉「已用保证金 × stopLossPct%」时的价格。 */
    static double stopPrice(StrategyParams params, boolean isLong, double avgEntry, double marginUsed, double qty) {
        if (qty <= 0 || marginUsed <= 0) {
            return 0d;
        }
        double distance = params.stopLossPct() / 100d * marginUsed / qty;
        return isLong ? avgEntry - distance : avgEntry + distance;
    }

    /**
     * 逐仓强平价（F7）：强平条件 {@code equity = mark × qty × mmr}。
     * long 分子 {@code notional − margin}、short 分子 {@code notional + margin}，写反会把空单强平算到均价下方。
     */
    static double liqPrice(boolean isLong, double avg, double qty, double margin, double mmr) {
        if (qty <= 0 || avg <= 0 || mmr <= 0 || mmr >= 1) {
            return 0d;
        }
        double notional = avg * qty;
        return isLong ? (notional - margin) / (qty * (1 - mmr)) : (notional + margin) / (qty * (1 + mmr));
    }

    // ---------------------------------------------------------------- 回放

    private static final class Sim {

        private final List<PriceBar> bars;
        private final StrategyParams p;
        private final EngineConfig cfg;
        private final boolean isLong;
        private final double dir;
        private final double capital;
        private final double realizedAtStart;
        private final List<FundingPoint> fundings;

        // 持仓
        private boolean inPos;
        private int layers;
        private double qty;
        private double avgEntry;
        /** 首仓成交价（F8）：与加权均价分开存，实盘对账与逐笔比对都用它。 */
        private double firstEntryPx;
        private double costNotional;
        private double marginUsed;
        private double peakMarginUsed;
        private double lastLayerPx;
        private double feeThisTrade;
        private double fundingThisTrade;
        private double worstAdversePct;
        private long entryTs;
        private long lastFundingTs;
        private boolean pendingEntry;

        // 统计
        private double realized;
        private double peakEquity;
        private double maxDd;
        private int wins;
        private int losses;
        private int consecutiveLosses;
        private int maxConsecutiveLosses;
        private int liquidations;
        private double grossWin;
        private double grossLoss;
        private double worstTradePct;
        private double holdMsSum;
        private final List<BacktestTrade> trades = new ArrayList<>();
        private final List<EquityPoint> equity = new ArrayList<>();
        private long lastBucket = Long.MIN_VALUE;
        private int fundingCursor;

        // 入场指标（EMA_CROSS / BREAKOUT）
        private double emaFast = Double.NaN;
        private double emaSlow = Double.NaN;
        private double emaTrend = Double.NaN;
        private boolean prevFastAboveSlow;

        Sim(List<PriceBar> bars, StrategyParams p, EngineConfig cfg, EngineState start) {
            this.bars = bars;
            this.p = p;
            this.cfg = cfg;
            this.isLong = p.longSide();
            this.dir = isLong ? 1d : -1d;
            EngineState s = start == null ? EngineState.flat(p.initialMarginUsd()) : start;
            this.capital = s.initialCapital();
            this.realizedAtStart = s.realizedPnl();
            this.realized = s.realizedPnl();
            this.peakEquity = Math.max(s.peakEquity(), capital + s.realizedPnl());
            this.peakMarginUsed = s.peakMarginUsed();
            this.inPos = s.inPos();
            this.layers = s.layers();
            this.qty = s.qty();
            this.avgEntry = s.avgEntry();
            this.firstEntryPx = s.firstEntryPx();
            this.costNotional = s.costNotional();
            this.marginUsed = s.marginUsed();
            this.lastLayerPx = s.lastLayerPx();
            this.entryTs = s.entryTsMs();
            this.lastFundingTs = s.lastFundingTsMs();
            this.feeThisTrade = s.feePaid();
            this.fundingThisTrade = s.fundingPaid();
            this.worstAdversePct = s.worstAdversePct();
            this.fundings = resolveFundings(s);
        }

        private List<FundingPoint> resolveFundings(EngineState s) {
            if (!cfg.fundings().isEmpty()) {
                return cfg.fundings();
            }
            double rate = p.fundingPer8hPct() / 100d;
            long from = s.inPos() && s.lastFundingTsMs() > 0 ? s.lastFundingTsMs() : bars.get(0).tsMs() - 1L;
            long to = bars.get(bars.size() - 1).tsMs();
            return EngineConfig.syntheticFundings(rate, from, to);
        }

        BacktestOutcome run() {
            for (int i = 0; i < bars.size(); i++) {
                PriceBar bar = bars.get(i);
                applyFunding(bar);
                updateIndicators(bar, i);

                if (!inPos) {
                    if (pendingEntry) {
                        pendingEntry = false;
                        double slip = slipOf(bar);
                        double px = isLong ? bar.open() * (1 + slip) : bar.open() * (1 - slip);
                        if (px > 0 && capital + realized > 0) {
                            openFirst(px, bar);
                        }
                    } else if (shouldEnter(bar, i)) {
                        if (cfg.fillMode() == FillMode.SIGNAL_CLOSE) {
                            double slip = slipOf(bar);
                            double px = isLong ? bar.close() * (1 + slip) : bar.close() * (1 - slip);
                            if (px > 0 && capital + realized > 0) {
                                openFirst(px, bar);
                            }
                            recordEquity(bar);
                            continue;
                        }
                        pendingEntry = true;
                        recordEquity(bar);
                        continue;
                    }
                    if (!inPos) {
                        recordEquity(bar);
                        continue;
                    }
                }
                step(bar);
            }
            closeOut();
            return outcome();
        }

        private void applyFunding(PriceBar bar) {
            while (fundingCursor < fundings.size() && fundings.get(fundingCursor).tsMs() <= bar.tsMs()) {
                FundingPoint point = fundings.get(fundingCursor++);
                if (inPos && point.tsMs() > lastFundingTs) {
                    fundingThisTrade += costNotional * point.rate() * dir;
                    lastFundingTs = point.tsMs();
                }
            }
        }

        private void updateIndicators(PriceBar bar, int index) {
            double close = bar.close();
            double a = 2d / (12d + 1d);
            double b = 2d / (48d + 1d);
            double c = 2d / (200d + 1d);
            double fast = Double.isNaN(emaFast) ? close : emaFast + a * (close - emaFast);
            double slow = Double.isNaN(emaSlow) ? close : emaSlow + b * (close - emaSlow);
            double trend = Double.isNaN(emaTrend) ? close : emaTrend + c * (close - emaTrend);
            if (index >= 200) {
                prevFastAboveSlow = fast > slow;
            }
            emaFast = fast;
            emaSlow = slow;
            emaTrend = trend;
        }

        private boolean shouldEnter(PriceBar bar, int index) {
            return switch (cfg.entryRule()) {
                case ALWAYS_IN -> true;
                case NONE -> false;
                case BREAKOUT -> breakout(bar, index);
                case EMA_CROSS -> emaCross(bar, index);
            };
        }

        private boolean breakout(PriceBar bar, int index) {
            int n = cfg.entryLookback();
            if (index < n) {
                return false;
            }
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            for (int k = index - n; k < index; k++) {
                high = Math.max(high, bars.get(k).high());
                low = Math.min(low, bars.get(k).low());
            }
            return isLong ? bar.close() > high : bar.close() < low;
        }

        private boolean emaCross(PriceBar bar, int index) {
            if (index < 200 || Double.isNaN(emaFast)) {
                return false;
            }
            boolean crossUp = !prevFastAboveSlow && emaFast > emaSlow;
            boolean crossDown = prevFastAboveSlow && emaFast < emaSlow;
            return isLong ? crossUp && bar.close() > emaTrend : crossDown && bar.close() < emaTrend;
        }

        /** 当根 bar 的滑点（小数）：按振幅缩放，平静 bar 给一半、剧烈 bar 最多 5 倍。 */
        private double slipOf(PriceBar bar) {
            return ShadowMath.slippageBps(p.slippageBps(), bar.high(), bar.low(), bar.open()) / 10_000d;
        }

        private void openFirst(double px, PriceBar bar) {
            layers = 1;
            qty = notionalFor(p, 1) / px;
            costNotional = qty * px;
            marginUsed = costNotional / p.leverage();
            peakMarginUsed = Math.max(peakMarginUsed, marginUsed);
            avgEntry = px;
            firstEntryPx = px;
            lastLayerPx = px;
            feeThisTrade = costNotional * p.feeTaker();
            fundingThisTrade = 0d;
            worstAdversePct = 0d;
            entryTs = bar.tsMs();
            lastFundingTs = bar.tsMs();
            inPos = true;
        }

        private void step(PriceBar bar) {
            double adverse = isLong ? (avgEntry - bar.low()) / avgEntry : (bar.high() - avgEntry) / avgEntry;
            if (avgEntry > 0) {
                worstAdversePct = Math.max(worstAdversePct, adverse * 100d);
            }

            // 1) 强平
            double liqPx = liqPrice(isLong, avgEntry, qty, marginUsed, cfg.maintenanceMarginRate());
            boolean liquidated = liqPx > 0 && (isLong ? bar.low() <= liqPx : bar.high() >= liqPx);

            // 2) 止损
            double stopPx = stopPrice(p, isLong, avgEntry, marginUsed, qty);
            boolean stopped = !liquidated && stopPx > 0 && (isLong ? bar.low() <= stopPx : bar.high() >= stopPx);

            // 3) 逆势加仓（预挂限价单口径：跳空则按开盘、否则按触发价）
            if (!liquidated && !stopped && layers < p.maxLayers()) {
                double trigger = isLong ? lastLayerPx * (1 - p.layerStepPct() / 100d)
                        : lastLayerPx * (1 + p.layerStepPct() / 100d);
                boolean hit = trigger > 0 && (isLong ? bar.low() <= trigger : bar.high() >= trigger);
                if (hit) {
                    double fillPx = isLong ? Math.min(trigger, bar.open()) : Math.max(trigger, bar.open());
                    double addNotional = notionalFor(p, layers + 1);
                    double addMargin = addNotional / p.leverage();
                    // 资金闸门：账户权益吃不下这一层就不开。没有它，回测能加到 8 层、峰值占用 3000U，
                    // 而推荐出去的资金只有 100U —— 影子/实盘根本开不出来。
                    if (ShadowMath.allowsLayer(p.accountCapitalUsd(), realized, marginUsed, addMargin)) {
                        double addQty = addNotional / fillPx;
                        double newQty = qty + addQty;
                        double newCost = costNotional + addQty * fillPx;
                        avgEntry = newCost / newQty;
                        qty = newQty;
                        costNotional = newCost;
                        marginUsed = costNotional / p.leverage();
                        peakMarginUsed = Math.max(peakMarginUsed, marginUsed);
                        lastLayerPx = fillPx;
                        feeThisTrade += addQty * fillPx * p.feeTaker();
                        layers++;
                    }
                }
            }

            // 4) 结算判定
            if (liquidated) {
                settle(bar, "LIQUIDATED", liqPx, true, false);
                return;
            }
            if (stopped) {
                double slip = slipOf(bar);
                double px = isLong ? Math.min(stopPx, bar.open()) * (1 - slip)
                        : Math.max(stopPx, bar.open()) * (1 + slip);
                settle(bar, "STOP_LOSS", px, false, false);
                return;
            }
            double tpPx = isLong ? avgEntry * (1 + p.takeProfitPct() / 100d)
                    : avgEntry * (1 - p.takeProfitPct() / 100d);
            boolean takeProfit = tpPx > 0 && (isLong ? bar.high() >= tpPx : bar.low() <= tpPx);
            if (takeProfit) {
                // 止盈是挂单成交：费率用 maker，且不额外吃滑点（限价单按 tpPx 成交）
                settle(bar, "TAKE_PROFIT", tpPx, false, true);
                return;
            }
            if (bar.tsMs() - entryTs >= p.maxHoldHours() * 3_600_000L) {
                double slip = slipOf(bar);
                double px = isLong ? bar.close() * (1 - slip) : bar.close() * (1 + slip);
                settle(bar, "TIME_STOP", px, false, false);
                return;
            }
            recordEquity(bar);
        }

        /** @param makerExit 平仓是否为挂单成交（止盈）：是则按 {@link StrategyParams#feeMaker()} 计费。 */
        private void settle(PriceBar bar, String reason, double exitPx, boolean liquidation, boolean makerExit) {
            double exitFee = liquidation ? 0d : qty * exitPx * (makerExit ? p.feeMaker() : p.feeTaker());
            double pnl = liquidation
                    ? -Math.max(0d, marginUsed - fundingThisTrade)
                    : dir * (exitPx - avgEntry) * qty - feeThisTrade - exitFee - fundingThisTrade;
            if (liquidation) {
                liquidations++;
            }
            double pnlPct = marginUsed <= 0 ? 0d : pnl / marginUsed * 100d;
            long exitTs = bar.tsMs();
            holdMsSum += Math.max(0L, exitTs - entryTs);
            trades.add(new BacktestTrade(p.side(), entryTs, exitTs, firstEntryPx, avgEntry, exitPx, qty, costNotional,
                    round2(pnl), round2(pnlPct), round2(feeThisTrade + exitFee), round2(fundingThisTrade),
                    layers, round2(worstAdversePct), reason));
            realized += pnl;
            if (pnl >= 0d) {
                wins++;
                grossWin += pnl;
                consecutiveLosses = 0;
            } else {
                losses++;
                grossLoss += -pnl;
                consecutiveLosses++;
                maxConsecutiveLosses = Math.max(maxConsecutiveLosses, consecutiveLosses);
            }
            worstTradePct = Math.min(worstTradePct, pnlPct);
            inPos = false;
            pendingEntry = cfg.entryRule() == EntryRule.ALWAYS_IN;
            recordEquity(bar);
        }

        private void recordEquity(PriceBar bar) {
            long bucket = TimeAxis.dayBucket(bar.tsMs());
            if (bucket == lastBucket) {
                return;
            }
            lastBucket = bucket;
            addPoint(bar.tsMs(), bar.close());
        }

        private void addPoint(long tsMs, double markPx) {
            double unrealized = inPos ? dir * (markPx - avgEntry) * qty - fundingThisTrade : 0d;
            double now = capital + realized + unrealized;
            peakEquity = Math.max(peakEquity, now);
            double dd = peakEquity <= 0d ? 0d : (peakEquity - now) / peakEquity * 100d;
            maxDd = Math.max(maxDd, dd);
            equity.add(new EquityPoint(tsMs, round2(now), round2(dd)));
        }

        private void closeOut() {
            PriceBar last = bars.get(bars.size() - 1);
            if (equity.isEmpty() || equity.get(equity.size() - 1).tsMs() < last.tsMs()) {
                addPoint(last.tsMs(), last.close());
            }
        }

        private BacktestOutcome outcome() {
            int tradeCount = trades.size();
            double segmentRealized = realized - realizedAtStart;
            double returnPct = peakMarginUsed <= 0d ? 0d : segmentRealized / peakMarginUsed * 100d;
            double winRate = tradeCount == 0 ? 0d : wins * 100d / tradeCount;
            Double profitFactor = grossLoss <= 0 ? null : round2(grossWin / grossLoss);
            double sharpe = sharpe(equity);
            double calmar = maxDd <= 0d ? 0d : returnPct / maxDd;
            double avgHoldHours = tradeCount == 0 ? 0d : holdMsSum / tradeCount / 3_600_000d;

            BacktestMetrics metrics = new BacktestMetrics(round2(returnPct), round2(segmentRealized), round2(maxDd),
                    round2(calmar), round2(sharpe), round2(winRate), profitFactor, tradeCount, liquidations,
                    round2(avgHoldHours), round2(worstTradePct), maxConsecutiveLosses);

            List<BacktestTrade> kept = trades.size() <= MAX_STORED_TRADES ? List.copyOf(trades)
                    : List.copyOf(trades.subList(trades.size() - MAX_STORED_TRADES, trades.size()));

            EngineState end = new EngineState(inPos, layers, qty, avgEntry, firstEntryPx, costNotional, marginUsed,
                    peakMarginUsed, lastLayerPx, entryTs, lastFundingTs, realized, feeThisTrade, fundingThisTrade,
                    peakEquity, capital, worstAdversePct);
            return new BacktestOutcome(metrics, kept, List.copyOf(equity), end);
        }
    }

    /** 按日权益算年化夏普（无风险利率 0）。 */
    private static double sharpe(List<EquityPoint> equity) {
        if (equity.size() < 3) {
            return 0d;
        }
        double[] returns = new double[equity.size() - 1];
        double sum = 0d;
        for (int i = 1; i < equity.size(); i++) {
            double prev = equity.get(i - 1).equity();
            double cur = equity.get(i).equity();
            double r = prev == 0d ? 0d : (cur - prev) / prev;
            returns[i - 1] = r;
            sum += r;
        }
        double mean = sum / returns.length;
        double variance = 0d;
        for (double r : returns) {
            variance += (r - mean) * (r - mean);
        }
        variance /= returns.length;
        double sd = Math.sqrt(variance);
        return sd == 0d ? 0d : mean / sd * Math.sqrt(365d);
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
