package com.okexalpha.strategy.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 马丁回测引擎：单币、单方向、单参数一次完整回放，<b>纯函数无 IO</b>。
 *
 * <p>决策顺序（每根 bar，保守优先）：强平 → 止损 → 逆势加仓 → 止盈 → 超时平仓。
 * 同一根 bar 同时触及止盈与止损时按止损处理（悲观假设）。</p>
 *
 * <p>成本口径：开/平仓各收一次 taker 费；开仓价按滑点不利方向偏移；
 * 持仓期间每 8 小时按名义价值收一次资金费（正费率 = 多头付）。</p>
 */
public final class MartingaleBacktester {

    /** 维持保证金率（强平线近似：1/杠杆 - 维持保证金率）。 */
    private static final double MAINTENANCE_MARGIN_RATE = 0.005d;
    private static final long FUNDING_INTERVAL_MS = 8L * 3_600_000L;
    private static final long DAY_MS = 86_400_000L;
    /** 内存里保留的逐笔成交上限（超出只影响明细，不影响指标）。 */
    public static final int MAX_STORED_TRADES = 2_000;

    private MartingaleBacktester() {
    }

    public static BacktestOutcome run(List<PriceBar> bars, StrategyParams params) {
        if (bars == null || bars.isEmpty()) {
            return new BacktestOutcome(BacktestMetrics.empty(), List.of(), List.of());
        }
        final boolean isLong = params.longSide();
        final double slip = params.slippageBps() / 10_000d;
        final double margin = params.initialMarginUsd();
        final double fundingRate = params.fundingPer8hPct() / 100d;

        double realized = 0d;
        double peak = margin;
        double maxDd = 0d;

        boolean inPos = false;
        int layers = 0;
        double qty = 0d;
        double costNotional = 0d;
        double marginUsed = 0d;
        double avgEntry = 0d;
        double feeThisTrade = 0d;
        double fundingThisTrade = 0d;
        double lastLayerPx = 0d;
        double worstAdversePct = 0d;
        long lastFundingTs = 0L;
        long entryTs = 0L;

        int liquidations = 0;
        int wins = 0;
        int losses = 0;
        double grossWin = 0d;
        double grossLoss = 0d;
        double worstTradePct = 0d;
        int consecutiveLosses = 0;
        int maxConsecutiveLosses = 0;
        double holdMsSum = 0d;

        List<BacktestTrade> trades = new ArrayList<>();
        List<EquityPoint> equity = new ArrayList<>();
        long dayBucket = Long.MIN_VALUE;

        for (PriceBar bar : bars) {
            if (!inPos) {
                // 本金耗尽（已实现亏损吃掉初始保证金）后不再开新仓，避免出现 -130% 这类
                // 物理上不可能的结果：真实账户在权益归零时就已被强制停止。
                if (margin + realized <= 0d) {
                    break;
                }
                double px = isLong ? bar.open() * (1 + slip) : bar.open() * (1 - slip);
                if (px <= 0) {
                    continue;
                }
                layers = 1;
                qty = notionalFor(params, 1) / px;
                costNotional = qty * px;
                marginUsed = costNotional / params.leverage();
                avgEntry = px;
                lastLayerPx = px;
                feeThisTrade = costNotional * params.feeTaker();
                fundingThisTrade = 0d;
                worstAdversePct = 0d;
                lastFundingTs = bar.tsMs();
                entryTs = bar.tsMs();
                inPos = true;
            } else {
                while (bar.tsMs() >= lastFundingTs + FUNDING_INTERVAL_MS) {
                    fundingThisTrade += costNotional * fundingRate * (isLong ? 1 : -1);
                    lastFundingTs += FUNDING_INTERVAL_MS;
                }
                double adverse = isLong ? (avgEntry - bar.low()) / avgEntry : (bar.high() - avgEntry) / avgEntry;
                worstAdversePct = Math.max(worstAdversePct, adverse * 100d);
            }

            // 1) 强平
            double liqPx = isLong
                    ? avgEntry * (1d - 1d / params.leverage() + MAINTENANCE_MARGIN_RATE)
                    : avgEntry * (1d + 1d / params.leverage() - MAINTENANCE_MARGIN_RATE);
            boolean liquidated = liqPx > 0 && (isLong ? bar.low() <= liqPx : bar.high() >= liqPx);

            // 2) 止损（按已用保证金的比例）
            double stopPx = stopPrice(params, isLong, avgEntry, marginUsed, qty);
            boolean stopped = !liquidated && stopPx > 0 && (isLong ? bar.low() <= stopPx : bar.high() >= stopPx);

            // 3) 逆势加仓
            if (!liquidated && !stopped && layers < params.maxLayers()) {
                double trigger = isLong
                        ? lastLayerPx * (1 - params.layerStepPct() / 100d)
                        : lastLayerPx * (1 + params.layerStepPct() / 100d);
                boolean hit = trigger > 0 && (isLong ? bar.low() <= trigger : bar.high() >= trigger);
                if (hit) {
                    double fillPx = isLong ? Math.min(trigger, bar.open()) : Math.max(trigger, bar.open());
                    double addQty = notionalFor(params, layers + 1) / fillPx;
                    double newQty = qty + addQty;
                    double newCost = costNotional + addQty * fillPx;
                    avgEntry = newCost / newQty;
                    qty = newQty;
                    costNotional = newCost;
                    marginUsed = costNotional / params.leverage();
                    lastLayerPx = fillPx;
                    feeThisTrade += addQty * fillPx * params.feeTaker();
                    layers++;
                }
            }

            // 4) 结算判定
            String reason = null;
            double exitPx = 0d;
            if (liquidated) {
                reason = "LIQUIDATED";
                exitPx = liqPx;
            } else if (stopped) {
                reason = "STOP_LOSS";
                exitPx = stopPx;
            } else {
                double tpPx = isLong
                        ? avgEntry * (1 + params.takeProfitPct() / 100d)
                        : avgEntry * (1 - params.takeProfitPct() / 100d);
                boolean takeProfit = tpPx > 0 && (isLong ? bar.high() >= tpPx : bar.low() <= tpPx);
                if (takeProfit) {
                    reason = "TAKE_PROFIT";
                    exitPx = tpPx;
                } else if (bar.tsMs() - entryTs >= params.maxHoldHours() * 3_600_000L) {
                    reason = "TIME_STOP";
                    exitPx = isLong ? bar.close() * (1 - slip) : bar.close() * (1 + slip);
                }
            }

            if (reason != null) {
                double dir = isLong ? 1d : -1d;
                double exitFee = qty * exitPx * params.feeTaker();
                double pnl = (exitPx - avgEntry) * qty * dir - feeThisTrade - exitFee - fundingThisTrade;
                if ("LIQUIDATED".equals(reason)) {
                    // 逐仓口径：最多亏掉该仓位的保证金
                    pnl = -Math.max(0d, marginUsed - fundingThisTrade);
                    liquidations++;
                }
                double pnlPct = marginUsed <= 0 ? 0d : pnl / marginUsed * 100d;
                long exitTs = bar.tsMs();
                holdMsSum += Math.max(0L, exitTs - entryTs);

                trades.add(new BacktestTrade(params.side(), entryTs, exitTs, avgEntry, exitPx, qty, costNotional,
                        round2(pnl), round2(pnlPct), round2(feeThisTrade + exitFee), round2(fundingThisTrade),
                        layers, round2(worstAdversePct), reason));
                realized += pnl;
                if (pnl >= 0) {
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
            }

            // 5) 资金曲线（按自然日一个点）
            double unrealized = inPos ? ((isLong ? 1 : -1) * (bar.close() - avgEntry) * qty) - fundingThisTrade : 0d;
            double equityNow = margin + realized + unrealized;
            long bucket = Math.floorDiv(bar.tsMs(), DAY_MS);
            if (bucket != dayBucket) {
                dayBucket = bucket;
                peak = Math.max(peak, equityNow);
                double dd = peak <= 0 ? 0d : (peak - equityNow) / peak * 100d;
                maxDd = Math.max(maxDd, dd);
                equity.add(new EquityPoint(bar.tsMs(), round2(equityNow), round2(dd)));
            }
        }

        // 序列结束仍持仓：按最后收盘价平掉，保证指标完整
        if (inPos) {
            PriceBar last = bars.get(bars.size() - 1);
            double dir = isLong ? 1d : -1d;
            double exitPx = isLong ? last.close() * (1 - slip) : last.close() * (1 + slip);
            double exitFee = qty * exitPx * params.feeTaker();
            double pnl = (exitPx - avgEntry) * qty * dir - feeThisTrade - exitFee - fundingThisTrade;
            double pnlPct = marginUsed <= 0 ? 0d : pnl / marginUsed * 100d;
            trades.add(new BacktestTrade(params.side(), entryTs, last.tsMs(), avgEntry, exitPx, qty, costNotional,
                    round2(pnl), round2(pnlPct), round2(feeThisTrade + exitFee), round2(fundingThisTrade), layers,
                    round2(worstAdversePct), "END_OF_DATA"));
            realized += pnl;
            holdMsSum += Math.max(0L, last.tsMs() - entryTs);
            if (pnl >= 0) {
                wins++;
                grossWin += pnl;
            } else {
                losses++;
                grossLoss += -pnl;
            }
            worstTradePct = Math.min(worstTradePct, pnlPct);
            double equityNow = margin + realized;
            peak = Math.max(peak, equityNow);
            double dd = peak <= 0 ? 0d : (peak - equityNow) / peak * 100d;
            maxDd = Math.max(maxDd, dd);
            equity.add(new EquityPoint(last.tsMs(), round2(equityNow), round2(dd)));
        }

        int tradeCount = trades.size();
        double returnPct = realized / margin * 100d;
        double winRate = tradeCount == 0 ? 0d : wins * 100d / tradeCount;
        Double profitFactor = grossLoss <= 0 ? null : round2(grossWin / grossLoss);
        double sharpe = sharpe(equity);
        double calmar = maxDd <= 0 ? 0d : returnPct / maxDd;
        double avgHoldHours = tradeCount == 0 ? 0d : holdMsSum / tradeCount / 3_600_000d;

        BacktestMetrics metrics = new BacktestMetrics(round2(returnPct), round2(maxDd), round2(calmar),
                round2(sharpe), round2(winRate), profitFactor, tradeCount, liquidations, round2(avgHoldHours),
                round2(worstTradePct), maxConsecutiveLosses);

        List<BacktestTrade> kept = trades.size() <= MAX_STORED_TRADES ? trades
                : List.copyOf(trades.subList(trades.size() - MAX_STORED_TRADES, trades.size()));
        return new BacktestOutcome(metrics, kept, equity);
    }

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
            double r = prev == 0 ? 0d : (cur - prev) / prev;
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
        return sd == 0 ? 0d : mean / sd * Math.sqrt(365d);
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
