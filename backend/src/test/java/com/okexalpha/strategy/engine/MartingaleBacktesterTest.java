package com.okexalpha.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引擎口径单测（方案 §2.6 的 12 条 + 保留的既有行为）。
 *
 * <p>所有用例都在 NEXT_OPEN 口径下：第 0 根 bar 只是"信号 bar"，真实开仓在第 1 根 bar 的开盘。</p>
 */
class MartingaleBacktesterTest {

    private static final long MIN = 60_000L;

    private static StrategyParams params(String side, double margin, int leverage, int maxLayers,
            double stepPct, double scale, double tpPct, double slPct, int maxHoldHours) {
        // 账户权益给足：绝大多数用例只验证价格/成本口径，不应该被加仓闸门截断
        return new StrategyParams(side, margin, leverage, maxLayers, stepPct, scale, tpPct, slPct,
                maxHoldHours, 0d, 0d, 0d, 0d, 10_000d);
    }

    private static PriceBar bar(long minute, double open, double high, double low, double close) {
        return new PriceBar(minute * MIN, open, high, low, close);
    }

    private static BacktestOutcome run(StrategyParams p, PriceBar... bars) {
        return MartingaleBacktester.run(List.of(bars), p);
    }

    // 1. 同 bar 止损价高于加仓价：只止损，不加仓
    @Test
    @DisplayName("1. low 同时触及止损与加仓价：止损先发生，不加仓")
    void stopWinsOverAddOnSameBar() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1d, 5, 15, 24);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 97, 97.5));

        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("STOP_LOSS");
        assertThat(t.layers()).isEqualTo(1);
        // lev10、margin 100 → 止损距离 = 15% × 100 / 10 = 1.5
        assertThat(t.exitPx()).isEqualTo(98.5);
    }

    // 2. 反转 bar：先加仓、再按新均价算止盈
    @Test
    @DisplayName("2. 同一根 bar 先触及加仓价再触及止盈：加仓生效后按新均价止盈")
    void addBeforeTakeProfitOnReversalBar() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1d, 1, 90, 24);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 101, 98, 99));

        BacktestTrade t = out.trades().get(0);
        assertThat(t.layers()).isEqualTo(2);
        assertThat(t.exitReason()).isEqualTo("TAKE_PROFIT");
        // 均价 = 2000 / (10 + 1000/98) ≈ 98.99，止盈 +1% ≈ 99.98
        assertThat(t.avgEntry()).isLessThan(99d);
        assertThat(t.exitPx()).isCloseTo(t.avgEntry() * 1.01, within(0.02));
    }

    // 3. 跳空低开穿过止损价：按 open 成交，不给好价
    @Test
    @DisplayName("3. 跳空穿过止损：按 open × (1−slip) 成交，不是 stopPx")
    void gapThroughStopFillsAtOpen() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1d, 5, 15, 24,
                0d, 0d, 0d, 10d, 10_000d);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 99.5, 100),
                bar(2, 96, 96.5, 95, 95.5));

        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("STOP_LOSS");
        // F11：滑点随振幅缩放。bar(2) 振幅 (96.5−95)/96 ≈ 1.56% > 参考 0.2% → 触顶 5×，10bps → 50bps。
        double slip = ShadowMath.slippageBps(10d, 96.5d, 95d, 96d) / 10_000d;
        assertThat(slip).isEqualTo(0.005d);
        assertThat(t.exitPx()).isCloseTo(96d * (1 - slip), within(1e-9));
        assertThat(t.exitPx()).isLessThan(98.5d);
    }

    // 4. 跳空高开穿过止盈价：仍按 tpPx 成交
    @Test
    @DisplayName("4. 跳空穿过止盈：仍按 tpPx 成交，不给跳空红利")
    void gapThroughTakeProfitFillsAtTarget() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1d, 1, 90, 24);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 99.5, 100),
                bar(2, 105, 106, 104, 105.5));

        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("TAKE_PROFIT");
        assertThat(t.exitPx()).isEqualTo(101);
    }

    // 6. 资金费按真实结算点扣：24h 内 3 个 UTC 00/08/16 点
    @Test
    @DisplayName("6. 资金费按结算点逐次扣，窗口末持仓不强平，次数 = 结算点个数")
    void fundingFollowsRealSettlementPoints() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1d, 50, 90, 48,
                0d, 0d, 0d, 0d, 10_000d);
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i <= 24 * 60; i++) {
            bars.add(bar(i, 100, 100, 100, 100));
        }
        List<FundingPoint> fundings = List.of(
                new FundingPoint(8 * 3_600_000L, 0.0001d),
                new FundingPoint(16 * 3_600_000L, 0.0001d),
                new FundingPoint(24 * 3_600_000L, 0.0001d));
        EngineConfig cfg = EngineConfig.standard().withFundings(fundings);
        BacktestOutcome out = MartingaleBacktester.run(bars, p, cfg);

        assertThat(out.trades()).isEmpty();
        assertThat(out.endState().inPos()).isTrue();
        assertThat(out.endState().fundingPaid()).isCloseTo(0.3d, within(1e-9));
    }

    // 7. 强平：pnl = −保证金（不计手续费）
    @Test
    @DisplayName("7. 强平按逐仓精确强平价结算，亏损等于已用保证金")
    void liquidationLosesMarginOnly() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1d, 50, 90, 24);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 99.5, 100),
                bar(2, 95, 96, 85, 86));

        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("LIQUIDATED");
        assertThat(t.exitPx()).isCloseTo(90.45d, within(0.01d));
        assertThat(t.pnl()).isEqualTo(-100d);
        assertThat(out.metrics().liquidations()).isEqualTo(1);
    }

    // 8. 五层加满后反弹止盈
    @Test
    @DisplayName("8. 五层加满后反弹触及 tp：按满层均价结算")
    void fiveLayersThenTakeProfit() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 5, 2, 1d, 5, 90, 24);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.05, 99.9, 100),
                bar(1, 100, 100.05, 99.5, 99.9),
                bar(2, 99.9, 99.95, 97.9, 98.0),
                bar(3, 98, 98.05, 95.9, 96.1),
                bar(4, 96.1, 96.2, 94.05, 94.2),
                bar(5, 94.2, 94.3, 92.1, 92.4),
                bar(6, 92.4, 101, 92.2, 100));

        BacktestTrade t = out.trades().get(0);
        assertThat(t.layers()).isEqualTo(5);
        assertThat(t.exitReason()).isEqualTo("TAKE_PROFIT");
        assertThat(t.exitPx()).isCloseTo(t.avgEntry() * 1.05, within(0.02));
    }

    // 9. 层数用尽后继续下跌：不再加仓
    @Test
    @DisplayName("9. maxLayers 用尽后继续下跌：层数停在 maxLayers")
    void noAddBeyondMaxLayers() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 1, 2, 1d, 50, 90, 24);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 95, 96),
                bar(2, 95, 95.5, 90, 91));

        BacktestTrade t = out.trades().get(0);
        assertThat(t.layers()).isEqualTo(1);
        assertThat(t.exitReason()).isIn("STOP_LOSS", "LIQUIDATED");
    }

    // 10. 跨窗口续跑：持仓连续，无 END_OF_DATA
    @Test
    @DisplayName("10. 窗口 A 末持仓 → 窗口 B 首根：持仓连续，不产生 END_OF_DATA 交易")
    void positionCarriesAcrossWindows() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1d, 1, 90, 24);
        List<PriceBar> windowA = List.of(bar(0, 100, 100.1, 99.9, 100), bar(1, 100, 100.5, 99.5, 100));
        BacktestOutcome a = MartingaleBacktester.run(windowA, p);
        assertThat(a.trades()).isEmpty();
        assertThat(a.endState().inPos()).isTrue();

        List<PriceBar> windowB = List.of(bar(2, 100, 101.5, 99.8, 101.2));
        BacktestOutcome b = MartingaleBacktester.run(windowB, p, EngineConfig.standard(), a.endState());
        assertThat(b.trades()).hasSize(1);
        BacktestTrade t = b.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("TAKE_PROFIT");
        assertThat(t.entryTsMs()).isEqualTo(bar(1, 0, 0, 0, 0).tsMs());
        assertThat(a.trades()).noneMatch(x -> "END_OF_DATA".equals(x.exitReason()));
    }

    // 11. 确定性
    @Test
    @DisplayName("11. 相同输入跑两次：指标与成交逐字段相等")
    void deterministicForSameInput() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1.5d, 1, 90, 24);
        List<PriceBar> bars = List.of(
                bar(0, 100, 100.5, 99.5, 100),
                bar(1, 100, 100.5, 98, 99),
                bar(2, 99, 100.2, 98.5, 100),
                bar(3, 100, 101.2, 99.8, 101));
        BacktestOutcome x = MartingaleBacktester.run(bars, p);
        BacktestOutcome y = MartingaleBacktester.run(bars, p);
        assertThat(x.metrics()).isEqualTo(y.metrics());
        assertThat(x.trades()).isEqualTo(y.trades());
        assertThat(x.equity()).isEqualTo(y.equity());
    }

    // 12. returnPct 分母是 peakMarginUsed
    @Test
    @DisplayName("12. returnPct 分母是峰值已用保证金，不是初始保证金")
    void returnPctUsesPeakMarginUsed() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 2, 2, 1d, 1, 90, 24);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 97.9, 99.9),
                bar(2, 99.9, 100.6, 99.5, 100.4));

        // 2 层 → 峰值已用保证金 200；满层止盈 +1% 名义 2000 → 约 +20U → 10%
        assertThat(out.metrics().returnPct()).isCloseTo(10d, within(0.3d));
    }

    // 入场规则
    @Test
    @DisplayName("BREAKOUT：收盘突破前 N 根高点才入场，且按下一根开盘成交")
    void breakoutEntryWaitsForNextOpen() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1d, 1, 90, 24);
        List<PriceBar> bars = List.of(
                bar(0, 100, 100.2, 99.8, 100),
                bar(1, 100, 100.2, 99.8, 100),
                bar(2, 100, 100.2, 99.8, 100),
                bar(3, 100, 101.5, 99.8, 101.2),
                bar(4, 101, 102.6, 100.9, 102.4));
        EngineConfig cfg = EngineConfig.standard().withEntryRule(EntryRule.BREAKOUT).withEntryLookback(3);
        BacktestOutcome out = MartingaleBacktester.run(bars, p, cfg);

        BacktestTrade t = out.trades().get(0);
        assertThat(t.entryTsMs()).isEqualTo(bar(4, 0, 0, 0, 0).tsMs());
        assertThat(t.avgEntry()).isEqualTo(101);
        assertThat(t.exitReason()).isEqualTo("TAKE_PROFIT");
    }

    @Test
    @DisplayName("EMA_CROSS：横盘序列不产生交叉，因此不入场")
    void emaCrossStaysOutOnFlatSeries() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1d, 1, 90, 24);
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            bars.add(bar(i, 100, 100.01, 99.99, 100));
        }
        BacktestOutcome out = MartingaleBacktester.run(bars, p,
                EngineConfig.standard().withEntryRule(EntryRule.EMA_CROSS));
        assertThat(out.trades()).isEmpty();
        assertThat(out.endState().inPos()).isFalse();
    }

    // 保留的既有行为
    @Test
    @DisplayName("手续费开平各收一次：开仓吃单 + 止盈挂单（maker=0 时只收一次）")
    void feesOnBothSides() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1d, 1, 90, 24,
                0.001d, 0d, 0d, 0d, 10_000d);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 101.5, 99.9, 101));

        BacktestTrade t = out.trades().get(0);
        // 止盈是限价挂单 → 平仓按 feeMaker（此处 0），只剩开仓 1000 × 0.001 = 1.0
        assertThat(t.exitReason()).isEqualTo("TAKE_PROFIT");
        assertThat(t.fee()).isEqualTo(1d);
        assertThat(t.pnl()).isEqualTo(9d);
    }

    @Test
    @DisplayName("亏损平仓仍吃单：止损按 taker 计费，与止盈的 maker 形成对照")
    void stopLossChargesTakerFee() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1d, 1, 5, 24,
                0.001d, 0d, 0d, 0d, 10_000d);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 95, 96));

        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("STOP_LOSS");
        double qty = 10d;       // 100U 保证金 × 10 倍 / 100 = 10 币
        double exitPx = 99.5d;  // 止损价 = 100 − 5% × 100U / 10 币
        assertThat(t.exitPx()).isCloseTo(exitPx, within(1e-9));
        assertThat(t.fee()).isCloseTo(qty * 100d * 0.001d + qty * exitPx * 0.001d, within(0.01d));
    }

    @Test
    @DisplayName("滑点让开仓价更高")
    void slippageMovesEntryPrice() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1d, 50, 90, 1,
                0d, 0d, 0d, 10d, 10_000d);
        BacktestOutcome out = run(p,
                bar(0, 100, 100, 100, 100),
                bar(1, 100, 100, 100, 100));

        assertThat(out.endState().inPos()).isTrue();
        assertThat(out.endState().avgEntry()).isEqualTo(100.1);
    }

    @Test
    @DisplayName("空头：价格上涨触发止盈、下跌触发加仓")
    void shortSide() {
        StrategyParams p = params(StrategyParams.SHORT, 100, 10, 3, 2, 1.5d, 1, 90, 24);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 98.5, 99));

        BacktestTrade t = out.trades().get(0);
        assertThat(t.side()).isEqualTo(StrategyParams.SHORT);
        assertThat(t.exitReason()).isEqualTo("TAKE_PROFIT");
        assertThat(t.exitPx()).isEqualTo(99);
        assertThat(t.pnl()).isGreaterThan(0);
    }

    @Test
    @DisplayName("空序列返回空结果，不抛异常")
    void emptySeries() {
        BacktestOutcome out = MartingaleBacktester.run(List.of(), params(StrategyParams.LONG, 100, 10, 3, 2, 1.5d, 1, 15, 24));
        assertThat(out.metrics().trades()).isZero();
        assertThat(out.trades()).isEmpty();
        assertThat(out.equity()).isEmpty();
    }

    @Test
    @DisplayName("分层名义价值按 layerScale 等比放大")
    void notionalScalesPerLayer() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 5, 2, 1.5d, 1, 15, 24);
        assertThat(MartingaleBacktester.notionalFor(p, 1)).isEqualTo(1000);
        assertThat(MartingaleBacktester.notionalFor(p, 2)).isEqualTo(1500);
        assertThat(MartingaleBacktester.notionalFor(p, 3)).isEqualTo(2250);
    }

    @Test
    @DisplayName("强平价公式：多空分子符号相反")
    void liquidationPriceFormula() {
        // lev5、avg100、qty5、margin100、mmr0.5% → 多 80.40 / 空 119.40
        assertThat(MartingaleBacktester.liqPrice(true, 100, 5, 100, 0.005)).isCloseTo(80.40d, within(0.01d));
        assertThat(MartingaleBacktester.liqPrice(false, 100, 5, 100, 0.005)).isCloseTo(119.40d, within(0.01d));
    }

    @Test
    @DisplayName("权益曲线按 UTC 自然日打点")
    void equityCurveIsDailySeries() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1.5d, 50, 90, 24 * 200);
        List<PriceBar> bars = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            for (int min = 0; min < 24 * 60; min++) {
                bars.add(bar(day * 24 * 60L + min, 100, 100, 100, 100));
            }
        }
        BacktestOutcome out = MartingaleBacktester.run(bars, p);
        assertThat(out.equity()).hasSize(6);
        assertThat(out.metrics().maxDrawdownPct()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("本金耗尽后停止开新仓：收益不会跌破 -100%")
    void stopsTradingAfterCapitalExhausted() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 1, 2, 1d, 50, 100, 1);
        List<PriceBar> bars = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            long base = round * 10L;
            bars.add(bar(base, 100, 100, 100, 100));
            bars.add(bar(base + 1, 99, 99, 80, 80));
            for (int i = 2; i < 10; i++) {
                bars.add(bar(base + i, 80, 80, 80, 80));
            }
        }
        BacktestOutcome out = MartingaleBacktester.run(bars, p);
        assertThat(out.metrics().returnPct()).isGreaterThanOrEqualTo(-100);
    }

    @Test
    @DisplayName("longSide() 不参与 JSON 序列化")
    void isLongIsNotSerialized() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1.5d, 1, 15, 24);
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(p);
        assertThat(json).doesNotContain("\"long\":").contains("\"side\":\"long\"");
    }

    @Test
    @DisplayName("账户权益不足时不再加仓：层数被 (权益 + 已实现盈亏) × 95% 截断")
    void layerBlockedByAccountCapital() {
        // lev10、margin100 → 首层保证金 100，账户只有 110 → 95% 上限 104.5，第二层(100)进不来
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1d, 1, 90, 24,
                0d, 0d, 0d, 0d, 110d);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 97, 97.5),
                bar(2, 97.5, 101.5, 97.4, 101));   // 拉到首层 +1% 止盈，把仓位平掉以便核对层数

        BacktestTrade t = out.trades().get(0);
        assertThat(t.layers()).isEqualTo(1);
    }

    @Test
    @DisplayName("同样行情下权益充足会加仓：闸门只和资金有关，不是无差别锁死")
    void layerAllowedWithEnoughCapital() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1d, 1, 90, 24,
                0d, 0d, 0d, 0d, 10_000d);
        BacktestOutcome out = run(p,
                bar(0, 100, 100.1, 99.9, 100),
                bar(1, 100, 100.2, 97, 97.5),
                bar(2, 97.5, 101.5, 97.4, 101));   // 加仓后均价 ~98.74，+1% 止盈同样在 bar2 触发

        BacktestTrade t = out.trades().get(0);
        assertThat(t.layers()).isEqualTo(2);
    }
}
